package com.github.ten951.gitplus.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import kotlinx.coroutines.*

/** A click freezes the historical request. Closing its hover card must not cancel this task. */
internal class CommitDiffController(
    private val project: Project,
    private val scope: CoroutineScope,
    parent: Disposable,
    private val load: suspend (CommitDiffRequest) -> Change = { request ->
        coroutineToIndicator { _ -> CommitDiffLoader(project).load(request) }
    },
    private val show: (Change) -> Unit = { ShowDiffAction.showDiffForChange(project, listOf(it)) },
    private val onFailure: (String) -> Unit = {
        VcsNotifier.getInstance(project).notifyWarning("git.plus.commit.diff.failed", "Git Plus：无法打开提交 Diff", StringUtil.escapeXmlEntities(it))
    },
) : Disposable {
    private var request: Job? = null
    private var generation = 0L
    private var disposed = false

    fun open(snapshot: CommitDiffRequest) {
        if (disposed || project.isDisposed) return
        request?.cancel()
        val token = ++generation
        request = scope.launch(Dispatchers.EDT) {
            try {
                val change = withContext(Dispatchers.IO) { load(snapshot) }
                ensureActive()
                if (!disposed && !project.isDisposed && generation == token) show(change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw CancellationException("Commit Diff cancelled", cancelled)
            } catch (failure: Exception) {
                ensureActive()
                LOG.debug("Unable to open commit Diff", failure)
                if (!disposed && !project.isDisposed && generation == token) {
                    onFailure(failure.localizedMessage ?: "无法读取提交；请检查本地Git历史是否完整。")
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        generation++
        request?.cancel()
        request = null
    }

    init { Disposer.register(parent, this) }
    companion object { private val LOG = logger<CommitDiffController>() }
}
