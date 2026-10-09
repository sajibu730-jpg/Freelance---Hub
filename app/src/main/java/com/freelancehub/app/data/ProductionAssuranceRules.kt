package com.freelancehub.app.data

/** Server-authoritative production assurance contracts; advisory clients/AI cannot finalize privileged state. */
object ProductionAssuranceRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION = 50

    fun jobMatchesVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        verifiedSkills.any { it.equals(jobSkill, ignoreCase = true) }

    fun eligibleForDistribution(active: Boolean, available: Boolean, skillMatch: Boolean): Boolean =
        active && available && skillMatch

    fun clientMayFinalizePrivilegedState(): Boolean = false
    fun aiMayFinalizePrivilegedState(): Boolean = false
    fun allowsCovertMonitoring(): Boolean = false
    fun allowsUnboundedRetry(): Boolean = false
    fun allowsClientSideFinancialMutation(): Boolean = false
}
