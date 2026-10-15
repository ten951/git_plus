package com.github.ten951.gitplus.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class CurrentLineBlameService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val sessions = mutableMapOf<Editor, Session>()
    private var started = false
    private val pendingChanges = mutableListOf<ChangedPath>()
    private val pendingRoots = mutableSetOf<Path>()
    private var refreshJob: Job? = null
    private class QuerySource(val provider: BlameProvider, val coordinator: BlameQueryCoordinator, val details: CommitDetailsProvider)
    private val sources = mutableMapOf<String, QuerySource>() // EDT confined; one cache per configured executable.

    private fun source(executable: String): QuerySource = sources.getOrPut(executable) {
        val provider = BlameProvider(executable) { LOG.debug(it) }
        QuerySource(provider, BlameQueryCoordinator(scope) { request ->
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]!!
            if (provider.generationOf(request.file) != request.generation) throw CancellationException("Snapshot invalidated")
            provider.queryAsync(request) { job.ensureActive() }
        }, CommitDetailsProvider(scope, executable) { LOG.debug(it) })
    }

    fun start() {
        if (started || project.isDisposed) return
        started = true
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = attach(event.editor)
            override fun editorReleased(event: EditorFactoryEvent) {
                sessions.remove(event.editor)?.let(Disposer::dispose)
            }
        }, this)
        factory.allEditors.forEach(::attach)
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            enqueueRefresh(emptyList(), Path.of(it.root.path))
        })
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                (event.oldEditor as? TextEditor)?.editor?.let { sessions[it]?.deactivate() }
                (event.newEditor as? TextEditor)?.editor?.let { sessions[it]?.activate() }
            }
        })
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            scope.launch(Dispatchers.EDT) { sessions.values.forEach { it.repaint() } }
        })
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = events.flatMap { event ->
                    val directory = event.file?.isDirectory == true || event is VFileCreateEvent && event.isDirectory
                    val oldPath = when (event) {
                        is VFileMoveEvent -> event.oldPath
                        is VFilePropertyChangeEvent -> event.oldPath
                        else -> null
                    }
                    listOfNotNull(event.path, oldPath).distinct().map { ChangedPath(Path.of(it), directory) }
                }
                if (paths.isNotEmpty()) enqueueRefresh(paths)
            }
        })
    }

    private fun attach(editor: Editor) {
        if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR || editor.isDisposed || sessions.containsKey(editor)) return
        val session = Session(editor)
        sessions[editor] = session
        session.schedule()
    }

    private fun enqueueRefresh(changes: List<ChangedPath>, root: Path? = null) {
        scope.launch(Dispatchers.EDT) {
            pendingChanges.addAll(changes)
            root?.let(pendingRoots::add)
            refreshJob?.cancel()
            refreshJob = scope.launch(Dispatchers.EDT) {
                delay(50)
                val batch = pendingChanges.toList()
                val roots = pendingRoots.toSet()
                pendingChanges.clear()
                pendingRoots.clear()
                sessions.values.forEach { if (it.affected(batch, roots)) it.refresh() }
            }
        }
    }

    private inner class Session(private val editor: Editor) : Disposable {
        private var request: Job? = null
        private var closed = false
        private var querySource: QuerySource? = null
        private var dirty = false
        private var active = false
        private val state: EditorBlameState
        private var hover: BlameHoverController? = null
        private val periodicRefresh: Job

        init {
            Disposer.register(this@CurrentLineBlameService, this)
            state = EditorBlameState(editor, this, { hover?.dismiss() }) { schedule() }
            hover = BlameHoverController(editor, state, scope, this, { commit ->
                val selected = querySource
                val file = FileDocumentManager.getInstance().getFile(editor.document)
                val repository = file?.let { selected?.provider?.repositoryPaths(Path.of(it.path)) }
                if (selected != null && repository != null) selected.details.message(repository, commit.revision) else null
            })
            periodicRefresh = scope.launch(Dispatchers.EDT) {
                while (isActive) {
                    delay(60_000)
                    if (!closed && !editor.isDisposed && (active || editor.contentComponent.isShowing)) schedule()
                }
            }
        }

        fun refresh() {
            FileDocumentManager.getInstance().getFile(editor.document)?.let {
                val path = Path.of(it.path)
                querySource?.let { selected ->
                    selected.provider.repositoryPaths(path)?.let(selected.details::invalidate)
                    selected.provider.invalidateFile(path)
                }
            }
            request?.cancel()
            dirty = true
            state.invalidate(false)
            if (active || editor.contentComponent.isShowing) schedule()
        }

        fun affected(changes: List<ChangedPath>, roots: Set<Path>): Boolean {
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
            val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
            val paths = repository?.let {
                RepositoryPaths(Path.of(it.root.path), it.repositoryFiles.headFile.parentFile.toPath(),
                    it.repositoryFiles.refsHeadsFile.parentFile.parentFile.toPath())
            } ?: querySource?.provider?.repositoryPaths(Path.of(file.path))
            return paths?.root in roots || BlameRefreshScope.affects(Path.of(file.path), paths, changes)
        }

        fun activate() { active = true; if (dirty) schedule() else state.updatePresentation() }
        fun deactivate() {
            hover?.dismiss()
            active = false
            if (!editor.contentComponent.isShowing) {
                request?.cancel()
                dirty = true
            }
        }
        fun repaint() = state.updatePresentation()

        fun schedule() {
            request?.cancel()
            if (closed) return
            if (!active && !editor.contentComponent.isShowing) {
                dirty = true
                return
            }
            dirty = false
            request = scope.launch(Dispatchers.EDT) {
                delay(150)
                if (closed || editor.isDisposed) return@launch
                if (!active && !editor.contentComponent.isShowing) {
                    dirty = true
                    return@launch
                }
                val ticket = state.beginRequest()
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@launch
                if (!file.isValid || !file.isInLocalFileSystem || file.fileType.isBinary || editor.document.textLength > 1_000_000) return@launch
                val content = editor.document.text
                val path = Path.of(file.path)
                val charset = file.charset
                val separator = FileDocumentManager.getInstance().getLineSeparator(file, project)
                val bom = file.bom?.clone()
                try {
                    val git = withContext(Dispatchers .IO) { GitExecutableManager.getInstance().getPathToGit(project) }
                    val selected = source(git)
                    querySource = selected
                    val snapshot = BlameRequest(path, content, charset, separator, bom?.toList().orEmpty(), selected.provider.generationOf(path))
                    val result = withContext(Dispatchers.Default) {
                        selected.coordinator.query(snapshot)
                    }
                    ensureActive()
                    if (!closed && FileDocumentManager.getInstance().getFile(editor.document) == file) state.publish(ticket, result[ticket.line])
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (exception: Exception) {
                    LOG.debug("Unable to query current-line Git blame", exception)
                    state.publish(ticket, null)
                }
            }
        }

        override fun dispose() {
            closed = true
            hover?.dismiss()
            request?.cancel()
            periodicRefresh.cancel()
            querySource = null
        }
    }

    override fun dispose() {
        refreshJob?.cancel()
        pendingChanges.clear()
        pendingRoots.clear()
        sessions.clear()
        sources.values.forEach { it.provider.clearCache(); it.details.invalidate() }
        sources.clear()
    }

    companion object { private val LOG = logger<CurrentLineBlameService>() }
}
