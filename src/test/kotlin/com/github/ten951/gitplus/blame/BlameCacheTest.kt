package com.github.ten951.gitplus.blame

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class BlameCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun warmSnapshotDoesNotStartAnyGitProcesses() {
        val root = repository()
        val counter = root.resolve("calls")
        val wrapper = root.resolve("git-counter")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '.\\n' >> '$counter'\nexec git \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val provider = BlameProvider(wrapper.toString())
        val file = root.resolve("code.txt")
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
        val before = Files.readAllLines(counter).size
        repeat(10) { assertNotNull(provider.query(file, "code\n")[0]!!.commit) }
        assertEquals("Warm lookups must not execute rev-parse", before, Files.readAllLines(counter).size)
    }

    @Test fun invalidationPreventsOldSnapshotReuse() {
        val root = repository()
        val provider = BlameProvider()
        val file = root.resolve("code.txt")
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
        val generation = provider.generationOf(file)
        provider.invalidateFile(file)
        assertTrue(provider.generationOf(file) > generation)
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
    }

    @Test fun mailmapChangeRefreshesWithoutAVfsEvent() {
        val root = repository()
        val provider = BlameProvider()
        val file = root.resolve("code.txt")
        assertEquals("test", provider.query(file, "code\n")[0]!!.commit!!.author)
        Files.writeString(root.resolve(".mailmap"), "Mapped <mapped@example.invalid> test <test@example.invalid>\n")
        assertEquals("Mapped", provider.query(file, "code\n")[0]!!.commit!!.author)
    }

    @Test fun attributesChangeCannotReuseSnapshotWithoutAVfsEvent() {
        val root = repository()
        val counter = root.resolve("calls")
        val wrapper = root.resolve("git-counter")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '.\\n' >> '$counter'\nexec git \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val provider = BlameProvider(wrapper.toString())
        val file = root.resolve("code.txt")
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
        val before = Files.readAllLines(counter).size
        Files.writeString(root.resolve(".gitattributes"), "*.txt -text\n")
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
        assertTrue("Changed attributes must trigger a fresh blame", Files.readAllLines(counter).size > before)
    }

    @Test fun gitDirectoryTimestampDoesNotRediscoverAnUnchangedRepository() {
        val root = repository()
        val counter = root.resolve("calls")
        val wrapper = root.resolve("git-counter")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '.\\n' >> '$counter'\nexec git \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val provider = BlameProvider(wrapper.toString())
        val file = root.resolve("code.txt")
        provider.query(file, "code\n")
        val before = Files.readAllLines(counter).size
        val gitDir = root.resolve(".git")
        Files.setLastModifiedTime(gitDir, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(gitDir).toMillis() + 2000))
        assertNotNull(provider.query(file, "code\n")[0]!!.commit)
        assertEquals("An unrelated .git directory write must not start Git", before, Files.readAllLines(counter).size)
    }

    @Test fun linkedWorktreesKeepSeparateHeadAndIndexState() {
        val root = repository()
        val other = temp.newFolder("linked").toPath()
        git(root, "worktree", "add", "-qb", "linked", other.toString())
        val provider = BlameProvider()
        assertEquals("base", provider.query(root.resolve("code.txt"), "code\n")[0]!!.commit!!.summary)
        Files.writeString(other.resolve("code.txt"), "changed\n")
        git(other, "add", ".")
        git(other, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-qm", "linked commit")
        assertEquals("linked commit", provider.query(other.resolve("code.txt"), "changed\n")[0]!!.commit!!.summary)
        assertEquals("base", provider.query(root.resolve("code.txt"), "code\n")[0]!!.commit!!.summary)
        git(other, "checkout", "--detach", "HEAD~1")
        assertEquals("base", provider.query(other.resolve("code.txt"), "code\n")[0]!!.commit!!.summary)
        git(root, "pack-refs", "--all")
        assertEquals("base", provider.query(root.resolve("code.txt"), "code\n")[0]!!.commit!!.summary)
    }

    @Test fun invalidationDuringGitWorkRejectsOldResultAndAllowsNextQuery() {
        val root = repository()
        val entered = root.resolve("entered")
        val release = root.resolve("release")
        val wrapper = root.resolve("git-paused")
        Files.writeString(wrapper, """
            #!/bin/sh
            case "${'$'}*" in
              *"blame --line-porcelain"*)
                touch '$entered'
                while [ ! -e '$release' ]; do sleep 0.02; done
                ;;
            esac
            exec git "${'$'}@"
        """.trimIndent() + "\n")
        wrapper.toFile().setExecutable(true)
        val provider = BlameProvider(wrapper.toString())
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Map<Int, BlameLine>> { provider.query(root.resolve("code.txt"), "code\n") }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(entered) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The real blame process must be in flight", Files.exists(entered))
            provider.invalidateFile(root.resolve("code.txt"))
            Files.writeString(release, "go")
            try {
                pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
                fail("Invalidated in-flight result must not be published")
            } catch (failure: java.util.concurrent.ExecutionException) {
                assertTrue(failure.cause is java.util.concurrent.CancellationException)
            }
            assertEquals("base", provider.query(root.resolve("code.txt"), "code\n")[0]!!.commit!!.summary)
        } finally {
            Files.writeString(release, "go")
            executor.shutdownNow()
        }
    }

    private fun git(root: Path, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(output, 0, p.waitFor())
    }

    private fun repository(): Path {
        val root = temp.newFolder().toPath()
        git(root, "init", "-q")
        Files.writeString(root.resolve("code.txt"), "code\n")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
        return root
    }
}
