package com.freelancehub.app.security

/** Prevents sensitive operational fields from being included in moderation-audit exports. */
object ModerationAuditExportRedactionRules {
    private val ALLOWED_FIELDS = setOf(
        "eventId", "actorId", "targetId", "role", "action", "reason",
        "sourceType", "sourceId", "createdAt", "evidenceIds", "correlationId"
    )

    private val ALWAYS_REDACTED_FIELDS = setOf(
        "password", "passwordHash", "accessToken", "refreshToken", "authorization",
        "cookie", "sessionToken", "paymentSecret", "webhookSecret", "privateKey"
    )

    fun isAllowedField(field: String): Boolean = ALLOWED_FIELDS.contains(field.trim())

    fun isSensitiveField(field: String): Boolean = ALWAYS_REDACTED_FIELDS.contains(field.trim())

    fun validExportFields(fields: Collection<String>): Boolean =
        fields.isNotEmpty() && fields.all { isAllowedField(it) } && fields.distinct().size == fields.size

    fun containsSensitiveField(fields: Collection<String>): Boolean =
        fields.any { isSensitiveField(it) }
}
