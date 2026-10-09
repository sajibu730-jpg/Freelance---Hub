package com.freelancehub.app.security

/** Guards controlled export of moderation audit records without weakening read access. */
object ModerationAuditExportRules {
    enum class ExportRole { ADMIN, COMPLIANCE_REVIEWER }

    const val MAX_EXPORT_RECORDS = 500
    const val MAX_REASON_LENGTH = 1000
    const val MAX_EXPORT_FORMAT_LENGTH = 16

    fun canExport(role: String): Boolean =
        ExportRole.entries.any { it.name == role.trim().uppercase() }

    fun validFormat(format: String): Boolean =
        format.trim().uppercase() in setOf("JSON", "CSV") && format.trim().length <= MAX_EXPORT_FORMAT_LENGTH

    fun validRecordCount(count: Int): Boolean = count in 1..MAX_EXPORT_RECORDS

    fun validExportRequest(role: String, format: String, count: Int): Boolean =
        canExport(role) && validFormat(format) && validRecordCount(count)

    /** Export requests must be explicit; an omitted source filter is not accepted. */
    fun sourceFilterRequired(sourceType: String?, sourceId: String?): Boolean =
        !sourceType.isNullOrBlank() && !sourceId.isNullOrBlank() &&
            ModerationTraceRules.validSourceType(sourceType) &&
            ModerationTraceRules.validSourceId(sourceId)
}
