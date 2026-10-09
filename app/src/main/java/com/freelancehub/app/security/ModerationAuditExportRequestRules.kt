package com.freelancehub.app.security

/** Guards idempotent, bounded moderation-audit export requests and result retention metadata. */
object ModerationAuditExportRequestRules {
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_RETENTION_MINUTES = 60
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun validRequestId(requestId: String): Boolean = ID_PATTERN.matches(requestId.trim())

    fun validRetentionMinutes(minutes: Int): Boolean = minutes in 1..MAX_RETENTION_MINUTES

    fun validRequest(
        requestId: String,
        role: String,
        format: String,
        count: Int,
        sourceType: String?,
        sourceId: String?,
        retentionMinutes: Int,
        from: String,
        to: String
    ): Boolean =
        validRequestId(requestId) &&
            ModerationAuditExportRules.validExportRequest(role, format, count) &&
            ModerationAuditExportRules.sourceFilterRequired(sourceType, sourceId) &&
            validRetentionMinutes(retentionMinutes) &&
            ModerationAuditExportTimeWindowRules.validWindow(from, to)

    /** A request may be replayed only when its stored fingerprint is identical. */
    fun replayMatches(storedFingerprint: String?, incomingFingerprint: String?): Boolean =
        !storedFingerprint.isNullOrBlank() && storedFingerprint == incomingFingerprint
}
