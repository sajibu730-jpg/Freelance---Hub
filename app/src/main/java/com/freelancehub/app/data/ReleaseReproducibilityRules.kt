package com.freelancehub.app.data

object ReleaseReproducibilityRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val statuses = setOf("REPRODUCIBLE", "BLOCKED", "REQUIRES_ACTION")

    fun canRecord(
        releaseVersion: String,
        versionCode: Int,
        sourceRevision: String,
        sourceSha256: String,
        migrationSha256: String,
        manifestSha256: String,
        buildRecipeSha256: String,
        status: String,
        cleanSourceVerified: Boolean,
        versionBindingVerified: Boolean,
        buildRecipeVerified: Boolean,
        verificationEvidence: String
    ): Boolean =
        releaseVersion == "4.400.0" &&
            versionCode == 2230 &&
            sourceRevision.trim().isNotEmpty() &&
            sha256.matches(sourceSha256) &&
            sha256.matches(migrationSha256) &&
            sha256.matches(manifestSha256) &&
            sha256.matches(buildRecipeSha256) &&
            status in statuses &&
            cleanSourceVerified &&
            versionBindingVerified &&
            buildRecipeVerified &&
            verificationEvidence.trim().isNotEmpty()
}
