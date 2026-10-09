package com.freelancehub.app.data

/** v51.901-v52.400 end-to-end marketplace lifecycle contracts. */
object EndToEndMarketplaceRules {
    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_TOTAL_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION_LIMIT = 50

    fun skillMatched(jobSkill: String, selectedOrVerifiedSkills: Set<String>): Boolean =
        jobSkill.isNotBlank() && selectedOrVerifiedSkills.any { it.equals(jobSkill.trim(), ignoreCase = true) }

    fun eligibleForDistribution(active: Boolean, available: Boolean, skillMatched: Boolean): Boolean =
        active && available && skillMatched

    fun normalWindowIsValid(hours: Int): Boolean = hours == NORMAL_HIRING_WINDOW_HOURS

    fun emergencyWindowIsValid(totalHours: Int, phaseHours: Int, perPhase: Int): Boolean =
        totalHours == EMERGENCY_TOTAL_HOURS && phaseHours == EMERGENCY_PHASE_HOURS &&
            perPhase == EMERGENCY_PHASE_DISTRIBUTION_LIMIT

    fun requiresServerAuthorization(serverAuthorized: Boolean): Boolean = serverAuthorized

    fun idempotencyRequiredForFinancialMutation(hasIdempotencyKey: Boolean): Boolean = hasIdempotencyKey

    fun aiMayFinalizeOutcome(): Boolean = false

    fun clientMayFinalizePrivilegedOutcome(): Boolean = false

    fun covertMonitoringAllowed(): Boolean = false

    fun closedIdentityMayReopenAccount(identityReusable: Boolean): Boolean = false
}
