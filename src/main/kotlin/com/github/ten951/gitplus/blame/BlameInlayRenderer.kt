package com.github.ten951.gitplus.blame

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

class BlameInlayRenderer(val text: String) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return inlay.editor.contentComponent.getFontMetrics(font).stringWidth("  $text").coerceAtLeast(1)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val graphics = g.create() as Graphics2D
        try {
            val editor = inlay.editor
            graphics.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            graphics.color = editor.colorsScheme.getColor(EditorColors.ANNOTATIONS_COLOR) ?: JBColor.GRAY
            val metrics = graphics.fontMetrics
            val baseline = targetRegion.y + (editor.lineHeight - metrics.height) / 2.0 + metrics.ascent
            graphics.drawString("  $text", targetRegion.x.toFloat(), baseline.toFloat())
        } finally {
            graphics.dispose()
        }
    }
}
