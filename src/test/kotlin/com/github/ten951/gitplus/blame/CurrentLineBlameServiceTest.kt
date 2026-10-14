package com.github.ten951.gitplus.blame

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path

class CurrentLineBlameServiceTest : BasePlatformTestCase() {
    private var targetEditor: Editor? = null
    fun testBackgroundQueryRefreshesAfterCaretMovesAndDocumentEdits() {
        val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "git-plus-integration-")
        Disposer.register(testRootDisposable, com.intellij.openapi.Disposable { root.toFile().deleteRecursively() })
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        git("init", "-q")
        git("config", "user.name", "integration author")
        git("config", "user.email", "test@example.invalid")
        val path = root.resolve("sample.txt")
        Files.writeString(path, "first\nsecond\nthird\n")
        git("add", ".")
        git("commit", "-qm", "integration commit")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        myFixture.configureFromExistingVirtualFile(file)
        val editor = EditorFactory.getInstance().createEditor(myFixture.editor.document, project, file, false, EditorKind.MAIN_EDITOR)
        targetEditor = editor
        val service = project.service<CurrentLineBlameService>()
        try {
            service.start()
            val textEditor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(editor)
            project.messageBus.syncPublisher(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER)
                .selectionChanged(com.intellij.openapi.fileEditor.FileEditorManagerEvent(
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project), null, null, null, file, textEditor,
                    com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()))
            waitForHint("integration author")
            editor.caretModel.moveToOffset(6)
            waitForHint("integration commit")
            assertEquals(1, editor.document.getLineNumber(hint()!!.offset))
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(6, "changed ")
            }
            waitForHint("尚未提交")
            editor.caretModel.moveToOffset(0)
            waitForHint("integration author")
            assertEquals("first\nsecond\nthird\n", Files.readString(path))
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
            targetEditor = null
        }
    }

    private fun hint() = targetEditor!!.inlayModel.getAfterLineEndElementsInRange(
        0, targetEditor!!.document.textLength, BlameInlayRenderer::class.java).singleOrNull()

    private fun waitForHint(expected: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (hint()?.renderer?.text?.contains(expected) == true) return
            Thread.sleep(20)
        }
        fail("Expected hint containing '$expected'; actual: ${hint()?.renderer?.text}")
    }
}
