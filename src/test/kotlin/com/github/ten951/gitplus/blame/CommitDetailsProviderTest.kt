package com.github.ten951.gitplus.blame

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CommitDetailsProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun git(root: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(output, 0, p.waitFor())
        return output.trimEnd()
    }
    private fun repository(): RepositoryPaths {
        val root = temp.newFolder().toPath().toRealPath()
        git(root, "init", "-q")
        Files.writeString(root.resolve("code.txt"), "code\n")
        git(root, "add", ".")
        git(root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-qm",
            "完整摘要\n\n详细说明 <html> & 中文\n第二段正文")
        return RepositoryPaths(root, root.resolve(".git"), root.resolve(".git"))
    }

    @Test fun fullMessageIsLoadedOnceAndInvalidationRefreshesIt() = runBlocking {
        val repo = repository()
        val calls = repo.root.resolve("calls")
        val wrapper = repo.root.resolve("git-counter")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '.\\n' >> '$calls'\nexec git \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = CommitDetailsProvider(scope, wrapper.toString())
            val sha = git(repo.root, "rev-parse", "HEAD")
            val expected = "完整摘要\n\n详细说明 <html> & 中文\n第二段正文\n"
            repeat(10) { assertEquals(expected, provider.message(repo, sha)) }
            assertEquals(1, Files.readAllLines(calls).size)
            assertNull(provider.message(repo, "--output=/tmp/invalid"))
            assertEquals(1, Files.readAllLines(calls).size)
            provider.invalidate()
            assertEquals(expected, provider.message(repo, sha))
            assertEquals(2, Files.readAllLines(calls).size)
        } finally { scope.cancel() }
    }

    @Test fun simultaneousConsumersShareOneProcessAndOneCanCancel() = runBlocking {
        val repo = repository()
        val entered = repo.root.resolve("entered")
        val release = repo.root.resolve("release")
        val calls = repo.root.resolve("calls")
        val wrapper = repo.root.resolve("git-paused")
        Files.writeString(wrapper, """
            #!/bin/sh
            printf '.\n' >> '$calls'
            touch '$entered'
            while [ ! -e '$release' ]; do sleep 0.02; done
            exec git "${'$'}@"
        """.trimIndent() + "\n")
        wrapper.toFile().setExecutable(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = CommitDetailsProvider(scope, wrapper.toString())
            val sha = git(repo.root, "rev-parse", "HEAD")
            val first = async(start = CoroutineStart.UNDISPATCHED) { provider.message(repo, sha) }
            withTimeout(5_000) { while (!Files.exists(entered)) delay(10) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { provider.message(repo, sha) }
            first.cancelAndJoin()
            Files.writeString(release, "go")
            assertTrue(withTimeout(5_000) { second.await() }!!.contains("第二段正文"))
            assertEquals(1, Files.readAllLines(calls).size)
        } finally { Files.writeString(release, "go"); scope.cancel() }
    }

    @Test fun invalidatingOneRepositoryDoesNotEvictAnother() = runBlocking {
        val first = repository()
        val second = repository()
        val calls = first.root.resolve("calls")
        val wrapper = first.root.resolve("git-counter")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '.\\n' >> '$calls'\nexec git \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = CommitDetailsProvider(scope, wrapper.toString())
            val one = git(first.root, "rev-parse", "HEAD")
            val two = git(second.root, "rev-parse", "HEAD")
            assertNotNull(provider.message(first, one))
            assertNotNull(provider.message(second, two))
            provider.invalidate(first)
            assertNotNull(provider.message(second, two))
            assertEquals(2, Files.readAllLines(calls).size)
            assertNotNull(provider.message(first, one))
            assertEquals(3, Files.readAllLines(calls).size)
        } finally { scope.cancel() }
    }
}
