package com.freelancehub.app.data

/** Deterministic client-side guard for operational assurance evidence. */
object ReleaseOperationalAssuranceRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val statuses = setOf("READY", "BLOCKED", "REQUIRES_ACTION")

    fun canRecord(
        environment: String,
        artifactSha256: String,
        migrationSha256: String,
        sourceSha256: String,
        status: String,
        backupVerified: Boolean,
        restoreDrillVerified: Boolean,
        monitoringVerified: Boolean,
        rollbackVerified: Boolean,
        securityGateVerified: Boolean,
        ownerEvidence: String,
        verificationEvidence: String
    ): Boolean =
        environment == "PRODUCTION" &&
            sha256.matches(artifactSha256) &&
            sha256.matches(migrationSha256) &&
            sha256.matches(sourceSha256) &&
            status in statuses &&
            backupVerified && restoreDrillVerified && monitoringVerified &&
            rollbackVerified && securityGateVerified &&
            ownerEvidence.trim().isNotEmpty() && verificationEvidence.trim().isNotEmpty()
}
