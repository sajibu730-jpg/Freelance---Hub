package com.freelancehub.app.data

object BusinessContinuityRules {
    private val priorities = setOf("STANDARD", "HIGH", "CRITICAL")
    private val statuses = setOf("DRAFT", "APPROVED", "RETIRED")
    private val testTypes = setOf("RESTORE", "FAILOVER", "ROLLBACK", "DEPENDENCY")
    private val results = setOf("PLANNED", "PASSED", "FAILED", "PARTIAL", "BLOCKED")
    private val fallbackStates = setOf("NONE", "READ_ONLY", "QUEUE", "RETRY", "MAINTENANCE")

    fun isCriticalityValid(value: String): Boolean = value in priorities
    fun isPlanStatusValid(value: String): Boolean = value in statuses
    fun isRecoveryTestTypeValid(value: String): Boolean = value in testTypes
    fun isRecoveryTestResultValid(value: String): Boolean = value in results
    fun isFallbackStateValid(value: String): Boolean = value in fallbackStates
    fun isEvidenceHashValid(value: String): Boolean = value.length in 32..128 && value.all { it in "0123456789abcdefABCDEF" }
    fun isRtoValid(minutes: Int): Boolean = minutes > 0
    fun isRpoValid(minutes: Int): Boolean = minutes >= 0
}
