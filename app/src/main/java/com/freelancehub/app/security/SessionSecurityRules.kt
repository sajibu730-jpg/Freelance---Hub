package com.freelancehub.app.security

/** Pure client-side session rules; server remains authoritative for authentication. */
object SessionSecurityRules {
    private const val MAX_TOKEN_LENGTH = 8192

    fun shouldRefresh(httpStatus: Int, hasRefreshToken: Boolean): Boolean =
        httpStatus == 401 && hasRefreshToken

    fun shouldClearSession(httpStatus: Int, hasRefreshToken: Boolean): Boolean =
        httpStatus == 401 && !hasRefreshToken

    fun canStoreAccessToken(token: String): Boolean = validToken(token)

    fun canUseRefreshToken(token: String): Boolean = validToken(token)

    /** Reject obviously malformed/oversized bearer material before it reaches storage or headers. */
    private fun validToken(token: String): Boolean =
        token.trim().isNotEmpty() && token.length <= MAX_TOKEN_LENGTH
}
