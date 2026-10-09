package com.freelancehub.app.data

/**
 * v52.901-v53.400 production lifecycle integrity contracts.
 * These are source-level invariants; server authorization remains authoritative.
 */
object ProductionLifecycleIntegrityRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_CAPACITY = 50

    fun jobMatchesVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        jobSkill.isNotBlank() && verifiedSkills.any { it.equals(jobSkill, ignoreCase = true) }

    fun canEnterDistribution(isActive: Boolean, isAvailable: Boolean, skillMatches: Boolean): Boolean =
        isActive && isAvailable && skillMatches

    fun emergencyPhase(index: Int): Int? = when (index) {
        1, 2 -> index
        else -> null
    }

    fun phaseCapacity(index: Int): Int = if (emergencyPhase(index) != null) EMERGENCY_PHASE_CAPACITY else 0

    /** Client UI and AI may advise; only the authorized server may finalize privileged state. */
    fun clientMayFinalizePrivilegedState(): Boolean = false
    fun aiMayFinalizePrivilegedState(): Boolean = false

    /** Account closure and anti-bypass decisions must follow disclosed server policy and appeal controls. */
    fun requiresServerPolicyForEnforcement(): Boolean = true
    fun permitsCovertMonitoringForEnforcement(): Boolean = false
}
