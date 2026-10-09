package com.freelancehub.app.data

/** Source-level final-readiness boundary contract for v55.900. */
object FinalReadinessRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION = 50

    fun jobMatchesVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        jobSkill.isNotBlank() && verifiedSkills.any { it.equals(jobSkill, ignoreCase = true) }

    fun emergencyPhaseIndex(elapsedHours: Int): Int? = when {
        elapsedHours in 0 until EMERGENCY_PHASE_HOURS -> 1
        elapsedHours in EMERGENCY_PHASE_HOURS until EMERGENCY_HIRING_WINDOW_HOURS -> 2
        else -> null
    }

    fun privilegedMutationRequiresServerAuthorization(): Boolean = true
    fun aiMayFinalizeFinancialOrContractualOutcome(): Boolean = false
    fun clientMayBypassServerAuthorization(): Boolean = false
    fun covertMonitoringAllowed(): Boolean = false
    fun destructiveRecoveryMayRunOffline(): Boolean = false
}
