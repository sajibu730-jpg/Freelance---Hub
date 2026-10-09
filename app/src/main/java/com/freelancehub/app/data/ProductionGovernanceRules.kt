package com.freelancehub.app.data

/** Client-side guards for production financial governance. Backend remains authoritative. */
object ProductionGovernanceRules {
    private val scope = Regex("^[A-Z0-9_.:-]{2,80}$")
    private val idempotencyKey = Regex("^[A-Za-z0-9._:-]{8,200}$")
    private val hash = Regex("^[A-Fa-f0-9]{32,128}$")
    private val findingCode = Regex("^[A-Z0-9_:-]{3,80}$")

    fun validControlScope(value: String): Boolean = scope.matches(value.trim())
    fun validIdempotencyKey(value: String): Boolean = idempotencyKey.matches(value.trim())
    fun validPayloadHash(value: String): Boolean = hash.matches(value.trim())
    fun validFindingCode(value: String): Boolean = findingCode.matches(value.trim())

    fun controlRunTransitionAllowed(from: String, to: String): Boolean = when (from.uppercase() to to.uppercase()) {
        "RUNNING" to "PASSED" -> true
        "RUNNING" to "FAILED" -> true
        "RUNNING" to "CANCELLED" -> true
        else -> false
    }

    fun severityRequiresReview(severity: String): Boolean = when (severity.uppercase()) {
        "HIGH", "CRITICAL" -> true
        else -> false
    }

    fun webhookSignatureOutcomeAllowed(valid: Boolean, outcome: String): Boolean = when {
        valid -> outcome.uppercase() in setOf("ACCEPTED", "DUPLICATE")
        else -> outcome.uppercase() == "INVALID_SIGNATURE"
    }

    fun outboxTransitionAllowed(from: String, to: String): Boolean = when (from.uppercase() to to.uppercase()) {
        "PENDING" to "PROCESSING", "PROCESSING" to "DELIVERED",
        "PROCESSING" to "FAILED", "FAILED" to "PROCESSING",
        "FAILED" to "DEAD_LETTER" -> true
        else -> false
    }

    fun incidentSeverityAllowed(value: String): Boolean =
        value.uppercase() in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")

    fun featureKeyAllowed(value: String): Boolean =
        Regex("^[a-z0-9_.:-]{2,100}$").matches(value.trim())
}
