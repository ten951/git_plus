package com.github.ten951.gitplus.blame

import java.time.Instant

internal fun parseBlame(output: String, checkCanceled: () -> Unit = {}): Map<Int, BlameLine> {
    val result = mutableMapOf<Int, BlameLine>()
    val byRevision = mutableMapOf<String, BlameLine>()
    val uncommitted = BlameLine(uncommitted = true)
    var revision = ""
    var lineNumber = -1
    var author = ""
    var time: Instant? = null
    var summary = ""
    checkCanceled()
    for ((index, line) in output.lineSequence().withIndex()) {
        if (index % 256 == 0) checkCanceled()
        when {
            line.startsWith('\t') -> {
                if (lineNumber >= 0) {
                    if (revision.all { it == '0' }) result[lineNumber] = uncommitted
                    else {
                        val attribution = byRevision[revision] ?: time?.let {
                            BlameLine(BlameCommit(revision, author, it, summary)).also { value -> byRevision[revision] = value }
                        }
                        if (attribution != null) result[lineNumber] = attribution
                    }
                }
                lineNumber = -1
            }
            line.startsWith("author ") -> if (revision !in byRevision) author = line.removePrefix("author ")
            line.startsWith("committer-time ") -> if (revision !in byRevision) time = line.removePrefix("committer-time ").toLongOrNull()?.let(Instant::ofEpochSecond)
            line.startsWith("summary ") -> if (revision !in byRevision) summary = line.removePrefix("summary ")
            else -> {
                val space = line.indexOf(' ')
                if (space == 40 || space == 64) {
                    val parts = line.split(' ', limit = 4)
                    if (parts.size >= 3 && parts[0].all { it in "0123456789abcdef" }) {
                        revision = parts[0]
                        lineNumber = (parts[2].toIntOrNull() ?: 0) - 1
                        author = ""; time = null; summary = ""
                    }
                }
            }
        }
    }
    checkCanceled()
    return result
}
