package com.freelancehub.app.security

import com.freelancehub.app.data.UserRole

/** Pure authorization rules. Backend authorization remains authoritative in production. */
object AuthorizationRules {
    enum class Action { VIEW_JOBS, CREATE_JOB, MANAGE_OWN_JOB, SUBMIT_PROPOSAL, SUBMIT_WORK, MANAGE_PROPOSAL, REVIEW_COMPLETED_JOB, SEND_JOB_MESSAGE, ADMIN_MODERATION }

    fun isAllowed(role: UserRole, action: Action): Boolean = when (action) {
        Action.VIEW_JOBS, Action.SEND_JOB_MESSAGE -> true
        Action.CREATE_JOB, Action.MANAGE_OWN_JOB, Action.MANAGE_PROPOSAL -> role == UserRole.CLIENT
        Action.SUBMIT_PROPOSAL, Action.SUBMIT_WORK -> role == UserRole.FREELANCER
        Action.REVIEW_COMPLETED_JOB -> role == UserRole.CLIENT || role == UserRole.FREELANCER
        Action.ADMIN_MODERATION -> false // Admin is intentionally not a client-side UserRole.
    }

    fun ownsResource(actorEmail: String, ownerEmail: String): Boolean =
        actorEmail.isNotBlank() && ownerEmail.isNotBlank() && actorEmail.equals(ownerEmail, ignoreCase = true)

    fun isParticipant(actorEmail: String, firstEmail: String, secondEmail: String): Boolean =
        ownsResource(actorEmail, firstEmail) || ownsResource(actorEmail, secondEmail)
}
