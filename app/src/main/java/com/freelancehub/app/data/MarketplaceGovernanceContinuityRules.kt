package com.freelancehub.app.data

/** v51.900 governance-continuity contracts; backend authorization remains authoritative. */
object MarketplaceGovernanceContinuityRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION_LIMIT = 50

    fun emergencyWindowIsTwoPhases(): Boolean =
        EMERGENCY_PHASE_HOURS * 2 == EMERGENCY_HIRING_WINDOW_HOURS

    fun eligibleForDistribution(active: Boolean, available: Boolean, skillMatches: Boolean): Boolean =
        active && available && skillMatches

    fun clientMayFinalizePrivilegedOutcome(): Boolean = false
    fun aiMayFinalizePrivilegedOutcome(): Boolean = false
    fun covertMonitoringAllowed(): Boolean = false
    fun serverMustAuthorizeEnforcement(): Boolean = true
}
