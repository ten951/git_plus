package com.github.ten951.gitplus.blame

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal fun fileSignature(path: Path): String = if (!Files.exists(path)) "missing" else {
    val a = Files.readAttributes(path, BasicFileAttributes::class.java)
    "${a.lastModifiedTime()}:${a.size()}:${a.fileKey()}"
}

// Directory mtimes change for unrelated Git bookkeeping; only identity matters for locating .git.
private fun gitMarkerSignature(path: Path): String {
    if (!Files.exists(path)) return "missing"
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    return if (attributes.isDirectory) "directory:${attributes.fileKey()}:${attributes.creationTime()}" else fileSignature(path)
}

internal fun attributesSignature(file: Path, repository: RepositoryPaths): String =
    generateSequence(file.toAbsolutePath().normalize().parent.toRealPath()) { if (it == repository.root) null else it.parent }
        .takeWhile { it.startsWith(repository.root) }
        .joinToString("|") { directory ->
            val path = directory.resolve(".gitattributes")
            "$path:${fileSignature(path)}"
        }

/** Filesystem freshness checks are cheap; Git resolves HEAD only when metadata changes or the TTL expires. */
internal class RepositoryStateCache(private val runner: GitCommandRunner) {
    data class State(val paths: RepositoryPaths, val head: String, val fingerprint: String, val checkedAt: Long)
    private data class Located(val paths: RepositoryPaths, val markers: String, val discoveredAt: Long)
    private val locations = LinkedHashMap<Path, Located>(32, .75f, true)
    private val states = LinkedHashMap<Path, State>(8, .75f, true)
    private val ttl = TimeUnit.SECONDS.toNanos(60)

    fun knownPaths(file: Path): RepositoryPaths? = synchronized(locations) { locations[file.toAbsolutePath().parent]?.paths }
    fun clear() { synchronized(locations) { locations.clear() }; synchronized(states) { states.clear() } }

    fun state(directory: Path, check: () -> Unit): State? {
        val now = System.nanoTime()
        var located = synchronized(locations) { locations[directory] }
        val markers = markers(directory)
        if (located == null || located.markers != markers || now - located.discoveredAt >= ttl) {
            fun path(option: String): Path? = runner.run(directory, listOf("rev-parse", "--path-format=absolute", option), checkCanceled = check)
                ?.removeSuffix("\n")?.let(Path::of)
            val root = path("--show-toplevel") ?: return null
            val gitDir = path("--absolute-git-dir") ?: return null
            val common = path("--git-common-dir") ?: return null
            located = Located(RepositoryPaths(root, gitDir, common), markers, now)
            synchronized(locations) { locations[directory] = located; while (locations.size > 256) locations.remove(locations.keys.first()) }
        }
        val paths = located.paths
        val fingerprint = fingerprint(paths)
        synchronized(states) { states[paths.gitDir] }?.let {
            if (it.fingerprint == fingerprint && now - it.checkedAt < ttl) return it
        }
        val head = runner.run(directory, listOf("rev-parse", "--verify", "HEAD"), checkCanceled = check)?.trim() ?: return null
        val state = State(paths, head, fingerprint, now)
        synchronized(states) { states[paths.gitDir] = state; while (states.size > 64) states.remove(states.keys.first()) }
        return state
    }

    fun isCurrent(state: State): Boolean = fingerprint(state.paths) == state.fingerprint

    private fun markers(directory: Path): String = buildString {
        var current: Path? = directory
        while (current != null) {
            val signature = gitMarkerSignature(current.resolve(".git"))
            append("$current:$signature|")
            if (signature != "missing") break
            current = current.parent
        }
    }

    private fun fingerprint(paths: RepositoryPaths): String {
        val headFile = paths.gitDir.resolve("HEAD")
        val headText = if (Files.exists(headFile)) Files.readString(headFile).trim() else ""
        val selectedRef = headText.removePrefix("ref: ")
        val files = listOf(headFile, paths.gitDir.resolve("index"), paths.gitDir.resolve("commondir"),
            paths.root.resolve(".mailmap"), paths.commonDir.resolve("info/attributes"), paths.gitDir.resolve("info/attributes"),
            paths.commonDir.resolve("packed-refs"), paths.commonDir.resolve("config"),
            paths.gitDir.resolve("config.worktree"), paths.commonDir.resolve("shallow"), paths.commonDir.resolve("reftable/tables.list")) +
            if (headText.startsWith("ref: refs/")) listOf(paths.commonDir.resolve(selectedRef)) else emptyList()
        return headText + gitMarkerSignature(paths.root.resolve(".git")) + files.joinToString("|") { "$it:${fileSignature(it)}" }
    }
}
