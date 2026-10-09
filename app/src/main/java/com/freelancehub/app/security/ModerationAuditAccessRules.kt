package com.freelancehub.app.security

/** Server-side policy contract for reading moderation audit history. */
object ModerationAuditAccessRules {
    enum class ReaderRole { ADMIN, COMPLIANCE_REVIEWER, MODERATOR }

    fun canRead(role: String): Boolean =
        ReaderRole.entries.any { it.name == role.trim().uppercase() }

    fun canReadTarget(role: String, requesterId: String, targetId: String?): Boolean {
        if (!canRead(role) || requesterId.trim().length !in 8..128) return false
        if (targetId == null) return true
        return targetId.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))
    }

    fun maxPageSize(requested: Int): Int = requested.coerceIn(1, ModerationAuditRules.MAX_PAGE_SIZE)
}
