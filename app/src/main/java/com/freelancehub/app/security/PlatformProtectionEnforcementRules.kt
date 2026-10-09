package com.freelancehub.app.security

/**
 * Deterministic, user-visible escalation guidance for marketplace protection.
 * Server-side policy remains authoritative; this module does not silently surveil users.
 */
object PlatformProtectionEnforcementRules {
    enum class Outcome { ALLOW, WARN, REVIEW_REQUIRED }

    data class Decision(val outcome: Outcome, val reason: String)

    fun decide(recentWarnings: Int, confirmedViolations: Int): Decision {
        if (recentWarnings < 0 || confirmedViolations < 0) {
            return Decision(Outcome.REVIEW_REQUIRED, "Invalid enforcement counters")
        }
        return when {
            confirmedViolations >= 3 -> Decision(Outcome.REVIEW_REQUIRED, "Multiple confirmed protection violations require review")
            recentWarnings >= 3 -> Decision(Outcome.REVIEW_REQUIRED, "Repeated warnings require review")
            recentWarnings > 0 || confirmedViolations > 0 -> Decision(Outcome.WARN, "Marketplace protection warning")
            else -> Decision(Outcome.ALLOW, "No protection issue recorded")
        }
    }

    fun countersValid(recentWarnings: Int, confirmedViolations: Int): Boolean =
        recentWarnings in 0..100 && confirmedViolations in 0..100
}
