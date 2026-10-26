package com.github.ten951.gitplus.blame

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class BlameRefreshScopeTest {
    private val file = Path.of("/repo/src/code.kt")
    private val repo = RepositoryPaths(Path.of("/repo"), Path.of("/repo/.git"), Path.of("/repo/.git"))
    @Test fun unrelatedFilesAndRepositoriesAreIgnored() {
        assertFalse(BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of("/other/file")))))
        assertFalse(BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of("/repo/build/out")))))
        assertFalse(BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of("/repo/.git/objects/abc")))))
    }
    @Test fun currentFileParentsAndRepositoryStateAreRelevant() {
        for (path in listOf("/repo/src/code.kt", "/repo/.git/HEAD", "/repo/.git/index", "/repo/.git/packed-refs", "/repo/.git/refs/heads/main", "/repo/.mailmap")) {
            assertTrue(path, BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of(path)))))
        }
        assertTrue(BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of("/repo/src"), true))))
        assertFalse(BlameRefreshScope.affects(file, repo, listOf(ChangedPath(Path.of("/repo/sr"), true))))
    }
    @Test fun worktreeAndCommonDirectoryAreBothHandled() {
        val worktree = RepositoryPaths(Path.of("/worktree"), Path.of("/main/.git/worktrees/w"), Path.of("/main/.git"))
        val target = Path.of("/worktree/code.kt")
        assertTrue(BlameRefreshScope.affects(target, worktree, listOf(ChangedPath(Path.of("/main/.git/worktrees/w/HEAD")))))
        assertTrue(BlameRefreshScope.affects(target, worktree, listOf(ChangedPath(Path.of("/main/.git/refs/heads/feature")))))
        assertTrue(BlameRefreshScope.affects(target, worktree, listOf(ChangedPath(Path.of("/worktree/.git")))))
        assertFalse(BlameRefreshScope.affects(target, worktree, listOf(ChangedPath(Path.of("/main/.git/worktrees/other/index")))))
    }
}
