package com.freelancehub.app.data

object PrivacyGovernanceRules {
    private val versionPattern = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
    private val keyPattern = Regex("^[A-Z0-9_:-]{3,100}$")
    private val hashPattern = Regex("^[A-Fa-f0-9]{32,128}$")

    fun isPolicyVersionValid(value: String): Boolean = versionPattern.matches(value.trim())
    fun isGovernanceKeyValid(value: String): Boolean = keyPattern.matches(value.trim())
    fun isEvidenceHashValid(value: String): Boolean = hashPattern.matches(value.trim())
    fun isRetentionDaysValid(value: Int): Boolean = value >= 0
    fun isLifecycleStateValid(value: String): Boolean = value.trim() in setOf("OPEN", "IN_REVIEW", "COMPLETED", "REJECTED")
    fun isConsentStateValid(value: String): Boolean = value.trim() in setOf("GRANTED", "WITHDRAWN")
}
