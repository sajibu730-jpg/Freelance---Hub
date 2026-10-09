package com.freelancehub.app.security

/**
 * Deterministic lifecycle guards for platform-protection enforcement.
 * The production backend remains authoritative for identity, evidence and enforcement.
 */
object ProtectionEnforcementLifecycleRules {
    enum class Status { WARNING, REVIEW_REQUIRED, SUSPENDED, CLOSED, REINSTATED }

    fun appealAllowed(status: Status): Boolean =
        status == Status.WARNING || status == Status.REVIEW_REQUIRED || status == Status.SUSPENDED

    fun canTransition(from: Status, to: Status): Boolean = when (from) {
        Status.WARNING -> to == Status.REVIEW_REQUIRED || to == Status.REINSTATED
        Status.REVIEW_REQUIRED -> to == Status.SUSPENDED || to == Status.REINSTATED
        Status.SUSPENDED -> to == Status.CLOSED || to == Status.REINSTATED
        Status.CLOSED -> to == Status.REINSTATED
        Status.REINSTATED -> to == Status.WARNING
    }

    fun validEnforcementId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validAppealDetails(details: String): Boolean =
        details.trim().length in 1..3000

    fun canSubmitAppeal(status: Status, enforcementId: String, details: String): Boolean =
        appealAllowed(status) && validEnforcementId(enforcementId) && validAppealDetails(details)
}
