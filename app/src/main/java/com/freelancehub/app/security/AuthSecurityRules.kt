package com.freelancehub.app.security

import com.freelancehub.app.data.AuthResponse

/** Pure validation for authentication responses before session material is persisted. */
object AuthSecurityRules {
    private const val MAX_USER_ID_LENGTH = 128
    private val allowedRoles = setOf("CLIENT", "FREELANCER", "ADMIN")

    fun canAcceptAuthResponse(response: AuthResponse, nowEpochSeconds: Long): Boolean {
        if (!SessionSecurityRules.canStoreAccessToken(response.accessToken)) return false
        if (response.userId.trim().isEmpty() || response.userId.length > MAX_USER_ID_LENGTH) return false
        if (response.userId.any { it.isISOControl() }) return false
        if (response.role.trim().uppercase() !in allowedRoles) return false
        val expiresAt = response.expiresAtEpochSeconds
        return expiresAt == null || expiresAt > nowEpochSeconds
    }

    /** A refresh-capable mobile session must have a valid refresh token as well. */
    fun canEstablishRefreshableSession(response: AuthResponse, nowEpochSeconds: Long): Boolean =
        canAcceptAuthResponse(response, nowEpochSeconds) &&
            !response.refreshToken.isNullOrBlank() &&
            SessionSecurityRules.canUseRefreshToken(response.refreshToken)
}
