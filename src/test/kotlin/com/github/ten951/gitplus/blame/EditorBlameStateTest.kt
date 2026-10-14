package com.github.ten951.gitplus.blame

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import java.time.Instant

class EditorBlameStateTest : BasePlatformTestCase() {
    private val committed = BlameLine(BlameCommit("abc", "author", Instant.now(), "message"))

    fun testCaretChangeClearsOldHintAndRejectsStaleResult() {
        myFixture.configureByText("sample.txt", "first\nsecond\n")
        val state = EditorBlameState(myFixture.editor, testRootDisposable) {}
        val first = state.beginRequest()
        state.publish(first, committed)
        assertEquals(1, hints().size)
        myFixture.editor.caretModel.moveToOffset(6)
        assertTrue(hints().isEmpty())
        state.publish(first, committed)
        assertTrue(hints().isEmpty())
        state.publish(state.beginRequest(), committed)
        assertEquals(1, hints().size)
        assertEquals(6, myFixture.editor.document.getLineStartOffset(
            myFixture.editor.document.getLineNumber(hints().single().offset)))
    }

    fun testDocumentEditClearsHintAndRejectsOldVersion() {
        myFixture.configureByText("sample.txt", "first\nsecond\n")
        val state = EditorBlameState(myFixture.editor, testRootDisposable) {}
        val ticket = state.beginRequest()
        state.publish(ticket, committed)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "new\n") }
        state.publish(ticket, committed)
        assertTrue(hints().isEmpty())
    }

    fun testMultipleEditorsHaveIndependentHintsAndDisposeClearsThem() {
        myFixture.configureByText("sample.txt", "first\nsecond\n")
        val second = EditorFactory.getInstance().createEditor(myFixture.editor.document, project)
        try {
            val firstState = EditorBlameState(myFixture.editor, testRootDisposable) {}
            val secondState = EditorBlameState(second, testRootDisposable) {}
            firstState.publish(firstState.beginRequest(), committed)
            secondState.publish(secondState.beginRequest(), BlameLine(uncommitted = true))
            assertEquals(1, hints().size)
            val otherHints = second.inlayModel.getAfterLineEndElementsInRange(0, 100, BlameInlayRenderer::class.java)
            assertEquals("尚未提交", otherHints.single().renderer.text)
            val before = myFixture.editor.document.text
            Disposer.dispose(firstState)
            assertTrue(hints().isEmpty())
            assertEquals(1, otherHints.size)
            assertEquals(before, myFixture.editor.document.text)
            Disposer.dispose(secondState)
            assertTrue(second.inlayModel.getAfterLineEndElementsInRange(0, 100).isEmpty())
        } finally {
            EditorFactory.getInstance().releaseEditor(second)
        }
    }

    fun testSameLineMovementKeepsHintAndNewRequestSupersedesOldOne() {
        myFixture.configureByText("sample.txt", "first\nsecond\n")
        val state = EditorBlameState(myFixture.editor, testRootDisposable) {}
        val old = state.beginRequest()
        val current = state.beginRequest()
        state.publish(current, committed)
        myFixture.editor.caretModel.moveToOffset(2)
        assertEquals(1, hints().size)
        state.publish(old, BlameLine(uncommitted = true))
        assertTrue(hints().single().renderer.text.contains("author"))
    }

    fun testAddingAndRemovingPrimaryCaretInvalidatesHint() {
        myFixture.configureByText("sample.txt", "first\nsecond\n")
        val editor = myFixture.editor
        val state = EditorBlameState(editor, testRootDisposable) {}
        state.publish(state.beginRequest(), committed)
        val added = editor.caretModel.addCaret(com.intellij.openapi.editor.VisualPosition(1, 0), true)!!
        assertTrue("Old primary line must lose its hint", hints().isEmpty())
        state.publish(state.beginRequest(), committed)
        assertEquals(1, editor.document.getLineNumber(hints().single().offset))
        editor.caretModel.removeCaret(added)
        assertTrue("Removed primary line must lose its hint", hints().isEmpty())
    }

    private fun hints() = myFixture.editor.inlayModel
        .getAfterLineEndElementsInRange(0, myFixture.editor.document.textLength, BlameInlayRenderer::class.java)
}
