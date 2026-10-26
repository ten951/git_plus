package com.github.ten951.gitplus.blame

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Files
import java.nio.file.Path

internal data class CommitDiffRequest(val root: VirtualFile, val revision: String, val pathAtCommit: String)

/** Read-only background loading. Git4Idea owns rename/root/encoding/binary content semantics. */
internal class CommitDiffLoader(private val project: Project) {
    fun load(request: CommitDiffRequest): Change {
        require(request.revision.length in listOf(40, 64) && request.revision.all { it in "0123456789abcdef" }) {
            "Commit Diff requires a complete commit SHA"
        }
        val relative = Path.of(request.pathAtCommit)
        require(!relative.isAbsolute && request.pathAtCommit.isNotEmpty() && relative.none { it.toString() == ".." }) {
            "Commit Diff requires a repository-relative file path"
        }
        val expected = Path.of(request.root.path).resolve(relative).normalize().toString().replace(java.io.File.separatorChar, '/')
        ProgressManager.checkCanceled()
        // skipDiffsForMerge=true uses the --first-parent output already requested by Git4Idea.
        // revertable=false retains the real parent revision (null only for an initial addition).
        val changes = GitChangeUtils.getRevisionChanges(project, request.root, request.revision, true, false, false).changes
        ProgressManager.checkCanceled()
        val change = changes.firstOrNull { it.afterRevision?.file?.path == expected }
            ?: throw VcsException("该提交中找不到此文件的修改；历史对象可能不完整。")
        if (change.beforeRevision == null) checkShallowBoundary(request)
        return change
    }

    private fun checkShallowBoundary(request: CommitDiffRequest) {
        // --git-path resolves the shared metadata directory for linked worktrees as well.
        val shallow = Path.of(git(request, GitCommand.REV_PARSE, "--path-format=absolute", "--git-path", "shallow").removeSuffix("\n"))
        if (!Files.isRegularFile(shallow)) return
        val boundary = Files.lines(shallow).use { lines ->
            lines.anyMatch { ProgressManager.checkCanceled(); it == request.revision }
        }
        if (!boundary) return
        // Pretty-format %P hides parents at shallow boundaries. The raw object retains them.
        val raw = git(request, GitCommand.CAT_FILE, "-p", request.revision)
        if (raw.lineSequence().takeWhile { it.isNotEmpty() }.any { it.startsWith("parent ") }) {
            throw VcsException("该提交位于浅克隆边界，无法取得真实父提交；请补全本地历史后重试。")
        }
    }

    private fun git(request: CommitDiffRequest, command: GitCommand, vararg arguments: String): String {
        val handler = GitLineHandler(project, request.root, command).apply {
            setSilent(true)
            addParameters(*arguments)
        }
        return Git.getInstance().runCommand(handler).getOutputOrThrow()
    }
}
