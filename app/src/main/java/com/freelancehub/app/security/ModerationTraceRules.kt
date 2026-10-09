package com.freelancehub.app.security

/** Deterministic linkage guards so moderation actions retain a reviewable source trail. */
object ModerationTraceRules {
    enum class SourceType { REPORT, ENFORCEMENT, APPEAL, SYSTEM }
    const val MAX_SOURCE_ID_LENGTH = 128
    const val MAX_CORRELATION_ID_LENGTH = 128

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun validSourceId(id: String): Boolean =
        id.trim().length in 8..MAX_SOURCE_ID_LENGTH && ID_PATTERN.matches(id.trim())

    fun validCorrelationId(id: String?): Boolean =
        id == null || (id.trim().isNotEmpty() && id.trim().length <= MAX_CORRELATION_ID_LENGTH && ID_PATTERN.matches(id.trim()))

    fun validSourceType(sourceType: String): Boolean =
        SourceType.entries.any { it.name == sourceType.trim().uppercase() }

    fun traceValid(sourceType: String, sourceId: String, correlationId: String?): Boolean =
        validSourceType(sourceType) && validSourceId(sourceId) && validCorrelationId(correlationId)

    fun sameTrace(existingSourceType: String?, existingSourceId: String?, incomingSourceType: String, incomingSourceId: String): Boolean =
        existingSourceType == null || (existingSourceType == incomingSourceType && existingSourceId == incomingSourceId)
}
