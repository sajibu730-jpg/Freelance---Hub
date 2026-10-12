package com.freelancehub.app.data

import java.net.URI

data class BackendConfig(
    val baseUrl: String,
    val projectId: String,
    val environment: String = "production",
    val anonKey: String = ""
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
