package com.freelancehub.app.data

object DeploymentAttestationRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val evidenceHash = Regex("^[0-9a-fA-F]{32,128}$")

    fun validEnvironment(value: String): Boolean =
        value in setOf("CI", "STAGING", "PRODUCTION")

    fun validStatus(value: String): Boolean =
        value in setOf("PENDING", "VERIFIED", "REJECTED", "REVOKED")

    fun validDecision(value: String): Boolean =
        value in setOf("APPROVED", "REJECTED", "DEFERRED")

    fun validSha256(value: String): Boolean = sha256.matches(value)

    fun validEvidenceHash(value: String): Boolean = evidenceHash.matches(value)

    fun deployable(status: String, artifactSha256: String, manifestSha256: String): Boolean =
        status == "VERIFIED" && validSha256(artifactSha256) && validSha256(manifestSha256)
}
