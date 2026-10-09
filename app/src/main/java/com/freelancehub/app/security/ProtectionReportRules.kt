package com.freelancehub.app.security

/** Validation for user-submitted marketplace protection reports. */
object ProtectionReportRules {
    enum class Reason { BYPASS_PAYMENT, EXTERNAL_CONTACT, SCAM_OR_FRAUD, HARASSMENT, OTHER }
    const val MAX_DETAILS_LENGTH = 2000

    fun validReason(reason: String): Boolean =
        Reason.entries.any { it.name == reason.trim().uppercase() }

    fun validTargetId(targetId: String): Boolean =
        targetId.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validDetails(details: String): Boolean =
        details.trim().length in 1..MAX_DETAILS_LENGTH

    fun canSubmit(reason: String, targetId: String, details: String): Boolean =
        validReason(reason) && validTargetId(targetId) && validDetails(details)
}
