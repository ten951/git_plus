package com.github.ten951.gitplus.blame

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Queries the editor snapshot rather than the on-disk file. All commands are read-only. */
class BlameProvider(private val executable: String = "git", private val onFailure: (String) -> Unit = {}) {
    private data class CacheKey(val file: Path, val head: String, val index: String, val content: String,
                                val charset: Charset, val separator: String, val bom: List<Byte>)
    private val cache = object : LinkedHashMap<CacheKey, Map<Int, BlameLine>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Map<Int, BlameLine>>): Boolean = size > 4
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    fun query(file: Path, content: String, charset: Charset = Charsets.UTF_8, lineSeparator: String = "\n",
              bom: ByteArray? = null, checkCanceled: () -> Unit = {}): Map<Int, BlameLine> {
        if (content.isEmpty() || content.length > 1_000_000 || '\u0000' in content) return emptyMap()
        checkCanceled()
        return try {
            val directory = file.toAbsolutePath().parent ?: return emptyMap()
            val head = run(directory, listOf("rev-parse", "--verify", "HEAD"), checkCanceled = checkCanceled)?.trim() ?: return emptyMap()
            val indexPath = run(directory, listOf("rev-parse", "--path-format=absolute", "--git-path", "index"), checkCanceled = checkCanceled)
                ?.trim()?.let(Path::of) ?: return emptyMap()
            val index = if (Files.exists(indexPath)) "${Files.getLastModifiedTime(indexPath)}:${Files.size(indexPath)}" else ""
            val key = CacheKey(file.toAbsolutePath(), head, index, content, charset, lineSeparator, bom?.toList().orEmpty())
            synchronized(cache) { cache[key] }?.let { return it }
            // Literal pathspec prevents brackets and other special filename characters being interpreted by Git.
            val tracked = run(directory, listOf("--literal-pathspecs", "ls-files", "--error-unmatch", "--", file.fileName.toString()), checkCanceled = checkCanceled)
                ?: return emptyMap()
            if (tracked.isEmpty()) return emptyMap()
            val encoded = content.replace("\n", lineSeparator).toByteArray(charset)
            val bytes = if (bom != null && !encoded.take(bom.size).toByteArray().contentEquals(bom)) bom + encoded else encoded
            val output = run(directory, listOf("-c", "blame.blankBoundary=false", "-c", "blame.ignoreRevsFile=",
                "-c", "i18n.logOutputEncoding=UTF-8", "blame", "--line-porcelain", "--encoding=UTF-8",
                "--contents", "-", "--", file.fileName.toString()), bytes, checkCanceled) ?: return emptyMap()
            val result = parseBlame(output)
            checkCanceled()
            synchronized(cache) { cache[key] = result }
            result
        } catch (exception: IOException) {
            onFailure(exception.message.orEmpty())
            emptyMap()
        }
    }

    private fun run(directory: Path, args: List<String>, input: ByteArray? = null, checkCanceled: () -> Unit): String? {
        val output = Files.createTempFile("git-plus-output-", ".tmp")
        val error = Files.createTempFile("git-plus-error-", ".tmp")
        val stdin = Files.createTempFile("git-plus-input-", ".tmp")
        var process: Process? = null
        try {
            if (input != null) Files.write(stdin, input)
            val builder = ProcessBuilder(listOf(executable, "-C", directory.toString()) + args)
                .redirectInput(stdin.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile())
            // Never inherit another repository's Git context or prompt for interactive input.
            builder.environment().keys.removeIf { it.startsWith("GIT_") }
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            process = builder.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                checkCanceled()
                if (System.nanoTime() >= deadline || Files.size(output) > 32_000_000) return null
            }
            checkCanceled()
            if (process.exitValue() != 0 || Files.size(output) > 32_000_000) {
                onFailure("Git query failed with exit code ${process.exitValue()}")
                return null
            }
            // Source lines may use the file charset; metadata is UTF-8. Source text is not used by the parser.
            return String(Files.readAllBytes(output), Charsets.UTF_8)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            if (process?.isAlive == true) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(stdin)
            Files.deleteIfExists(output)
            Files.deleteIfExists(error)
        }
    }
}

internal fun parseBlame(output: String): Map<Int, BlameLine> {
    val result = mutableMapOf<Int, BlameLine>()
    var revision = ""
    var lineNumber = -1
    var author = ""
    var time: Instant? = null
    var summary = ""
    for (line in output.lineSequence()) {
        when {
            line.startsWith('\t') -> {
                if (lineNumber >= 0) {
                    if (revision.all { it == '0' }) result[lineNumber] = BlameLine(uncommitted = true)
                    else if (time != null) result[lineNumber] = BlameLine(BlameCommit(revision, author, time, summary))
                }
                lineNumber = -1
            }
            line.startsWith("author ") -> author = line.removePrefix("author ")
            line.startsWith("committer-time ") -> time = line.removePrefix("committer-time ").toLongOrNull()?.let(Instant::ofEpochSecond)
            line.startsWith("summary ") -> summary = line.removePrefix("summary ")
            else -> {
                val parts = line.split(' ')
                if (parts.size >= 3 && parts[0].length in listOf(40, 64) && parts[0].all { it in "0123456789abcdef" }) {
                    revision = parts[0]
                    lineNumber = (parts[2].toIntOrNull() ?: 0) - 1
                    author = ""
                    time = null
                    summary = ""
                }
            }
        }
    }
    return result
}
