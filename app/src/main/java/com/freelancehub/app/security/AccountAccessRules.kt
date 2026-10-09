package com.freelancehub.app.security

/**
 * Client-side gate for account lifecycle state. Production authentication must
 * enforce account_status on the server and invalidate sessions when access is revoked.
 */
object AccountAccessRules {
    enum class Status { ACTIVE, SUSPENDED, DEACTIVATED }

    fun canAuthenticate(status: Status): Boolean = status == Status.ACTIVE

    fun shouldInvalidateSession(status: Status): Boolean = status != Status.ACTIVE

    fun canResumeSuspension(status: Status, nowEpochMs: Long, suspendedUntilEpochMs: Long?): Boolean =
        status == Status.SUSPENDED && suspendedUntilEpochMs != null && suspendedUntilEpochMs <= nowEpochMs

    fun normalizeExpiredSuspension(status: Status, nowEpochMs: Long, suspendedUntilEpochMs: Long?): Status =
        if (canResumeSuspension(status, nowEpochMs, suspendedUntilEpochMs)) Status.ACTIVE else status
}
