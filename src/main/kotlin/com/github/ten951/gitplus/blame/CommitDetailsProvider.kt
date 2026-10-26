package com.github.ten951.gitplus.blame

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Lazily loads immutable commit messages. Author/time/SHA come from the existing blame snapshot. */
internal class CommitDetailsProvider(scope: CoroutineScope, executable: String,
                                     private val onFailure: (String) -> Unit = {}) {
    private data class Key(val repository: Path, val revision: String, val generation: Long)
    private val generations = ConcurrentHashMap<Path, AtomicLong>()
    private val runner = GitCommandRunner(executable, onFailure)
    private val cache = LinkedHashMap<Key, String>(32, .75f, true)
    private val queries = SharedQueryCoordinator<Key, String?>(scope) { key ->
        val job = currentCoroutineContext()[Job]!!
        withContext(Dispatchers.IO) { load(key) { job.ensureActive() } }
    }

    suspend fun message(repository: RepositoryPaths, revision: String): String? {
        if (revision.length !in listOf(40, 64) || revision.any { it !in "0123456789abcdef" }) return null
        val directory = repository.commonDir.toAbsolutePath().normalize()
        return queries.query(Key(directory, revision, generationOf(directory)))
    }

    private fun generationOf(repository: Path) = generations.computeIfAbsent(repository) { AtomicLong() }.get()

    fun invalidate(repository: RepositoryPaths? = null) = synchronized(cache) {
        if (repository == null) {
            generations.values.forEach { it.incrementAndGet() }
            cache.clear()
        } else {
            val directory = repository.commonDir.toAbsolutePath().normalize()
            generations.computeIfAbsent(directory) { AtomicLong() }.incrementAndGet()
            cache.keys.removeIf { it.repository == directory }
        }
    }

    private fun load(key: Key, check: () -> Unit): String? = try {
        check()
        synchronized(cache) { cache[key] } ?: run {
            val output = runner.run(key.repository, listOf("--no-pager", "show", "--no-patch", "--no-show-signature",
                "--no-notes", "--encoding=UTF-8", "--format=%H%x00%B", key.revision, "--"), checkCanceled = check)
            if (output == null || !output.startsWith(key.revision + '\u0000')) null else {
                val message = output.substring(key.revision.length + 1).removeSuffix("\n")
                if (message.length > 128_000) {
                    onFailure("Commit message exceeds hover limit (128000 characters)")
                    null
                } else {
                    check()
                    synchronized(cache) {
                        if (generationOf(key.repository) != key.generation) throw CancellationException("Commit metadata invalidated")
                        cache[key] = message
                        while (cache.size > 128 || cache.values.sumOf { it.length } > 1_000_000) cache.remove(cache.keys.first())
                    }
                    message
                }
            }
        }
    } catch (failure: IOException) { onFailure(failure.message.orEmpty()); null }
}
