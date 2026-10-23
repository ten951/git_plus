package com.github.ten951.gitplus.blame

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class CommitDiffLoaderTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var root: VirtualFile
    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "git-plus-diff-")
        Disposer.register(testRootDisposable, com.intellij.openapi.Disposable { directory.toFile().deleteRecursively() })
        git("init", "-q", "-b", "main")
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(directory.toString(), "Git"))
    }
    override fun tearDown() {
        try { ProjectLevelVcsManager.getInstance(project).directoryMappings = emptyList() }
        finally { super.tearDown() }
    }

    fun testFirstCommitShowsAddedFileWithEmptyBeforeRevision() {
        write("source.txt", "first\n")
        val revision = commit()
        val change = load(revision, "source.txt")
        assertNull(change.beforeRevision)
        assertEquals(revision, change.afterRevision!!.revisionNumber.asString())
        assertEquals(directory.resolve("source.txt").toString(), change.afterRevision!!.file.path)
    }

    fun testModificationComparesParentWithCommitRatherThanDirtyWorkingTree() {
        write("source.txt", "old\n")
        val parent = commit()
        write("source.txt", "new\n")
        val revision = commit()
        write("source.txt", "dirty working tree\n")
        val change = load(revision, "source.txt")
        assertEquals(parent, change.beforeRevision!!.revisionNumber.asString())
        assertEquals(revision, change.afterRevision!!.revisionNumber.asString())
        assertEquals("old\n", content(change.beforeRevision!!))
        assertEquals("new\n", content(change.afterRevision!!))
    }

    fun testRenamePreservesBothPathsAndHistoricalBlameCanOpenOldName() {
        write("old name.txt", "stable line\nsecond line\n")
        val original = commit()
        git("mv", "old name.txt", "中文 name.txt")
        val renamed = commit()
        val change = load(renamed, "中文 name.txt")
        assertEquals(directory.resolve("old name.txt").toString(), change.beforeRevision!!.file.path)
        assertEquals(directory.resolve("中文 name.txt").toString(), change.afterRevision!!.file.path)
        val blame = runBlocking(Dispatchers.IO) {
            BlameProvider("git").query(directory.resolve("中文 name.txt"), "stable line\nsecond line\n")
        }.getValue(0)
        assertEquals(original, blame.commit!!.revision)
        assertEquals("old name.txt", blame.pathAtCommit)
        assertNotNull(load(blame.commit!!.revision, blame.pathAtCommit!!).afterRevision)
    }

    fun testMergeUsesFirstParent() {
        write("source.txt", "base\n")
        commit()
        git("checkout", "-qb", "side")
        write("source.txt", "side\n")
        commit()
        git("checkout", "main")
        write("other.txt", "main change\n")
        val firstParent = commit()
        git("merge", "--no-ff", "side", "-m", "merge side")
        val merge = git("rev-parse", "HEAD").trim()
        val change = load(merge, "source.txt")
        assertEquals(firstParent, change.beforeRevision!!.revisionNumber.asString())
        assertEquals(merge, change.afterRevision!!.revisionNumber.asString())
        assertEquals("base\n", content(change.beforeRevision!!))
        assertEquals("side\n", content(change.afterRevision!!))
    }

    fun testUnavailableCommitAndMissingFileFailExplicitly() {
        write("source.txt", "base\n")
        val revision = commit()
        for ((sha, path) in listOf("f".repeat(40) to "source.txt", revision to "missing.txt")) {
            try { load(sha, path); fail("Unavailable diff must not silently open another file") }
            catch (_: VcsException) { }
        }
    }

    fun testInvalidRevisionAndEscapingPathAreRejected() {
        for ((sha, path) in listOf("HEAD" to "file", "a".repeat(40) to "../escape", "a".repeat(40) to "/absolute")) {
            try { load(sha, path); fail("Unsafe request accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun load(revision: String, path: String) = runBlocking {
        withContext(Dispatchers.IO) { CommitDiffLoader(project).load(CommitDiffRequest(root, revision, path)) }
    }
    private fun content(revision: com.intellij.openapi.vcs.changes.ContentRevision) = runBlocking(Dispatchers.IO) { revision.content }
    private fun write(path: String, content: String) { Files.writeString(directory.resolve(path), content) }
    private fun commit(): String {
        git("add", "--all")
        git("-c", "user.name=Diff Test", "-c", "user.email=diff@example.invalid", "commit", "-qm", "test change")
        return git("rev-parse", "HEAD").trim()
    }
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }
}
