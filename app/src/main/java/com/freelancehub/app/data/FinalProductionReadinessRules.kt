package com.freelancehub.app.data

object FinalProductionReadinessRules {
    private val hashPattern = Regex("^[A-Fa-f0-9]{32,128}$")
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val keyPattern = Regex("^[A-Z0-9_:-]{3,100}$")

    fun isReleaseVersionValid(value: String): Boolean = versionPattern.matches(value.trim())
    fun isEvidenceHashValid(value: String): Boolean = hashPattern.matches(value.trim())
    fun isGovernanceKeyValid(value: String): Boolean = keyPattern.matches(value.trim())
    fun isApprovalRationaleValid(value: String): Boolean = value.trim().length in 8..1000
    fun isSloObservationValid(observed: Double, target: Double): Boolean =
        observed.isFinite() && target.isFinite() && observed >= 0.0 && target >= 0.0
}
