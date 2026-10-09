package com.freelancehub.app.data

object ReleaseTraceabilityRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val statuses = setOf("TRACEABLE", "BLOCKED", "REQUIRES_ACTION")

    fun canRecord(
        releaseVersion: String,
        sourceRevision: String,
        sourceSha256: String,
        migrationSha256: String,
        artifactSha256: String,
        changeControlEvidence: String,
        verificationEvidence: String,
        status: String
    ): Boolean =
        releaseVersion == "4.300.0" &&
            sourceRevision.trim().isNotEmpty() &&
            sha256.matches(sourceSha256) &&
            sha256.matches(migrationSha256) &&
            sha256.matches(artifactSha256) &&
            changeControlEvidence.trim().isNotEmpty() &&
            verificationEvidence.trim().isNotEmpty() &&
            status in statuses
}
