package com.freelancehub.app.data

/** Pure validation rules for append-only trust/safety evidence events. */
object TrustSafetyEvidenceRules {
    private val allowedTypes = setOf(
        "CASE_OPENED", "CASE_REVIEW_STARTED", "CASE_RESOLVED", "CASE_DISMISSED",
        "ENFORCEMENT_APPLIED", "ENFORCEMENT_REINSTATED"
    )

    fun validEventType(type: String): Boolean = type in allowedTypes

    fun validSequence(previousSequence: Long?, sequence: Long): Boolean =
        sequence > 0L && (previousSequence == null || sequence == previousSequence + 1L)

    fun validOccurredAt(previousOccurredAtEpochMs: Long?, occurredAtEpochMs: Long): Boolean =
        occurredAtEpochMs >= 0L &&
            (previousOccurredAtEpochMs == null || occurredAtEpochMs >= previousOccurredAtEpochMs)

    fun validHash(hash: String): Boolean =
        hash.length == 64 && hash.all { it in "0123456789abcdef" }

    fun validActor(actorId: String): Boolean = actorId.isNotBlank()
}
