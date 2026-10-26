package com.github.ten951.gitplus.blame

import java.nio.file.Path

data class RepositoryPaths(val root: Path, val gitDir: Path, val commonDir: Path)
data class ChangedPath(val path: Path, val directory: Boolean = false)

object BlameRefreshScope {
    fun affects(file: Path, repository: RepositoryPaths?, changes: List<ChangedPath>): Boolean = changes.any { change ->
        val path = change.path.normalize()
        if (path == file.normalize() || change.directory && file.normalize().startsWith(path)) true
        else if (repository == null) false
        else {
            path == repository.root.resolve(".git") || path == repository.root.resolve(".mailmap") ||
                path.fileName?.toString() == ".gitattributes" && file.startsWith(path.parent) ||
                metadata(path, repository.gitDir, true) || metadata(path, repository.commonDir, repository.commonDir == repository.gitDir)
        }
    }

    private fun metadata(path: Path, directory: Path, worktree: Boolean): Boolean {
        if (path == directory) return true
        if (!path.startsWith(directory)) return false
        val relative = directory.relativize(path)
        val first = relative.getName(0).toString()
        return first in setOf("refs", "reftable", "packed-refs", "config", "shallow", "info") ||
            worktree && first in setOf("HEAD", "index", "config.worktree", "commondir", "MERGE_HEAD", "ORIG_HEAD")
    }
}
