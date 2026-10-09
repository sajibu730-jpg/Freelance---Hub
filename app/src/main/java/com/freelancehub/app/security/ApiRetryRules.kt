package com.freelancehub.app.security

/**
 * Pure retry policy for provider-neutral API transport.
 * Retries are deliberately conservative: authentication failures are handled by
 * the session layer, and non-idempotent writes require an idempotency key.
 */
object ApiRetryRules {
    private const val MAX_AUTOMATIC_RETRIES = 2

    fun shouldRetry(
        method: String,
        httpStatus: Int,
        attempt: Int,
        hasIdempotencyKey: Boolean = false
    ): Boolean {
        if (attempt >= MAX_AUTOMATIC_RETRIES) return false
        if (httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 409) return false
        if (httpStatus != 408 && httpStatus != 425 && httpStatus != 429 && httpStatus !in 500..599) return false
        return when (method.trim().uppercase()) {
            "GET", "HEAD", "OPTIONS" -> true
            "POST", "PUT", "PATCH", "DELETE" -> hasIdempotencyKey
            else -> false
        }
    }

    fun retryDelayMillis(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
        0 -> 500L
        1 -> 1_000L
        else -> 2_000L
    }
}
