package com.freelancehub.app.data

/** Server-authoritative handoff contracts; clients and AI remain advisory for privileged outcomes. */
object ProductionHandoffRules {
    const val RELEASE_BOUNDARY_VERSION = "54.900.0"
    const val MAX_AUTOMATIC_RETRY_ATTEMPTS = 3

    fun boundedRetryAllowed(attempt: Int, idempotencyKeyPresent: Boolean): Boolean =
        idempotencyKeyPresent && attempt in 1..MAX_AUTOMATIC_RETRY_ATTEMPTS

    fun destructiveRecoveryAllowed(serverAuthorized: Boolean, evidenceVerified: Boolean): Boolean =
        serverAuthorized && evidenceVerified

    const val NORMAL_HIRING_WINDOW_HOURS = 24
    const val EMERGENCY_HIRING_WINDOW_HOURS = 6
    const val EMERGENCY_PHASE_HOURS = 3
    const val EMERGENCY_PHASE_DISTRIBUTION = 50

    fun jobMatchesVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        verifiedSkills.any { it.equals(jobSkill, ignoreCase = true) }

    fun eligibleForEmergencyDistribution(active: Boolean, available: Boolean, skillMatch: Boolean): Boolean =
        active && available && skillMatch

    fun clientMayFinalizePrivilegedState(): Boolean = false
    fun aiMayFinalizePrivilegedState(): Boolean = false
    fun allowsCovertMonitoring(): Boolean = false
    fun allowsUnboundedRetry(): Boolean = false
    fun allowsClientSideFinancialMutation(): Boolean = false
    fun requiresServerAuthorizationForRecovery(): Boolean = true
}
