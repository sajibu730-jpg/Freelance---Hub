package com.freelancehub.app.data

/** v50.401-v50.900 final marketplace integrity contracts. Server policy remains authoritative. */
object MarketplaceIntegrityFinalRules {
    fun validSkillMatch(jobSkill: String, selectedOrVerifiedSkills: Set<String>): Boolean =
        jobSkill.isNotBlank() && selectedOrVerifiedSkills.any { it.equals(jobSkill.trim(), ignoreCase = true) }

    fun validNormalHiringWindow(hours: Int): Boolean = hours == 24

    fun validEmergencyWindow(totalHours: Int, phaseHours: Int, perPhase: Int): Boolean =
        totalHours == 6 && phaseHours == 3 && perPhase == 50

    fun eligibleForProgressiveDistribution(active: Boolean, available: Boolean, skillMatched: Boolean): Boolean =
        active && available && skillMatched

    fun serverAuthorizedMutation(serverAuthorized: Boolean): Boolean = serverAuthorized

    fun aiMayFinalizeMarketplaceOutcome(): Boolean = false

    fun covertBypassMonitoringAllowed(): Boolean = false

    fun reusableAfterClosure(identityReusable: Boolean): Boolean = false
}
