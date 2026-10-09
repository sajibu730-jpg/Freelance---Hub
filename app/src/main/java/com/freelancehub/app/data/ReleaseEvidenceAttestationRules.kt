package com.freelancehub.app.data

object ReleaseEvidenceAttestationRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val verificationMethods = setOf("HASH", "MANIFEST", "CI", "TEST", "REVIEW")
    private val verificationStatuses = setOf("VERIFIED", "REJECTED")

    fun canAttest(
        releaseVersion: String,
        versionCode: Int,
        evidenceId: String,
        attestationId: String,
        attestorReference: String,
        verificationMethod: String,
        verificationStatus: String,
        verifiedAtEpochSeconds: Long,
        evidenceSha256: String,
        immutableReference: String,
        verificationNotes: String
    ): Boolean =
        releaseVersion == "4.600.0" &&
            versionCode == 2250 &&
            evidenceId.trim().isNotEmpty() &&
            attestationId.trim().isNotEmpty() &&
            attestorReference.trim().isNotEmpty() &&
            verificationMethod in verificationMethods &&
            verificationStatus in verificationStatuses &&
            verifiedAtEpochSeconds > 0 &&
            sha256.matches(evidenceSha256) &&
            immutableReference.trim().isNotEmpty() &&
            verificationNotes.trim().isNotEmpty()
}
