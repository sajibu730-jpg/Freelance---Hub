package com.freelancehub.app.data

object ReleaseProvenanceRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val revision = Regex("^[0-9a-fA-F]{7,64}$")
    private val environments = setOf("CI", "STAGING", "PRODUCTION")
    private val statuses = setOf("DECLARED", "VERIFIED", "REJECTED", "REVOKED")

    fun isArtifactSha256Valid(value: String): Boolean = sha256.matches(value)
    fun isSourceRevisionValid(value: String): Boolean = revision.matches(value)
    fun isEnvironmentValid(value: String): Boolean = value in environments
    fun isStatusValid(value: String): Boolean = value in statuses
    fun isDeployable(environment: String, status: String): Boolean =
        isEnvironmentValid(environment) && status == "VERIFIED"
}
