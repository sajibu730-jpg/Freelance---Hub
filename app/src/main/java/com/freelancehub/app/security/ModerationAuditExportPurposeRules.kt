package com.freelancehub.app.security

/** Binds moderation-audit exports to an explicit, auditable business purpose. */
object ModerationAuditExportPurposeRules {
    enum class Purpose { COMPLIANCE_REVIEW, INCIDENT_REVIEW, LEGAL_HOLD }

    fun validPurpose(purpose: String): Boolean =
        Purpose.entries.any { it.name == purpose.trim().uppercase() }

    fun allowedForRole(role: String, purpose: String): Boolean {
        val normalizedRole = role.trim().uppercase()
        val normalizedPurpose = purpose.trim().uppercase()
        if (!validPurpose(normalizedPurpose)) return false
        return when (normalizedRole) {
            "ADMIN" -> true
            "COMPLIANCE_REVIEWER" -> normalizedPurpose == "COMPLIANCE_REVIEW"
            else -> false
        }
    }

    fun validPurposeBinding(role: String, purpose: String): Boolean =
        allowedForRole(role, purpose)
}
