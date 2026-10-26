package com.github.ten951.gitplus.blame

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BlameCommit(val revision: String, val author: String, val committedAt: Instant, val summary: String) {
    fun presentation(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        "${author.singleLine()} · ${formatCommitTime(committedAt, now, zone)} · ${summary.singleLine()}"
}

data class BlameLine(val commit: BlameCommit? = null, val uncommitted: Boolean = false, val pathAtCommit: String? = null) {
    fun presentation(): String? = if (uncommitted) "尚未提交" else commit?.presentation()
}

private val absoluteTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun formatCommitTime(committedAt: Instant, now: Instant, zone: ZoneId): String {
    val age = Duration.between(committedAt, now)
    if (age.isNegative || age > Duration.ofHours(72)) return absoluteTime.format(committedAt.atZone(zone))
    return when {
        age.seconds < 60 -> "刚刚"
        age.seconds < 3600 -> "${age.toMinutes()}分钟前"
        age.seconds < 86400 -> "${age.toHours()}小时前"
        else -> "${age.toDays()}天前"
    }
}

private fun String.singleLine(): String = lineSequence().firstOrNull().orEmpty()
    .map { if (it.isISOControl()) ' ' else it }.joinToString("")
