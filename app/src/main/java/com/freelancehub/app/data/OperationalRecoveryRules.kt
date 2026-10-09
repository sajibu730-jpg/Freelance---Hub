package com.freelancehub.app.data

/** v49.401-v49.900 operational recovery contracts. Server policy remains authoritative. */
object OperationalRecoveryRules {
    private const val MAX_AUTOMATIC_RETRIES = 3

    fun automaticRetryAllowed(attempt: Int, idempotencyKeyValid: Boolean, retryable: Boolean): Boolean =
        attempt in 1..MAX_AUTOMATIC_RETRIES && idempotencyKeyValid && retryable

    /** A degraded dependency must not turn an unavailable write path into a destructive client action. */
    fun degradedModeAllowsDestructiveWrite(isDependencyHealthy: Boolean, serverAuthorized: Boolean): Boolean =
        isDependencyHealthy && serverAuthorized

    /** Restore/replay evidence must be explicit before a recovered state is promoted. */
    fun recoveredStateMayBePromoted(evidenceVerified: Boolean, serverAuthorized: Boolean): Boolean =
        evidenceVerified && serverAuthorized

    /** Client and AI remain advisory during operational recovery. */
    fun clientMayPromoteRecovery(): Boolean = false
    fun aiMayPromoteRecovery(): Boolean = false

    /** Read-only fallback is allowed when the authoritative write path is unavailable. */
    fun readOnlyFallbackAllowed(dependencyHealthy: Boolean): Boolean = !dependencyHealthy
}
