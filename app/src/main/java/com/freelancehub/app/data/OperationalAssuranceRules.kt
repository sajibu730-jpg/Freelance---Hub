package com.freelancehub.app.data

object OperationalAssuranceRules {
    private val shaPattern = Regex("^[A-Fa-f0-9]{32,128}$")
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")

    fun isVerifiableArtifactHash(hash: String): Boolean = shaPattern.matches(hash.trim())
    fun isReleaseVersion(version: String): Boolean = versionPattern.matches(version.trim())
    fun canMarkBackupVerified(hash: String): Boolean = isVerifiableArtifactHash(hash)
    fun canStartRestoreDrill(hasRunningDrill: Boolean): Boolean = !hasRunningDrill
    fun canRollback(reason: String): Boolean = reason.trim().length in 8..500
    fun isHealthStatusValid(status: String): Boolean =
        status in setOf("HEALTHY", "DEGRADED", "UNAVAILABLE", "RECOVERING")
}
