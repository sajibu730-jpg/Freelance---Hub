package com.freelancehub.app.security

/** Provider-neutral production invariants. Server remains authoritative for money and identity. */
object ProductionIntegrityRules {
    private val idempotencyKey = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$")
    private val fingerprint = Regex("^[a-f0-9]{64}$")
    private val eventName = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

    fun validIdempotencyKey(value: String): Boolean = idempotencyKey.matches(value.trim())
    fun validRequestFingerprint(value: String): Boolean = fingerprint.matches(value.trim())
    fun validAuditEventName(value: String): Boolean = eventName.matches(value.trim())

    fun retryAllowed(httpStatus: Int): Boolean = httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599

    fun boundedPage(page: Int, size: Int): Pair<Int, Int> =
        (page.coerceIn(1, 10_000)) to (size.coerceIn(1, 100))

    fun sameIdempotentRequest(existingFingerprint: String, incomingFingerprint: String): Boolean =
        validRequestFingerprint(existingFingerprint) && validRequestFingerprint(incomingFingerprint) &&
            existingFingerprint.equals(incomingFingerprint, ignoreCase = true)
}
