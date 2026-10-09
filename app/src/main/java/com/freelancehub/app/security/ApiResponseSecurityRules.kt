package com.freelancehub.app.security

/** Pure response-level guards used before JSON payloads are accepted by the client. */
object ApiResponseSecurityRules {
    private const val MAX_RESPONSE_BYTES = 2 * 1_048_576

    fun isSuccessful(statusCode: Int): Boolean = statusCode in 200..299

    fun canAcceptJson(statusCode: Int, contentType: String?, contentLength: Long = -1L): Boolean {
        if (!isSuccessful(statusCode)) return false
        if (contentLength > MAX_RESPONSE_BYTES) return false
        if (statusCode == 204) return true
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "application/json" || type.endsWith("+json")
    }

    fun canAcceptBodySize(sizeBytes: Int): Boolean = sizeBytes in 0..MAX_RESPONSE_BYTES

    fun maxResponseBytes(): Int = MAX_RESPONSE_BYTES
}
