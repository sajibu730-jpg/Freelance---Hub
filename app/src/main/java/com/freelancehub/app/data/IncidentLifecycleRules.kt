package com.freelancehub.app.data

/** Pure lifecycle rules for security incidents; terminal states cannot reopen. */
object IncidentLifecycleRules {
    private val transitions = mapOf(
        "OPEN" to setOf("TRIAGED", "CONTAINED"),
        "TRIAGED" to setOf("CONTAINED", "RESOLVED"),
        "CONTAINED" to setOf("RECOVERING", "RESOLVED"),
        "RECOVERING" to setOf("RESOLVED"),
        "RESOLVED" to setOf("CLOSED"),
        "CLOSED" to emptySet()
    )

    private val states = transitions.keys

    fun canTransition(from: String, to: String): Boolean = to in (transitions[from] ?: emptySet())

    fun validState(state: String): Boolean = state in states

    fun validIncidentId(id: String): Boolean =
        id.length in 16..128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    fun validActorId(id: String): Boolean = id.isNotBlank() && id.length <= 128

    fun validEvidenceHash(hash: String): Boolean =
        hash.length == 64 && hash.all { it in "0123456789abcdef" }

    /** Canonical evidence input order; server derives the final SHA-256 evidence hash. */
    fun evidenceCanonicalInput(
        incidentId: String, sequenceNo: Long, fromState: String?, toState: String,
        actionType: String, actorId: String, occurredAtText: String, previousHash: String?
    ): String = listOf(
        incidentId, sequenceNo.toString(), fromState.orEmpty(), toState, actionType,
        actorId, occurredAtText, previousHash.orEmpty()
    ).joinToString("|")

    fun validActionType(action: String): Boolean =
        action in setOf("CONTAIN", "INVESTIGATE", "RECOVER", "VERIFY", "NOTIFY")

    fun validEventTransition(fromState: String?, toState: String): Boolean =
        if (fromState == null) toState == "OPEN" else canTransition(fromState, toState)

    fun validFirstEvent(toState: String, action: String): Boolean =
        toState == "OPEN" && action == "NOTIFY"

    fun validEventTime(
        previousEpochMillis: Long?,
        currentEpochMillis: Long,
        nowEpochMillis: Long,
        maxFutureSkewMillis: Long = 5 * 60 * 1000L
    ): Boolean {
        if (currentEpochMillis > nowEpochMillis + maxFutureSkewMillis) return false
        return previousEpochMillis == null || currentEpochMillis > previousEpochMillis
    }

    fun validPreviousHashForContinuation(sequenceNo: Long, previousHash: String?, fromState: String?): Boolean =
        if (sequenceNo == 1L) previousHash == null && fromState == null
        else previousHash != null && validEvidenceHash(previousHash) && !fromState.isNullOrBlank() && validState(fromState)

    fun actionMatchesTargetState(action: String, targetState: String): Boolean = when (targetState) {
        "OPEN" -> action == "NOTIFY"
        "TRIAGED" -> action == "INVESTIGATE"
        "CONTAINED" -> action == "CONTAIN"
        "RECOVERING" -> action == "RECOVER"
        "RESOLVED", "CLOSED" -> action == "VERIFY"
        else -> false
    }
}
