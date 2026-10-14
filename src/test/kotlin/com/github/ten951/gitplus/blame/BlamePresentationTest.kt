package com.github.ten951.gitplus.blame

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BlamePresentationTest {
    private val now = Instant.parse("2026-10-14T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun relativeTimeUntilAndIncluding72Hours() {
        val cases = listOf(0L to "刚刚", 59L to "刚刚", 60L to "1分钟前",
            3600L to "1小时前", 86400L to "1天前", 172800L to "2天前", 259200L to "3天前")
        for ((seconds, expected) in cases) {
            assertEquals(expected, formatCommitTime(now.minusSeconds(seconds), now, zone))
        }
    }

    @Test fun olderCommitUsesFullLocalDateTime() {
        assertEquals("2026-10-11 11:59:59", formatCommitTime(now.minusSeconds(259201), now, zone))
        assertEquals("2026-10-11 03:59:59", formatCommitTime(now.minusSeconds(259201), now, ZoneId.of("UTC")))
    }

    @Test fun futureCommitUsesAbsoluteDate() {
        assertEquals("2026-10-14 12:00:01", formatCommitTime(now.plusSeconds(1), now, zone))
    }

    @Test fun hintUsesAuthorTimeAndOnlyFirstMessageLine() {
        val commit = BlameCommit("abc", "yangxingnong", now.minusSeconds(172800), "fix: 修复查询逻辑\n\n详情")
        assertEquals("yangxingnong · 2天前 · fix: 修复查询逻辑", commit.presentation(now, zone))
    }
}
