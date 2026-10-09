package com.freelancehub.app.security

/** Pure client-side communication authorization. Production backend must enforce the same rules. */
object CommunicationRules {
    fun canReadConversation(actorEmail: String, otherEmail: String, jobOwnerEmail: String, freelancerEmail: String): Boolean {
        if (actorEmail.isBlank() || otherEmail.isBlank()) return false
        val pair = (actorEmail.equals(jobOwnerEmail, true) && otherEmail.equals(freelancerEmail, true)) ||
            (actorEmail.equals(freelancerEmail, true) && otherEmail.equals(jobOwnerEmail, true))
        return pair
    }

    fun canMarkNotificationRead(actorEmail: String, audienceEmail: String): Boolean =
        actorEmail.isNotBlank() && audienceEmail.isNotBlank() && actorEmail.equals(audienceEmail, true)
}
