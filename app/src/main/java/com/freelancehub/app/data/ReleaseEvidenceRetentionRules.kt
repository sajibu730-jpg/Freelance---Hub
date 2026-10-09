package com.freelancehub.app.data

object ReleaseEvidenceRetentionRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val evidenceTypes = setOf("SOURCE", "MIGRATION", "MANIFEST", "BUILD_RECIPE", "TEST", "CI")
    private val retentionClasses = setOf("RELEASE", "AUDIT", "INCIDENT")

    fun canRecord(
        releaseVersion: String,
        versionCode: Int,
        evidenceId: String,
        evidenceSha256: String,
        evidenceType: String,
        retentionClass: String,
        integrityVerified: Boolean,
        provenanceVerified: Boolean,
        immutableReference: String,
        verificationEvidence: String
    ): Boolean =
        releaseVersion == "4.500.0" &&
            versionCode == 2240 &&
            evidenceId.trim().isNotEmpty() &&
            sha256.matches(evidenceSha256) &&
            evidenceType in evidenceTypes &&
            retentionClass in retentionClasses &&
            integrityVerified &&
            provenanceVerified &&
            immutableReference.trim().isNotEmpty() &&
            verificationEvidence.trim().isNotEmpty()
}
