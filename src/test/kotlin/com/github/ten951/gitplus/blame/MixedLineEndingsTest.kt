package com.github.ten951.gitplus.blame

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

class MixedLineEndingsTest : BasePlatformTestCase() {
    private fun checkSnapshot(disk: String, document: String, committedLines: Set<Int>,
                              charset: Charset = Charsets.UTF_8, bom: ByteArray? = null) {
        val root = Files.createTempDirectory("git-plus-mixed")
        try {
            fun git(vararg args: String) {
                val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
            }
            git("init", "-q")
            val file = root.resolve("mixed.txt")
            val bytes = (bom ?: byteArrayOf()) + disk.toByteArray(charset)
            Files.write(file, bytes)
            git("-c", "core.autocrlf=false", "add", ".")
            git("-c", "user.name=author", "-c", "user.email=test@example.invalid", "commit", "-qm", "mixed commit")
            val result = BlameProvider().query(file, document, charset, "\r\n", bom)
            val lineCount = document.count { it == '\n' } + if (document.endsWith('\n')) 0 else 1
            assertEquals(lineCount, result.size)
            repeat(lineCount) { line ->
                assertEquals("line $line", line !in committedLines, result[line]!!.uncommitted)
                if (line in committedLines) assertEquals("mixed commit", result[line]!!.commit?.summary)
            }
            assertTrue("Source file must remain unchanged", bytes.contentEquals(Files.readAllBytes(file)))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    fun testUnchangedMixedSeparatorsKeepEveryCommittedLine() {
        checkSnapshot("first\r\nsecond\nthird\r\nfourth", "first\nsecond\nthird\nfourth", setOf(0, 1, 2, 3))
    }

    fun testUnsavedInsertionDeletionAndReplacementKeepUnchangedSeparators() {
        val disk = "first\r\nsecond\nthird\r\nfourth\n"
        checkSnapshot(disk, "new\nfirst\nsecond\nthird\nfourth\n", setOf(1, 2, 3, 4))
        checkSnapshot(disk, "second\nchanged\nfourth\n", setOf(0, 2))
    }

    fun testDuplicateLinesBomAndNonUtf8Encoding() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        checkSnapshot("first\r\nsame\nsame\r\nlast", "first\nsame\nsame\nlast", setOf(0, 1, 2, 3), bom = bom)
        checkSnapshot("中文\r\n第二行\n末尾", "中文\n新增\n第二行\n末尾", setOf(0, 2, 3), Charset.forName("GBK"))
    }
}
