package com.freelancehub.app.data

/** v52.401-v52.900 production continuity contracts. Server authorization remains authoritative. */
object ProductionContinuityRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION_LIMIT = 50

    fun normalHiringWindowIsValid(hours: Int): Boolean = hours == NORMAL_HIRING_WINDOW_HOURS
    fun emergencyHiringWindowIsValid(hours: Int): Boolean = hours == EMERGENCY_HIRING_WINDOW_HOURS
    fun emergencyPhaseIsValid(hours: Int, distributionLimit: Int): Boolean =
        hours == EMERGENCY_PHASE_HOURS && distributionLimit == EMERGENCY_PHASE_DISTRIBUTION_LIMIT

    fun skillMatchRequired(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        jobSkill.isNotBlank() && verifiedSkills.contains(jobSkill)

    fun freelancerEligibleForDistribution(active: Boolean, available: Boolean, skillMatch: Boolean): Boolean =
        active && available && skillMatch

    /** Client and AI cannot promote, recover, or finalize privileged marketplace state. */
    fun clientMayFinalizePrivilegedState(): Boolean = false
    fun aiMayFinalizePrivilegedState(): Boolean = false

    /** Recovery must remain explicit, auditable, and server-authorized. */
    fun destructiveRecoveryRequiresServerAuthorization(): Boolean = true
    fun covertMonitoringAllowed(): Boolean = false
}
