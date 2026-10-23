package com.github.ten951.gitplus.blame

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import git4idea.changes.GitChangeUtils
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
        return changes.firstOrNull { it.afterRevision?.file?.path == expected }
            ?: throw VcsException("该提交中找不到此文件的修改；历史对象可能不完整。")
    }
}
