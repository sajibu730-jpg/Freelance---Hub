package com.freelancehub.app.update

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val mandatory: Boolean,
    val downloadUrl: String
)

/** Server-controlled release metadata. The server must return signed/validated metadata in production. */
object UpdateConfig {
    const val RELEASE_METADATA_PATH = "/v1/app/releases/android"
    const val SUPPORTED_UPDATE_CHANNEL = "production"
}
