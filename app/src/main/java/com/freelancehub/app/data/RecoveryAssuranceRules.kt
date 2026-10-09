package com.freelancehub.app.data

object RecoveryAssuranceRules {
    private val types = setOf("DRILL", "RESTORE", "FAILOVER", "ROLLBACK", "DEPENDENCY")
    private val statuses = setOf("PLANNED", "READY", "PASSED", "FAILED", "BLOCKED")
    private val decisions = setOf("APPROVED", "REJECTED", "DEFERRED")

    fun isAssuranceTypeValid(value: String): Boolean = value in types
    fun isStatusValid(value: String): Boolean = value in statuses
    fun isSignoffDecisionValid(value: String): Boolean = value in decisions
    fun isEvidenceHashValid(value: String): Boolean =
        value.length in 32..128 && value.all { it in "0123456789abcdefABCDEF" }

    fun requiresEvidence(status: String): Boolean = status in setOf("PASSED", "FAILED", "BLOCKED")
}
