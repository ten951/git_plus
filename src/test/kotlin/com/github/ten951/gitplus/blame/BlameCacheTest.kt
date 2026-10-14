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

    private fun repository(): Path {
        val root = temp.newFolder().toPath()
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            assertEquals(output, 0, p.waitFor())
        }
        git("init", "-q")
        Files.writeString(root.resolve("code.txt"), "code\n")
        git("add", ".")
        git("-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
        return root
    }
}
