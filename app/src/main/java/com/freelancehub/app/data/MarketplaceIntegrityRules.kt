package com.freelancehub.app.data

/** v48.001-v48.400 marketplace integrity contracts. Enforcement remains server-controlled. */
object MarketplaceIntegrityRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_FREELANCERS_PER_PHASE = 50

    fun jobMatchesFreelancerSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean {
        val normalizedJob = jobSkill.trim().lowercase()
        return normalizedJob.isNotEmpty() && verifiedSkills.any { it.trim().lowercase() == normalizedJob }
    }

    fun normalHiringWindowIsValid(hours: Int): Boolean = hours == NORMAL_HIRING_WINDOW_HOURS

    fun emergencyHiringWindowIsValid(hours: Int, phaseHours: Int, freelancersPerPhase: Int): Boolean =
        hours == EMERGENCY_HIRING_WINDOW_HOURS &&
            phaseHours == EMERGENCY_PHASE_HOURS &&
            freelancersPerPhase == EMERGENCY_FREELANCERS_PER_PHASE

    fun freelancerEligibleForDistribution(active: Boolean, available: Boolean, workQualityWarning: Boolean): Boolean =
        active && available && !workQualityWarning

    fun warningMayRecover(recoveryConditionMet: Boolean): Boolean = recoveryConditionMet

    /** Account, payment and enforcement outcomes cannot be finalized by client UI or AI. */
    fun clientMayEnforceAccountOutcome(): Boolean = false
    fun aiMayFinalizeMarketplaceOutcome(): Boolean = false

    /** Anti-bypass enforcement must use disclosed platform rules, not covert monitoring. */
    fun requiresDisclosedEnforcementPolicy(): Boolean = true
}
