package com.freelancehub.app.data

/** v51.900 marketplace governance continuity contracts. Server-side authorization remains authoritative. */
object MarketplaceGovernanceRules {
    fun serverMustAuthorizeMutation(): Boolean = true
    fun clientMayBypassServerAuthorization(): Boolean = false
    fun aiMayFinalizeGovernanceOutcome(): Boolean = false
    fun financialMutationRequiresIdempotencyKey(): Boolean = true
    fun destructiveRecoveryRequiresEvidence(): Boolean = true
    fun enforcementRequiresDisclosedPolicy(): Boolean = true
    fun appealPathMustRemainAvailable(): Boolean = true
    fun sensitiveMonitoringMustBeExplicitlyDisclosed(): Boolean = true

    fun matchingRequiresVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean {
        val target = jobSkill.trim().lowercase()
        return target.isNotEmpty() && verifiedSkills.any { it.trim().lowercase() == target }
    }
}
