package com.freelancehub.app.security

/** Pure client-side guard for account-deletion request lifecycle.
 * Production deletion authorization, holds, retention and execution remain server-controlled.
 */
object AccountDeletionRules {
    enum class Status { REQUESTED, PENDING_REVIEW, SCHEDULED, CANCELLED, COMPLETED, BLOCKED }

    fun canRequest(accountActive: Boolean, hasOpenRequest: Boolean): Boolean =
        accountActive && !hasOpenRequest

    fun canCancel(status: Status): Boolean =
        status == Status.REQUESTED || status == Status.PENDING_REVIEW || status == Status.SCHEDULED

    fun canTransition(from: Status, to: Status): Boolean = when (from) {
        Status.REQUESTED -> to == Status.PENDING_REVIEW || to == Status.CANCELLED || to == Status.BLOCKED
        Status.PENDING_REVIEW -> to == Status.SCHEDULED || to == Status.CANCELLED || to == Status.BLOCKED
        Status.SCHEDULED -> to == Status.COMPLETED || to == Status.CANCELLED || to == Status.BLOCKED
        Status.CANCELLED, Status.COMPLETED, Status.BLOCKED -> false
    }

    fun hasValidOwner(actorEmail: String, accountEmail: String): Boolean =
        actorEmail.isNotBlank() && accountEmail.isNotBlank() &&
            actorEmail.trim().equals(accountEmail.trim(), ignoreCase = true)

    fun canExecuteScheduled(status: Status, scheduledForEpochMs: Long?, nowEpochMs: Long, hasActiveHold: Boolean): Boolean =
        status == Status.SCHEDULED && scheduledForEpochMs != null &&
            scheduledForEpochMs <= nowEpochMs && !hasActiveHold
}
