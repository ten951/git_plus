package com.github.ten951.gitplus.blame

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private sealed interface Prepared
    private class Cached(val result: Map<Int, BlameLine>) : Prepared
    private class Raw(val key: CacheKey, val state: RepositoryStateCache.State, val output: String) : Prepared

    fun query(file: Path, content: String, charset: Charset = Charsets.UTF_8, lineSeparator: String = "\n",
              bom: ByteArray? = null, checkCanceled: () -> Unit = {}): Map<Int, BlameLine> = try {
        when (val prepared = prepare(file, content, charset, lineSeparator, bom, checkCanceled)) {
            is Cached -> prepared.result
            is Raw -> finish(prepared, parseBlame(prepared.output, checkCanceled), checkCanceled)
        }
    } catch (exception: IOException) { onFailure(exception.message.orEmpty()); emptyMap() }

    suspend fun queryAsync(request: BlameRequest, checkCanceled: () -> Unit): Map<Int, BlameLine> = try {
        val prepared = withContext(Dispatchers.IO) {
            prepare(request.file, request.content, request.charset, request.separator, request.bom.toByteArray(), checkCanceled)
        }
        when (prepared) {
            is Cached -> prepared.result
            is Raw -> {
                val parsed = withContext(Dispatchers.Default) { parseBlame(prepared.output, checkCanceled) }
                withContext(Dispatchers.IO) { finish(prepared, parsed, checkCanceled) }
            }
        }
    } catch (exception: IOException) { onFailure(exception.message.orEmpty()); emptyMap() }

    private fun prepare(file: Path, content: String, charset: Charset, lineSeparator: String,
                        bom: ByteArray?, checkCanceled: () -> Unit): Prepared {
        checkCanceled()
        if (content.isEmpty() || content.length > 1_000_000 || '\u0000' in content) return Cached(emptyMap())
        val path = file.toAbsolutePath().normalize()
        val directory = path.parent ?: return Cached(emptyMap())
        val generation = generationOf(path)
        val state = repositories.state(directory, checkCanceled) ?: return Cached(emptyMap())
        val key = CacheKey(path, state.head, state.fingerprint, fileSignature(path), content, charset, lineSeparator, bom?.toList().orEmpty(), generation)
        synchronized(cache) { cache[key] }?.let { return Cached(it) }
        val tracked = runner.run(directory, listOf("--literal-pathspecs", "ls-files", "--error-unmatch", "--", path.fileName.toString()), checkCanceled = checkCanceled)
            ?: return Cached(emptyMap())
        if (tracked.isEmpty()) return Cached(emptyMap())
        val encoded = content.replace("\n", lineSeparator).toByteArray(charset)
        val bytes = if (bom != null && !encoded.take(bom.size).toByteArray().contentEquals(bom)) bom + encoded else encoded
        val output = runner.run(directory, listOf("-c", "blame.blankBoundary=false", "-c", "blame.ignoreRevsFile=",
            "-c", "i18n.logOutputEncoding=UTF-8", "blame", "--line-porcelain", "--encoding=UTF-8",
            "--contents", "-", "--", path.fileName.toString()), bytes, checkCanceled) ?: return Cached(emptyMap())
        return Raw(key, state, output)
    }

    private fun finish(raw: Raw, result: Map<Int, BlameLine>, checkCanceled: () -> Unit): Map<Int, BlameLine> {
        checkCanceled()
        if (!repositories.isCurrent(raw.state) || fileSignature(raw.key.file) != raw.key.disk) return emptyMap()
        synchronized(cache) {
            if (generationOf(raw.key.file) != raw.key.generation) throw CancellationException("Blame snapshot invalidated")
            if (result.size <= 100_000) {
                cache.keys.removeIf { it.file == raw.key.file }
                cache[raw.key] = result
                while (cache.size > 16 || cache.values.sumOf { it.size } > 100_000) cache.remove(cache.keys.first())
            }
        }
        return result
    }
}
