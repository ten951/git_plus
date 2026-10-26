package com.github.ten951.gitplus.blame

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Bounded, read-only invocation. No shell or user Git configuration writes. */
internal class GitCommandRunner(private val executable: String, private val failure: (String) -> Unit) {
    fun run(directory: Path, args: List<String>, input: ByteArray? = null, checkCanceled: () -> Unit): String? {
        val files = mutableListOf<Path>()
        var process: Process? = null
        try {
            fun temp(kind: String) = Files.createTempFile("git-plus-$kind-", ".tmp").also(files::add)
            val output = temp("output")
            val error = temp("error")
            val stdin = temp("input")
            if (input != null) Files.write(stdin, input)
            val builder = ProcessBuilder(listOf(executable, "-C", directory.toString()) + args)
                .redirectInput(stdin.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile())
            builder.environment().keys.removeIf { it.startsWith("GIT_") }
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            checkCanceled()
            process = builder.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                checkCanceled()
                if (System.nanoTime() >= deadline || Files.size(output) > 32_000_000) {
                    failure("Git query exceeded time/output limit")
                    return null
                }
            }
            checkCanceled()
            if (process.exitValue() != 0 || Files.size(output) > 32_000_000) {
                val stderr = Files.newInputStream(error).use { String(it.readNBytes(4096), Charsets.UTF_8) }
                failure("Git query failed (${process.exitValue()}): $stderr")
                return null
            }
            return String(Files.readAllBytes(output), Charsets.UTF_8)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            if (process?.isAlive == true) {
                process.destroyForcibly()
                try { process.waitFor(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            for (file in files) try { Files.deleteIfExists(file) } catch (e: IOException) { failure("Temporary file cleanup failed: ${e.message}") }
        }
    }
}
