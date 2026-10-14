package com.github.ten951.gitplus.blame

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BlameProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "first\nsecond\nthird\n"

    private fun repository(): Path {
        val root = temporary.newFolder("repo with spaces").toPath()
        git(root, "init", "-q")
        git(root, "config", "user.name", "测试作者")
        git(root, "config", "user.email", "test@example.invalid")
        Files.writeString(root.resolve("测试 file.txt"), original)
        git(root, "add", ".")
        git(root, "commit", "-qm", "fix: 初始提交\n\n详细描述")
        return root
    }

    @Test fun committedLineShowsRealAuthorAndSummary() {
        val root = repository()
        val result = BlameProvider().query(root.resolve("测试 file.txt"), original)
        val commit = result[1]!!.commit!!
        assertEquals("测试作者", commit.author)
        assertEquals("fix: 初始提交", commit.summary)
        assertTrue(commit.committedAt <= Instant.now())
        assertFalse(result[1]!!.uncommitted)
    }

    @Test fun unsavedInsertionRetainsOriginalLineAttribution() {
        val root = repository()
        val result = BlameProvider().query(root.resolve("测试 file.txt"), "new\nfirst\nsecond\nthird\n")
        assertTrue(result[0]!!.uncommitted)
        assertEquals("测试作者", result[2]!!.commit!!.author)
        assertEquals(original, Files.readString(root.resolve("测试 file.txt")))
    }

    @Test fun unsavedDeletionAndReplacementDoNotMisattribute() {
        val root = repository()
        val result = BlameProvider().query(root.resolve("测试 file.txt"), "second\nchanged\n")
        assertEquals("测试作者", result[0]!!.commit!!.author)
        assertTrue(result[1]!!.uncommitted)
    }

    @Test fun stagedChangesAreStillUncommitted() {
        val root = repository()
        val text = "first\nstaged\nthird\n"
        Files.writeString(root.resolve("测试 file.txt"), text)
        git(root, "add", ".")
        val result = BlameProvider().query(root.resolve("测试 file.txt"), text)
        assertTrue(result[1]!!.uncommitted)
        assertNotNull(result[2]!!.commit)
    }

    @Test fun headChangeInvalidatesCacheWithIdenticalDocument() {
        val root = repository()
        val file = root.resolve("测试 file.txt")
        val text = "first\nchanged\nthird\n"
        val provider = BlameProvider()
        assertTrue(provider.query(file, text)[1]!!.uncommitted)
        Files.writeString(file, text)
        git(root, "add", ".")
        git(root, "commit", "-qm", "second commit")
        assertEquals("second commit", provider.query(file, text)[1]!!.commit!!.summary)
    }

    @Test fun unsupportedFilesProduceNoHint() {
        val root = repository()
        val untracked = root.resolve("untracked.txt")
        Files.writeString(untracked, "new\n")
        assertTrue(BlameProvider().query(untracked, "new\n").isEmpty())
        assertTrue(BlameProvider().query(root.resolve("测试 file.txt"), "\u0000binary").isEmpty())
        assertTrue(BlameProvider().query(root.resolve("测试 file.txt"), "").isEmpty())
        val empty = temporary.newFolder("empty").toPath()
        git(empty, "init", "-q")
        val file = Files.writeString(empty.resolve("file.txt"), "new\n")
        assertTrue(BlameProvider().query(file, "new\n").isEmpty())
        assertTrue(BlameProvider("/missing/git").query(file, "new\n").isEmpty())
    }

    @Test fun cancelledQueryDoesNotPoisonLaterRequests() {
        val root = repository()
        val provider = BlameProvider()
        try {
            provider.query(root.resolve("测试 file.txt"), original, checkCanceled = {
                throw java.util.concurrent.CancellationException("request replaced")
            })
            fail("A cancelled request must not continue")
        } catch (_: java.util.concurrent.CancellationException) {
            // The same provider must remain usable for the next caret position.
        }
        assertEquals("测试作者", provider.query(root.resolve("测试 file.txt"), original)[1]!!.commit!!.author)
    }

    @Test fun nonUtf8SourceStillReturnsUtf8CommitMetadata() {
        val root = repository()
        val charset = java.nio.charset.Charset.forName("GBK")
        val text = "中文代码\nsecond\n"
        val file = root.resolve("测试 file.txt")
        Files.write(file, text.toByteArray(charset))
        git(root, "add", ".")
        git(root, "commit", "-qm", "GBK source")
        assertEquals("GBK source", BlameProvider().query(file, text, charset)[0]!!.commit!!.summary)
    }

    @Test fun normalizedEditorSnapshotPreservesCrLfAndBom() {
        val root = repository()
        git(root, "config", "core.autocrlf", "false")
        val file = root.resolve("测试 file.txt")
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        Files.write(file, bom + "first\r\nsecond\r\nthird\r\n".toByteArray())
        git(root, "add", ".")
        git(root, "commit", "-qm", "CRLF BOM commit")
        val result = BlameProvider().query(file, original, lineSeparator = "\r\n", bom = bom)
        assertEquals("CRLF BOM commit", result[0]!!.commit?.summary)
        assertEquals("CRLF BOM commit", result[1]!!.commit?.summary)
        val changed = BlameProvider().query(file, "first\nchanged\nthird\n", lineSeparator = "\r\n", bom = bom)
        assertNotNull(changed[0]!!.commit)
        assertTrue(changed[1]!!.uncommitted)
        assertNotNull(changed[2]!!.commit)
    }

    private fun git(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
