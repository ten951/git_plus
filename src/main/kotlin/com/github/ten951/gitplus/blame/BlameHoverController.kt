package com.github.ten951.gitplus.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Point
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal data class BlameHoverTarget(val inlay: Inlay<out BlameInlayRenderer>, val line: BlameLine)

internal interface CommitDetailsView {
    val isDisposed: Boolean
    fun update(text: String)
    fun close()
}

/** All view/lifecycle access is confined to EDT; loading delegates to the background provider. */
internal class BlameHoverController(
    private val editor: Editor,
    private val state: EditorBlameState,
    private val scope: CoroutineScope,
    parent: Disposable,
    private val load: suspend (BlameCommit) -> String?,
    private val openView: (BlameHoverTarget, String, () -> Unit, (() -> Unit)?) -> CommitDetailsView = { target, text, closed, onDiff ->
        openCommitDetails(editor, target, text, closed, onDiff)
    },
    private val openDiff: (BlameHoverTarget) -> Unit = {},
) : Disposable {
    private var disposed = false
    private var epoch = 0L
    private var target: BlameHoverTarget? = null
    private var request: Job? = null
    private var view: CommitDetailsView? = null

    init {
        Disposer.register(parent, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val next = state.hoverTarget(event.mouseEvent.point)
                if (next != null) hover(next) else if (view == null) dismiss()
            }
            override fun mouseDragged(event: EditorMouseEvent) = dismiss()
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) = dismiss()
            override fun mouseExited(event: EditorMouseEvent) { if (view == null) dismiss() }
        }, this)
        editor.scrollingModel.addVisibleAreaListener({ dismiss() }, this)
    }

    fun hover(next: BlameHoverTarget) {
        if (disposed || !state.isCurrent(next)) return
        if (target?.inlay === next.inlay && (request?.isActive == true || view?.isDisposed == false)) return
        dismiss()
        target = next
        val token = epoch
        request = scope.launch(Dispatchers.EDT) {
            delay(400)
            if (!current(token, next)) return@launch
            val commit = next.line.commit
            val header = commit?.let(::commitHeader)
            val initial = if (commit == null) "尚未提交\n当前行包含未提交的修改，没有对应提交详情。"
                else "$header\n\n正在加载提交说明…"
            val onDiff: (() -> Unit)? = if (commit != null && !next.line.uncommitted && !next.line.pathAtCommit.isNullOrEmpty()) {
                {
                    if (current(token, next)) {
                        // The diff callback starts its own task, independent of the now-closed hover request.
                        dismiss()
                        openDiff(next)
                    }
                }
            } else null
            val opened = openView(next, initial, { if (epoch == token) dismiss() }, onDiff)
            if (!current(token, next) || opened.isDisposed) { opened.close(); return@launch }
            view = opened
            if (commit == null) return@launch
            val message = try { load(commit) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { LOG.debug("Unable to load commit details", failure); null }
            if (current(token, next) && view === opened && !opened.isDisposed) {
                opened.update("$header\n\n" + (message ?: "无法加载提交说明（提交不可用、读取失败或内容过长）。移开鼠标后可重试。"))
            }
        }
    }

    private fun current(token: Long, next: BlameHoverTarget) = !disposed && epoch == token && state.isCurrent(next)

    fun dismiss() {
        epoch++
        request?.cancel()
        request = null
        target = null
        val old = view
        view = null
        old?.close()
    }

    override fun dispose() { disposed = true; dismiss() }

    companion object { private val LOG = logger<BlameHoverController>() }
}

private fun commitHeader(commit: BlameCommit): String =
    "作者：${commit.author}\n提交时间：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(commit.committedAt)}\n提交：${commit.revision}"

/** JTextArea deliberately treats commit messages as plain text, including any HTML-like content. */
private fun openCommitDetails(editor: Editor, target: BlameHoverTarget, text: String, closed: () -> Unit, onDiff: (() -> Unit)?): CommitDetailsView {
    val area = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
        background = UIManager.getColor("Panel.background")
        border = JBUI.Borders.empty(8)
        caretPosition = 0
    }
    val scroll = JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(260)) }
    val panel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        add(scroll, java.awt.BorderLayout.CENTER)
        if (onDiff != null) {
            val button = javax.swing.JButton("查看此文件的提交 Diff").apply {
                toolTipText = "比较该提交与第一父提交；首次提交与空文件比较"
                addActionListener { onDiff() }
            }
            add(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply { add(button) }, java.awt.BorderLayout.SOUTH)
        }
    }
    lateinit var popup: com.intellij.openapi.ui.popup.JBPopup
    val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
        .setTitle("Git 提交详情").setProject(editor.project)
        .setRequestFocus(false).setFocusable(true).setResizable(true)
        .setCancelOnClickOutside(true).setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(true).setCancelOnOtherWindowOpen(true)
    // Keep a small corridor between the hint and the popup so text remains selectable.
    builder.setCancelOnMouseOutCallback { event ->
        val bounds = target.inlay.bounds
        if (!editor.contentComponent.isShowing || !target.inlay.isValid || bounds == null || !popup.isVisible) true
        else {
            val origin = Point(bounds.location)
            SwingUtilities.convertPointToScreen(origin, editor.contentComponent)
            val keep = java.awt.Rectangle(origin, bounds.size)
            keep.add(java.awt.Rectangle(popup.locationOnScreen, popup.size))
            keep.grow(JBUI.scale(8), JBUI.scale(8))
            !keep.contains(event.locationOnScreen)
        }
    }
    popup = builder.createPopup()
    popup.addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) = closed()
    })
    val bounds = target.inlay.bounds
    if (bounds != null && editor.contentComponent.isShowing) {
        popup.show(RelativePoint(editor.contentComponent, Point(bounds.x, bounds.y + bounds.height + JBUI.scale(2))))
    } else popup.cancel()
    return object : CommitDetailsView {
        override val isDisposed get() = popup.isDisposed
        override fun update(text: String) { area.text = text; area.caretPosition = 0 }
        override fun close() { if (!popup.isDisposed) popup.cancel() }
    }
}
