package com.github.ten951.gitplus.blame

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*

class CommitDiffControllerTest : BasePlatformTestCase() {
    fun testBackgroundResultReachesDiffOnEdt() {
        val change = change()
        val shown = mutableListOf<Change>()
        runController(load = {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertEquals("a".repeat(40), it.revision)
            assertEquals("old name.txt", it.pathAtCommit)
            change
        }, show = {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            shown.add(it)
        }) { controller ->
            controller.open(request())
            waitUntil { shown.isNotEmpty() }
            assertSame(change, shown.single())
        }
    }

    fun testDisposeCancelsLoadingAndRejectsLateNonCancellableCompletion() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var shown = false
        runController(load = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); finished.complete(Unit); change() }
        }, show = { shown = true }) { controller ->
            controller.open(request())
            waitUntil { entered.isCompleted }
            Disposer.dispose(controller)
            release.complete(Unit)
            waitUntil { finished.isCompleted }
            pumpFor(100)
            assertFalse(shown)
        }
    }

    fun testFailureIsReportedAndCancelledRequestDoesNotNotify() {
        val failures = mutableListOf<String>()
        runController(load = { throw java.io.IOException("objects unavailable") }, failure = failures::add) { controller ->
            controller.open(request())
            waitUntil { failures.isNotEmpty() }
            assertTrue(failures.single().contains("objects unavailable"))
        }
        var notified = false
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        runController(load = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, failure = { notified = true }) { controller ->
            controller.open(request())
            waitUntil { entered.isCompleted }
            Disposer.dispose(controller)
            waitUntil { cancelled.isCompleted }
            assertFalse(notified)
        }
    }

    private fun runController(load: suspend (CommitDiffRequest) -> Change, show: (Change) -> Unit = {},
                              failure: (String) -> Unit = {}, block: (CommitDiffController) -> Unit) {
        myFixture.configureByText("source.txt", "code\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = CommitDiffController(project, scope, testRootDisposable, load, show, failure)
        try { block(controller) } finally { Disposer.dispose(controller); scope.cancel() }
    }
    private fun change() = Change(null, com.intellij.openapi.vcs.changes.SimpleContentRevision(
        "new", com.intellij.vcsUtil.VcsUtil.getFilePath("/tmp/commit-diff-source.txt"), "a".repeat(40)))
    private fun request() = CommitDiffRequest(myFixture.file.virtualFile.parent, "a".repeat(40), "old name.txt")
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for Diff lifecycle")
    }
    private fun pumpFor(ms: Long) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10) }
    }
}
