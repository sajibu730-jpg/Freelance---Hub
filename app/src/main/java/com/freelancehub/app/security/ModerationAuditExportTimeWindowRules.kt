package com.freelancehub.app.security

import java.time.Duration
import java.time.Instant

/** Constrains moderation-audit exports to a bounded, explicit time window for data minimization. */
object ModerationAuditExportTimeWindowRules {
    const val MAX_WINDOW_DAYS = 31L
    const val MAX_INSTANT_LENGTH = 40

    fun validInstant(value: String): Boolean = try {
        value.trim().length <= MAX_INSTANT_LENGTH && Instant.parse(value.trim()) != null
    } catch (_: Exception) {
        false
    }

    fun validWindow(from: String, to: String): Boolean {
        if (!validInstant(from) || !validInstant(to)) return false
        val start = Instant.parse(from.trim())
        val end = Instant.parse(to.trim())
        if (!end.isAfter(start)) return false
        return Duration.between(start, end) <= Duration.ofDays(MAX_WINDOW_DAYS)
    }
}
