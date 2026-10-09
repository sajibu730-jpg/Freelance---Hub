package com.freelancehub.app.security

/** Guards immutable metadata attached to a completed moderation-audit export. */
object ModerationAuditExportManifestRules {
    const val MAX_MANIFEST_ID_LENGTH = 128
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun validManifestId(id: String): Boolean = ID_PATTERN.matches(id.trim())

    fun immutableManifest(
        existingManifestId: String?,
        incomingManifestId: String,
        existingChecksum: String?,
        incomingChecksum: String
    ): Boolean {
        if (!validManifestId(incomingManifestId) || !ModerationAuditExportResponseRules.validChecksum(incomingChecksum)) return false
        if (existingManifestId.isNullOrBlank() && existingChecksum.isNullOrBlank()) return true
        return existingManifestId == incomingManifestId && existingChecksum == incomingChecksum
    }
}
