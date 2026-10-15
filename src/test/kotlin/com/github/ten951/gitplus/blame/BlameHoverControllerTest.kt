package com.github.ten951.gitplus.blame

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import java.time.Instant

class BlameHoverControllerTest : BasePlatformTestCase() {
    private class View(var text: String, private val closed: () -> Unit) : CommitDetailsView {
        override var isDisposed = false
        override fun update(text: String) { this.text = text }
        override fun close() { isDisposed = true; closed() }
    }

    fun testFullMetadataAndPlainTextMessageArriveWithoutChangingInlineText() {
        runHoverTest { state, controller, views, _ ->
            val line = BlameLine(BlameCommit("a".repeat(40), "作者", Instant.parse("2026-10-01T00:00:00Z"), "摘要"))
            state.publish(state.beginRequest(), line)
            val inlay = hints().single()
            controller.hover(BlameHoverTarget(inlay, line))
            waitUntil { views.lastOrNull()?.text?.contains("完整正文 <html>") == true }
            val text = views.last().text
            assertTrue(text.contains("a".repeat(40)))
            assertTrue(text.contains("作者"))
            assertTrue(text.contains("2026-10-01"))
            assertEquals("摘要", inlay.renderer.text.substringAfterLast(" · "))
            assertEquals("code\nsecond\n", myFixture.editor.document.text)
        }
    }

    fun testInvalidationCancelsPendingDetailsAndClosesView() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        runHoverTest(load = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }) { state, controller, views, _ ->
            val line = BlameLine(BlameCommit("a".repeat(40), "author", Instant.now(), "subject"))
            state.publish(state.beginRequest(), line)
            controller.hover(BlameHoverTarget(hints().single(), line))
            waitUntil { entered.isCompleted }
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "edit ") }
            waitUntil { cancelled.isCompleted }
            assertTrue(views.single().isDisposed)
            assertTrue(hints().isEmpty())
        }
    }

    fun testUncommittedHoverDoesNotLoadGitAndDisposeCancelsDelay() {
        var loads = 0
        runHoverTest(load = { loads++; "unexpected" }) { state, controller, views, _ ->
            val line = BlameLine(uncommitted = true)
            state.publish(state.beginRequest(), line)
            controller.hover(BlameHoverTarget(hints().single(), line))
            waitUntil { views.isNotEmpty() }
            assertTrue(views.single().text.contains("尚未提交"))
            assertEquals(0, loads)
            controller.dismiss()
            assertTrue(views.single().isDisposed)
            controller.hover(BlameHoverTarget(hints().single(), line))
            Disposer.dispose(controller)
            pumpFor(600)
            assertEquals(1, views.size)
        }
    }

    fun testFailedDetailsKeepMetadataAndShowFailure() {
        runHoverTest(load = { null }) { state, controller, views, _ ->
            val line = BlameLine(BlameCommit("b".repeat(40), "author", Instant.now(), "subject"))
            state.publish(state.beginRequest(), line)
            controller.hover(BlameHoverTarget(hints().single(), line))
            waitUntil { views.lastOrNull()?.text?.contains("无法加载提交说明") == true }
            assertTrue(views.single().text.contains("b".repeat(40)))
            assertEquals(1, hints().size)
        }
    }

    fun testEditorMouseMotionHitsOnlyTheCurrentInlay() {
        runHoverTest { state, _, views, _ ->
            val line = BlameLine(BlameCommit("d".repeat(40), "author", Instant.now(), "subject"))
            state.publish(state.beginRequest(), line)
            val bounds = hints().single().bounds!!
            val point = java.awt.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            assertNotNull(state.hoverTarget(point))
            assertNull(state.hoverTarget(java.awt.Point(bounds.x + bounds.width + 1, point.y)))
            myFixture.editor.contentComponent.dispatchEvent(java.awt.event.MouseEvent(
                myFixture.editor.contentComponent, java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, point.x, point.y, 0, false))
            waitUntil { views.lastOrNull()?.text?.contains("完整正文") == true }
            assertEquals(1, views.size)
        }
    }

    fun testLateCompletionCannotReplaceNewTarget() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runHoverTest(load = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); "old details" }
        }) { state, controller, views, _ ->
            val old = BlameLine(BlameCommit("c".repeat(40), "old", Instant.now(), "subject"))
            state.publish(state.beginRequest(), old)
            controller.hover(BlameHoverTarget(hints().single(), old))
            waitUntil { entered.isCompleted }
            val replacement = BlameLine(uncommitted = true)
            state.publish(state.beginRequest(), replacement)
            controller.hover(BlameHoverTarget(hints().single(), replacement))
            waitUntil { views.size == 2 }
            release.complete(Unit)
            pumpFor(100)
            assertTrue(views.first().isDisposed)
            assertTrue(views.last().text.contains("尚未提交"))
            assertFalse(views.last().text.contains("old details"))
        }
    }

    private fun runHoverTest(load: suspend (BlameCommit) -> String? = { "摘要\n\n完整正文 <html> & 中文" },
                             block: (EditorBlameState, BlameHoverController, MutableList<View>, CoroutineScope) -> Unit) {
        myFixture.configureByText("sample.txt", "code\nsecond\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val views = mutableListOf<View>()
        var controller: BlameHoverController? = null
        val state = EditorBlameState(myFixture.editor, testRootDisposable, { controller?.dismiss() }) {}
        controller = BlameHoverController(myFixture.editor, state, scope, testRootDisposable, load,
            { _, text, closed -> View(text, closed).also(views::add) })
        try { block(state, controller, views, scope) } finally { Disposer.dispose(controller); scope.cancel() }
    }

    private fun hints() = myFixture.editor.inlayModel.getAfterLineEndElementsInRange(
        0, myFixture.editor.document.textLength, BlameInlayRenderer::class.java)
    private fun waitUntil(test: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (test()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for hover state")
    }
    private fun pumpFor(milliseconds: Long) {
        val deadline = System.nanoTime() + milliseconds * 1_000_000
        while (System.nanoTime() < deadline) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10) }
    }
}
