package com.freelancehub.app.security

/** Rules for notification read-state: audience ownership only; notification content is immutable. */
object NotificationReadRules {
    fun canMarkRead(actorEmail: String, audienceEmail: String): Boolean =
        actorEmail.isNotBlank() && audienceEmail.isNotBlank() && actorEmail.equals(audienceEmail, true)

    fun validNotificationId(id: Long): Boolean = id > 0L
}
