package com.freelancehub.app.data

object SecurityIncidentResponseRules {
    private val severities = setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    private val states = setOf("OPEN", "TRIAGED", "CONTAINED", "RECOVERING", "RESOLVED", "CLOSED")
    private val actions = setOf("CONTAIN", "INVESTIGATE", "RECOVER", "VERIFY", "NOTIFY")
    private val outcomes = setOf("UNKNOWN", "NO_IMPACT", "IMPACTED", "REQUIRES_REVIEW")

    fun isSeverityValid(value: String): Boolean = value in severities
    fun isStateValid(value: String): Boolean = value in states
    fun isActionTypeValid(value: String): Boolean = value in actions
    fun isAssessmentOutcomeValid(value: String): Boolean = value in outcomes
    fun isEvidenceHashValid(value: String): Boolean = value.length in 32..128 && value.all { it in "0123456789abcdefABCDEF" }
}
