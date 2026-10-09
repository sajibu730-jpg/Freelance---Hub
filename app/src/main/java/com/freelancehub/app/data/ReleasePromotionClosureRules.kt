package com.freelancehub.app.data

/** Deterministic guard for closing a production promotion after monitoring. */
object ReleasePromotionClosureRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val outcomes = setOf("CLOSED", "BLOCKED", "ROLLED_BACK")
    private val channels = setOf("PLAY_INTERNAL", "PLAY_CLOSED", "PLAY_PRODUCTION", "CONTROLLED_ROLLOUT")

    fun canClose(
        environment: String,
        finalizationArtifactSha256: String,
        observedArtifactSha256: String,
        deploymentChannel: String,
        outcome: String,
        monitoringEvidence: String,
        observationWindowMinutes: Int
    ): Boolean =
        environment == "PRODUCTION" &&
            sha256.matches(finalizationArtifactSha256) &&
            sha256.matches(observedArtifactSha256) &&
            finalizationArtifactSha256.equals(observedArtifactSha256, ignoreCase = true) &&
            deploymentChannel in channels &&
            outcome in outcomes &&
            monitoringEvidence.trim().isNotEmpty() &&
            observationWindowMinutes >= 15
}
