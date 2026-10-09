package com.freelancehub.app.data

object ReleaseEvidenceChainRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun canRecord(
        releaseVersion: String,
        versionCode: Int,
        evidenceId: String,
        predecessorEvidenceId: String?,
        chainPosition: Int,
        evidenceSha256: String,
        predecessorSha256: String?,
        linkVerified: Boolean,
        provenanceVerified: Boolean,
        integrityVerified: Boolean,
        immutableReference: String,
        chainVerificationEvidence: String
    ): Boolean =
        releaseVersion == "4.700.0" &&
            versionCode == 2260 &&
            evidenceId.trim().isNotEmpty() &&
            (predecessorEvidenceId == null || predecessorEvidenceId.trim().isNotEmpty()) &&
            chainPosition >= 0 &&
            sha256.matches(evidenceSha256) &&
            (predecessorSha256 == null || sha256.matches(predecessorSha256)) &&
            linkVerified &&
            provenanceVerified &&
            integrityVerified &&
            immutableReference.trim().isNotEmpty() &&
            chainVerificationEvidence.trim().isNotEmpty()
}
