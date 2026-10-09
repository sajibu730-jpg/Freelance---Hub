package com.freelancehub.app.security

/** Pure request-level security rules used before network material is sent. */
object ApiRequestSecurityRules {
    private const val MAX_BODY_BYTES = 1_048_576
    private const val MAX_IDEMPOTENCY_KEY_LENGTH = 128

    fun canSendAccessToken(token: String): Boolean =
        SessionSecurityRules.canStoreAccessToken(token)

    fun canSendBody(body: ByteArray): Boolean = body.size <= MAX_BODY_BYTES

    fun canUseIdempotencyKey(key: String): Boolean =
        key.trim().isNotEmpty() &&
            key.length <= MAX_IDEMPOTENCY_KEY_LENGTH &&
            key.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
}
