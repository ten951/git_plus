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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
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
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refresh() })
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // HEAD, refs, index, external file edits and checkout operations all invalidate cached ownership.
                if (events.isNotEmpty()) refresh()
            }
        })
    }

    private fun attach(editor: Editor) {
        if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR || editor.isDisposed || sessions.containsKey(editor)) return
        val session = Session(editor)
        sessions[editor] = session
        session.schedule()
    }

    private fun refresh() {
        scope.launch(Dispatchers.EDT) {
            sessions.values.forEach { it.refresh() }
        }
    }

    private inner class Session(private val editor: Editor) : Disposable {
        private var request: Job? = null
        private var closed = false
        private var provider: BlameProvider? = null
        private var executable: String? = null
        private val state: EditorBlameState
        private val periodicRefresh: Job

        init {
            Disposer.register(this@CurrentLineBlameService, this)
            state = EditorBlameState(editor, this) { schedule() }
            periodicRefresh = scope.launch(Dispatchers.EDT) {
                while (isActive) {
                    delay(60_000)
                    if (!closed && !editor.isDisposed && editor.contentComponent.isShowing) schedule()
                }
            }
        }

        fun refresh() {
            provider?.clearCache()
            state.invalidate()
        }

        fun schedule() {
            request?.cancel()
            if (closed) return
            request = scope.launch(Dispatchers.EDT) {
                delay(150)
                if (closed || editor.isDisposed) return@launch
                val ticket = state.beginRequest()
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@launch
                if (!file.isValid || !file.isInLocalFileSystem || file.fileType.isBinary || editor.document.textLength > 1_000_000) return@launch
                val content = editor.document.text
                val path = Path.of(file.path)
                val charset = file.charset
                val separator = FileDocumentManager.getInstance().getLineSeparator(file, project)
                val bom = file.bom?.clone()
                try {
                    val job = coroutineContext[Job]!!
                    val result = withContext(Dispatchers.IO) {
                        val git = GitExecutableManager.getInstance().getPathToGit(project)
                        if (executable != git || provider == null) {
                            executable = git
                            provider = BlameProvider(git) { LOG.debug(it) }
                        }
                        // The cancellation check belongs to this request, not the job that first populated the cache.
                        val query = provider!!
                        query.query(path, content, charset, separator, bom) { job.ensureActive() }
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
            request?.cancel()
            periodicRefresh.cancel()
            provider?.clearCache()
        }
    }

    override fun dispose() { sessions.clear() }

    companion object { private val LOG = logger<CurrentLineBlameService>() }
}
