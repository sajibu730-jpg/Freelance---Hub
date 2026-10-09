package com.freelancehub.app.security

/** Deterministic guards for auditable moderation actions. Backend remains authoritative. */
object ModerationActionRules {
    enum class ActorRole { ADMIN, MODERATOR, SYSTEM }
    enum class Action { WARN, REQUIRE_REVIEW, SUSPEND, CLOSE, REINSTATE, DISMISS_REPORT }

    const val MAX_REASON_LENGTH = 1000

    fun validActorId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validTargetId(id: String): Boolean =
        id.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))

    fun validReason(reason: String): Boolean =
        reason.trim().length in 1..MAX_REASON_LENGTH

    fun actionAllowed(role: ActorRole, action: Action): Boolean = when (role) {
        ActorRole.SYSTEM -> true
        ActorRole.ADMIN -> true
        ActorRole.MODERATOR -> action != Action.CLOSE && action != Action.REINSTATE
    }

    fun auditEntryValid(
        actorId: String,
        targetId: String,
        reason: String,
        role: ActorRole,
        action: Action
    ): Boolean =
        validActorId(actorId) && validTargetId(targetId) && validReason(reason) &&
            actionAllowed(role, action)

    fun duplicateActionBlocked(existingEventIds: Set<String>, eventId: String): Boolean =
        eventId.trim() in existingEventIds

    fun validEventId(eventId: String): Boolean =
        eventId.trim().matches(Regex("^[A-Za-z0-9_-]{8,128}$"))
}
