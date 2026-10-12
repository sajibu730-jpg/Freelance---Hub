package com.freelancehub.app.data

import com.freelancehub.app.BuildConfig

/**
 * Production backend configuration.
 * Values are injected during the build.
 */
object RuntimeBackendConfig {

    fun current(): BackendConfig = BackendConfig(
        baseUrl = BuildConfig.BACKEND_BASE_URL,
        projectId = BuildConfig.BACKEND_PROJECT_ID,
        environment = "production",
        anonKey = BuildConfig.SUPABASE_ANON_KEY
    )
}
