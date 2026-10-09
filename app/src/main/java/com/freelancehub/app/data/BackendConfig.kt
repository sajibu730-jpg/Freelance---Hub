package com.freelancehub.app.data

import java.net.URI

/**
 * Runtime backend configuration. Production endpoints must be injected by the
 * deployment/build environment; secrets never belong in the APK.
 */
data class BackendConfig(
    val baseUrl: String,
    val projectId: String,
    val environment: String = "production"
) {
    fun isConfigured(): Boolean {
        if (projectId.isBlank() || baseUrl.isBlank()) return false
        if (baseUrl.contains("example.com", ignoreCase = true)) return false
        return runCatching {
            val uri = URI(baseUrl.trim())
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo.isNullOrBlank() &&
                uri.fragment.isNullOrBlank()
        }.getOrDefault(false)
    }
}
