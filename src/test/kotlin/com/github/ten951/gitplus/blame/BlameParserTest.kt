package com.github.ten951.gitplus.blame

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class BlameParserTest {
    private fun output(lines: Int) = buildString {
        repeat(lines) { n ->
            append("${"a".repeat(40)} ${n + 1} ${n + 1}\nauthor test\ncommitter-time 1000\nsummary message\nfilename file\n\tcode\n")
        }
    }
    @Test fun allLinesForOneRevisionReuseOneCommitObject() {
        val result = parseBlame(output(1000))
        assertEquals(1000, result.size)
        assertSame(result[0]!!.commit, result[999]!!.commit)
    }
    @Test fun parsingCanBeCancelledBeforeFinishingOutput() {
        var polls = 0
        try {
            parseBlame(output(40000)) { if (++polls == 3) throw CancellationException("stop") }
            fail("Must propagate cancellation during parsing")
        } catch (_: CancellationException) { assertEquals(3, polls) }
    }

    @Test fun keepsHistoricalPathsPerLineWithoutDuplicatingCommitMetadata() {
        val raw = output(2).replaceFirst("filename file", "filename old name.kt")
            .replace("filename file", "filename \"\\344\\270\\255\\346\\226\\207.kt\"")
        val lines = parseBlame(raw)
        assertEquals("old name.kt", lines[0]!!.pathAtCommit)
        assertEquals("中文.kt", lines[1]!!.pathAtCommit)
        assertSame(lines[0]!!.commit, lines[1]!!.commit)
    }
}
