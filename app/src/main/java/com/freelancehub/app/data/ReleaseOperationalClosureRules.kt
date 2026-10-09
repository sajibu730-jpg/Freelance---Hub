package com.freelancehub.app.data

object ReleaseOperationalClosureRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun canClose(
        releaseVersion: String,
        versionCode: Int,
        releaseEvidenceFinalized: Boolean,
        artifactProvenanceVerified: Boolean,
        migrationManifestVerified: Boolean,
        rollbackPlanPresent: Boolean,
        externalDeploymentInputsOutstanding: Boolean,
        closureEvidenceHash: String,
        immutableReference: String
    ): Boolean =
        releaseVersion == "4.900.0" &&
            versionCode == 2280 &&
            releaseEvidenceFinalized &&
            artifactProvenanceVerified &&
            migrationManifestVerified &&
            rollbackPlanPresent &&
            !externalDeploymentInputsOutstanding &&
            sha256.matches(closureEvidenceHash) &&
            immutableReference.trim().isNotEmpty()
}
