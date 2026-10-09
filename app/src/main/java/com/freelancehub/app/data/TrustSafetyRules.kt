package com.freelancehub.app.data

/** Pure validation rules for trust/safety moderation records; enforcement mutations remain server-controlled. */
object TrustSafetyRules {
    private val terminalCaseStates = setOf("RESOLVED", "DISMISSED")
    private val terminalActions = setOf("CLOSED", "REINSTATED")

    fun validCaseTransition(from: String, to: String): Boolean = when (from) {
        "OPEN" -> to == "UNDER_REVIEW"
        "UNDER_REVIEW" -> to == "RESOLVED" || to == "DISMISSED"
        else -> from in terminalCaseStates && to == from
    }

    fun validEnforcementAction(action: String): Boolean = action in setOf(
        "WARNING", "LIMITED", "SUSPENDED", "CLOSED", "REINSTATED"
    )

    fun validEnforcementWindow(startsAtEpochMs: Long, endsAtEpochMs: Long?): Boolean =
        endsAtEpochMs == null || endsAtEpochMs > startsAtEpochMs

    fun terminalAction(action: String): Boolean = action in terminalActions
}
