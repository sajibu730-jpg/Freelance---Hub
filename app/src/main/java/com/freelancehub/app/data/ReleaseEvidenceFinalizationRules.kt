package com.freelancehub.app.data

object ReleaseEvidenceFinalizationRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun canFinalize(
        releaseVersion: String,
        versionCode: Int,
        finalizationId: String,
        evidenceChainRootSha256: String,
        evidenceChainTipSha256: String,
        evidenceCount: Int,
        chainComplete: Boolean,
        integrityVerified: Boolean,
        provenanceVerified: Boolean,
        finalizationVerified: Boolean,
        immutableReference: String,
        finalizationEvidence: String
    ): Boolean =
        releaseVersion == "4.800.0" &&
            versionCode == 2270 &&
            finalizationId.trim().isNotEmpty() &&
            sha256.matches(evidenceChainRootSha256) &&
            sha256.matches(evidenceChainTipSha256) &&
            evidenceCount >= 1 &&
            chainComplete &&
            integrityVerified &&
            provenanceVerified &&
            finalizationVerified &&
            immutableReference.trim().isNotEmpty() &&
            finalizationEvidence.trim().isNotEmpty()
}
