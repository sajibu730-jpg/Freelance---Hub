package com.freelancehub.app.data

/** Release promotion gates used before an artifact may be treated as production-ready. */
object ReleasePromotionRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val environments = setOf("CI", "STAGING", "PRODUCTION")
    private val requiredChecks = setOf(
        "ROLLBACK_ATTESTATION",
        "RECOVERY_ATTESTATION",
        "ARTIFACT_INTEGRITY",
        "MIGRATION_INTEGRITY",
        "TESTS",
        "LINT",
        "SECURITY"
    )

    fun isSha256Valid(value: String): Boolean = sha256.matches(value)
    fun isEnvironmentValid(value: String): Boolean = value in environments
    fun areChecksComplete(completed: Set<String>): Boolean = completed.containsAll(requiredChecks)

    fun canPromote(
        environment: String,
        artifactSha256: String,
        migrationSha256: String,
        completedChecks: Set<String>,
        approvalDecision: String
    ): Boolean =
        environment == "PRODUCTION" &&
            isSha256Valid(artifactSha256) &&
            isSha256Valid(migrationSha256) &&
            areChecksComplete(completedChecks) &&
            approvalDecision == "APPROVED"
}
