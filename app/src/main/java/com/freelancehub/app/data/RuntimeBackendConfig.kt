package com.freelancehub.app.data

import com.freelancehub.app.BuildConfig

/**
 * Production backend configuration is injected at build time. No endpoint,
 * project credential, or secret is embedded in source control.
 */
object RuntimeBackendConfig {
    fun current(): BackendConfig = BackendConfig(
        baseUrl = BuildConfig.BACKEND_BASE_URL,
        projectId = BuildConfig.BACKEND_PROJECT_ID,
        environment = "production"
    )
}
