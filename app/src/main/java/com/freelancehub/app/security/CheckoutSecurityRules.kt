package com.freelancehub.app.security

import java.net.URI

/** Client-side safety boundary for server-issued external checkout URLs. */
object CheckoutSecurityRules {
    fun isSafeExternalCheckoutUrl(value: String): Boolean {
        if (value.length !in 1..2048 || value.any { it.isWhitespace() }) return false
        return runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo.isNullOrBlank() &&
                uri.fragment.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun isValidCheckoutId(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9._:-]{8,200}$"))

    fun isKnownCheckoutStatus(value: String): Boolean =
        value.uppercase() in setOf("PENDING", "PAID", "FAILED", "CANCELLED", "EXPIRED")
}
