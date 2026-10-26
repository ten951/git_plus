package com.github.ten951.gitplus.blame

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/** Reconstructs the editor snapshot without normalizing unchanged disk line endings. */
internal object DocumentSnapshotEncoder {
    fun read(file: Path): ByteArray = Files.newInputStream(file).use {
        val bytes = it.readNBytes(8_000_001)
        if (bytes.size > 8_000_000) throw IOException("Blame source exceeds snapshot byte limit")
        bytes
    }

    fun encode(disk: ByteArray, content: String, charset: Charset, separator: String,
               bom: ByteArray?, checkCanceled: () -> Unit): ByteArray {
        checkCanceled()
        val offset = if (bom != null && disk.size >= bom.size && disk.copyOfRange(0, bom.size).contentEquals(bom)) bom.size else 0
        val original = charset.newDecoder().decode(ByteBuffer.wrap(disk, offset, disk.size - offset)).toString()
        val normalized = original.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized == content) return disk

        val endings = Regex("\r\n|\r|\n").findAll(original).map { it.value }.toList()
        if (endings.distinct().size <= 1) return withBom(content.replace("\n", separator).toByteArray(charset), bom)

        val base = EmptyProgressIndicator()
        val check = checkCanceled
        val indicator = object : ProgressIndicator by base {
            override fun checkCanceled() { check(); base.checkCanceled() }
        }
        val fragments = try {
            ComparisonManager.getInstance().compareLines(normalized, content, ComparisonPolicy.DEFAULT, indicator)
        } catch (_: DiffTooBigException) {
            // A guessed mapping would silently show the wrong commit; omit this snapshot instead.
            throw IOException("Blame snapshot diff exceeds platform limits")
        }
        val lines = content.split('\n')
        val sourceLine = IntArray(lines.size) { -1 }
        var oldLine = 0
        var newLine = 0
        for (fragment in fragments) {
            while (newLine < fragment.startLine2) sourceLine[newLine++] = oldLine++
            oldLine = fragment.endLine1
            newLine = fragment.endLine2
        }
        while (newLine < lines.size) sourceLine[newLine++] = oldLine++
        val text = buildString(content.length + endings.size) {
            for ((index, line) in lines.withIndex()) {
                if (index % 256 == 0) checkCanceled()
                append(line)
                if (index < lines.lastIndex) append(endings.getOrNull(sourceLine[index]) ?: separator)
            }
        }
        checkCanceled()
        return withBom(text.toByteArray(charset), bom)
    }

    private fun withBom(bytes: ByteArray, bom: ByteArray?): ByteArray =
        if (bom != null && !bytes.take(bom.size).toByteArray().contentEquals(bom)) bom + bytes else bytes
}
