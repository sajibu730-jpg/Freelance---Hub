package com.freelancehub.app.security

/** Guards exported moderation-audit payload metadata and bounded response integrity. */
object ModerationAuditExportResponseRules {
    const val MAX_PAYLOAD_BYTES = 2_000_000
    const val MAX_CHECKSUM_LENGTH = 128
    private val HEX_SHA256 = Regex("^[A-Fa-f0-9]{64}$")

    fun validPayloadSize(bytes: Int): Boolean = bytes in 1..MAX_PAYLOAD_BYTES

    fun validChecksum(checksum: String): Boolean =
        checksum.trim().length <= MAX_CHECKSUM_LENGTH && HEX_SHA256.matches(checksum.trim())

    fun validContentType(format: String, contentType: String): Boolean {
        val normalizedFormat = format.trim().uppercase()
        val normalizedType = contentType.trim().lowercase()
        return when (normalizedFormat) {
            "JSON" -> normalizedType == "application/json"
            "CSV" -> normalizedType == "text/csv"
            else -> false
        }
    }

    fun validResponse(format: String, contentType: String, bytes: Int, checksum: String): Boolean =
        validContentType(format, contentType) && validPayloadSize(bytes) && validChecksum(checksum)
}
