package com.freelancehub.app.data

object ReleaseIntegrityBaselineRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val statuses = setOf("VERIFIED", "BLOCKED", "REQUIRES_ACTION")

    fun canRecord(
        releaseVersion: String,
        versionCode: Int,
        artifactSha256: String,
        sourceSha256: String,
        migrationSha256: String,
        status: String,
        releaseGateVerified: Boolean,
        testGateVerified: Boolean,
        securityGateVerified: Boolean,
        evidence: String
    ): Boolean =
        releaseVersion == "4.000.0" &&
            versionCode == 2190 &&
            sha256.matches(artifactSha256) &&
            sha256.matches(sourceSha256) &&
            sha256.matches(migrationSha256) &&
            status in statuses &&
            releaseGateVerified && testGateVerified && securityGateVerified &&
            evidence.trim().isNotEmpty()
}
