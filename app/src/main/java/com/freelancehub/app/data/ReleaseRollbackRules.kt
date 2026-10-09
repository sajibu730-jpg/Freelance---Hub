package com.freelancehub.app.data

object ReleaseRollbackRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val evidenceHash = Regex("^[0-9a-fA-F]{32,128}$")
    private val statuses = setOf("PLANNED", "READY", "EXECUTED", "FAILED", "BLOCKED")
    private val decisions = setOf("APPROVED", "REJECTED", "DEFERRED")

    fun isArtifactSha256Valid(value: String) = sha256.matches(value)
    fun isMigrationManifestSha256Valid(value: String) = sha256.matches(value)
    fun isEvidenceHashValid(value: String) = evidenceHash.matches(value)
    fun isStatusValid(value: String) = value in statuses
    fun isDecisionValid(value: String) = value in decisions
    fun requiresApproval(status: String) = status == "READY" || status == "EXECUTED"
}
