package com.freelancehub.app.data

/** Source-level invariants for v46.001-v46.400. No live infrastructure is implied. */
object ProductionTrustResilienceRules {
    private val safeRequestId = Regex("^[A-Za-z0-9._:-]{8,128}$")
    private val hash = Regex("^[0-9a-fA-F]{64}$")
    private val retryable = setOf("TIMEOUT", "TEMPORARY_UNAVAILABLE", "RATE_LIMITED", "NETWORK_UNAVAILABLE")
    private val terminal = setOf("SUCCESS", "VALIDATION_ERROR", "AUTH_ERROR", "FORBIDDEN", "NOT_FOUND", "CONFLICT")

    fun isSafeRequestId(value: String): Boolean = safeRequestId.matches(value)
    fun isSha256(value: String): Boolean = hash.matches(value)
    fun isRetryable(errorCode: String): Boolean = errorCode in retryable
    fun isTerminal(errorCode: String): Boolean = errorCode in terminal

    /** Retries are bounded and never permitted to mutate financial state without idempotency. */
    fun canRetry(attempt: Int, maxAttempts: Int, idempotencyKeyPresent: Boolean): Boolean =
        attempt >= 0 && maxAttempts in 1..5 && attempt < maxAttempts && idempotencyKeyPresent

    /** Offline mode may read cached-safe data, but cannot authorize financial/security mutations. */
    fun allowsOffline(operation: String): Boolean = operation in setOf("READ_PROFILE", "READ_JOBS", "READ_MESSAGES", "READ_NOTIFICATIONS")

    /** AI output never substitutes for a human/server-authorized workflow decision. */
    fun aiMayAuthorize(operation: String): Boolean = false
}
