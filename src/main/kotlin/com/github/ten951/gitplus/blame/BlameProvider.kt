package com.github.ten951.gitplus.blame

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Project-shared bounded snapshots; no editor ownership or UI operations. */
class BlameProvider(private val executable: String = "git", private val onFailure: (String) -> Unit = {}) {
    private data class CacheKey(val file: Path, val head: String, val metadata: String, val disk: String,
                                val content: String, val charset: Charset, val separator: String, val bom: List<Byte>, val generation: Long)
    private val runner = GitCommandRunner(executable, onFailure)
    private val repositories = RepositoryStateCache(runner)
    private val generations = ConcurrentHashMap<Path, AtomicLong>()
    private val cache = LinkedHashMap<CacheKey, Map<Int, BlameLine>>(16, .75f, true)

    fun generationOf(file: Path): Long = generations.computeIfAbsent(file.toAbsolutePath().normalize()) { AtomicLong() }.get()
    fun repositoryPaths(file: Path): RepositoryPaths? = repositories.knownPaths(file)
    fun invalidateFile(file: Path) = synchronized(cache) {
        val path = file.toAbsolutePath().normalize()
        generations.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
        cache.keys.removeIf { it.file == path }
    }
    fun clearCache() = synchronized(cache) {
        generations.values.forEach { it.incrementAndGet() }
        cache.clear()
        repositories.clear()
    }

    fun query(file: Path, content: String, charset: Charset = Charsets.UTF_8, lineSeparator: String = "\n",
              bom: ByteArray? = null, checkCanceled: () -> Unit = {}): Map<Int, BlameLine> {
        if (content.isEmpty() || content.length > 1_000_000 || '\u0000' in content) return emptyMap()
        checkCanceled()
        return try {
            val path = file.toAbsolutePath().normalize()
            val directory = path.parent ?: return emptyMap()
            val generation = generationOf(path)
            val state = repositories.state(directory, checkCanceled) ?: return emptyMap()
            val key = CacheKey(path, state.head, state.fingerprint, fileSignature(path), content, charset, lineSeparator, bom?.toList().orEmpty(), generation)
            synchronized(cache) { cache[key] }?.let { return it }
            val tracked = runner.run(directory, listOf("--literal-pathspecs", "ls-files", "--error-unmatch", "--", path.fileName.toString()), checkCanceled = checkCanceled)
                ?: return emptyMap()
            if (tracked.isEmpty()) return emptyMap()
            val encoded = content.replace("\n", lineSeparator).toByteArray(charset)
            val bytes = if (bom != null && !encoded.take(bom.size).toByteArray().contentEquals(bom)) bom + encoded else encoded
            val output = runner.run(directory, listOf("-c", "blame.blankBoundary=false", "-c", "blame.ignoreRevsFile=",
                "-c", "i18n.logOutputEncoding=UTF-8", "blame", "--line-porcelain", "--encoding=UTF-8",
                "--contents", "-", "--", path.fileName.toString()), bytes, checkCanceled) ?: return emptyMap()
            val result = parseBlame(output)
            checkCanceled()
            if (!repositories.isCurrent(state) || fileSignature(path) != key.disk) return emptyMap()
            synchronized(cache) {
                if (generationOf(path) != generation) throw CancellationException("Blame snapshot invalidated")
                if (result.size <= 100_000) {
                    cache.keys.removeIf { it.file == path }
                    cache[key] = result
                    while (cache.size > 16 || cache.values.sumOf { it.size } > 100_000) cache.remove(cache.keys.first())
                }
            }
            result
        } catch (exception: IOException) {
            onFailure(exception.message.orEmpty())
            emptyMap()
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
