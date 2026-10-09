package com.freelancehub.app.security

/** Deterministic validation and lifecycle guards for protection appeals. */
object ProtectionAppealRules {
    enum class Status { SUBMITTED, UNDER_REVIEW, ACCEPTED, REJECTED, WITHDRAWN }
    const val MAX_DETAILS_LENGTH = 3000
    const val MAX_EVIDENCE_ITEMS = 10

    fun validDetails(details: String): Boolean = details.trim().length in 1..MAX_DETAILS_LENGTH

    fun validEvidenceId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun evidenceCountValid(count: Int): Boolean = count in 0..MAX_EVIDENCE_ITEMS

    fun activeDuplicateBlocked(existing: Status?): Boolean =
        existing == Status.SUBMITTED || existing == Status.UNDER_REVIEW

    fun canTransition(from: Status, to: Status): Boolean = when (from) {
        Status.SUBMITTED -> to == Status.UNDER_REVIEW || to == Status.WITHDRAWN
        Status.UNDER_REVIEW -> to == Status.ACCEPTED || to == Status.REJECTED
        Status.ACCEPTED, Status.REJECTED, Status.WITHDRAWN -> false
    }

    fun canSubmit(details: String, evidenceCount: Int, existing: Status?): Boolean =
        validDetails(details) && evidenceCountValid(evidenceCount) && !activeDuplicateBlocked(existing)
}
