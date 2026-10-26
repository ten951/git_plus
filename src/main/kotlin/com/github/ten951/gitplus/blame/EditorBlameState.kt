package com.github.ten951.gitplus.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer

/** UI-thread state. A request may only paint the exact editor/document/line it captured. */
class EditorBlameState(private val editor: Editor, parent: Disposable,
                       private val onHintCleared: () -> Unit = {}, private val changed: () -> Unit) : Disposable {
    class Ticket internal constructor(val editor: Editor, val generation: Long, val stamp: Long, val line: Int)

    private var generation = 0L
    private var disposed = false
    private var inlay: Inlay<BlameInlayRenderer>? = null
    private var attribution: BlameLine? = null
    private var primaryLine = editor.caretModel.primaryCaret.logicalPosition.line

    init {
        Disposer.register(parent, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                checkPrimaryLine()
            }
            override fun caretAdded(event: CaretEvent) = checkPrimaryLine()
            override fun caretRemoved(event: CaretEvent) = checkPrimaryLine()
        }, this)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = invalidate()
        }, this)
    }

    private fun checkPrimaryLine() {
        val current = editor.caretModel.primaryCaret.logicalPosition.line
        if (current != primaryLine) {
            primaryLine = current
            invalidate()
        }
    }

    fun invalidate(notifyChange: Boolean = true) {
        if (disposed) return
        generation++
        clearHint()
        if (notifyChange) changed()
    }

    fun beginRequest(): Ticket {
        generation++
        clearHint()
        return Ticket(editor, generation, editor.document.modificationStamp, editor.caretModel.primaryCaret.logicalPosition.line)
    }

    fun publish(ticket: Ticket, line: BlameLine?) {
        if (disposed || editor.isDisposed || ticket.editor !== editor || ticket.generation != generation ||
            ticket.stamp != editor.document.modificationStamp || ticket.line != editor.caretModel.primaryCaret.logicalPosition.line) return
        clearHint()
        val text = line?.presentation() ?: return
        attribution = line
        if (ticket.line >= editor.document.lineCount) return
        inlay = editor.inlayModel.addAfterLineEndElement(editor.document.getLineEndOffset(ticket.line), true, BlameInlayRenderer(text))
    }

    private fun clearHint() {
        onHintCleared()
        inlay?.let(Disposer::dispose)
        inlay = null
        attribution = null
    }

    internal fun hoverTarget(point: java.awt.Point): BlameHoverTarget? {
        val hint = inlay ?: return null
        val line = attribution ?: return null
        return if (hint.isValid && hint.bounds?.contains(point) == true) BlameHoverTarget(hint, line) else null
    }

    internal fun isCurrent(target: BlameHoverTarget): Boolean =
        !disposed && !editor.isDisposed && inlay === target.inlay && target.inlay.isValid && attribution === target.line

    fun updatePresentation() {
        val hint = inlay ?: return
        hint.renderer.text = attribution?.presentation() ?: return
        hint.update()
        editor.contentComponent.repaint()
    }

    override fun dispose() {
        disposed = true
        generation++
        clearHint()
    }
}
