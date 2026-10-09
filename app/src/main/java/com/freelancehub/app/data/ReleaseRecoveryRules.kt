package com.freelancehub.app.data

object ReleaseRecoveryRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val evidenceHash = Regex("^[0-9a-fA-F]{32,128}$")
    private val environments = setOf("CI", "STAGING", "PRODUCTION")
    private val recoveryStatuses = setOf("PLANNED", "AUTHORIZED", "EXECUTED", "FAILED", "BLOCKED")
    private val decisions = setOf("APPROVED", "REJECTED", "DEFERRED")

    fun isSha256Valid(value: String): Boolean = sha256.matches(value)
    fun isEvidenceHashValid(value: String): Boolean = evidenceHash.matches(value)
    fun isEnvironmentValid(value: String): Boolean = value in environments
    fun isRecoveryStatusValid(value: String): Boolean = value in recoveryStatuses
    fun isDecisionValid(value: String): Boolean = value in decisions

    /** Recovery cannot be treated as authorized without an approved attestation. */
    fun requiresApprovedAttestation(status: String): Boolean =
        status == "AUTHORIZED" || status == "EXECUTED"

    fun canExecute(status: String, approvalDecision: String): Boolean =
        status == "AUTHORIZED" && approvalDecision == "APPROVED"
}
