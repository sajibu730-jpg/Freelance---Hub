package com.freelancehub.app.security

/** Deterministic guards for read-only moderation audit retrieval. Backend remains authoritative. */
object ModerationAuditRules {
    const val MAX_PAGE_SIZE = 50
    const val MAX_CURSOR_LENGTH = 256

    fun validEventId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validTargetId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validPageSize(limit: Int): Boolean = limit in 1..MAX_PAGE_SIZE

    fun validCursor(cursor: String?): Boolean =
        cursor == null || cursor.trim().length in 1..MAX_CURSOR_LENGTH

    fun immutableEvent(existingEventId: String?, incomingEventId: String): Boolean =
        existingEventId == null || existingEventId == incomingEventId

    fun queryValid(targetId: String?, limit: Int, cursor: String?): Boolean =
        (targetId == null || validTargetId(targetId)) && validPageSize(limit) && validCursor(cursor)
}
