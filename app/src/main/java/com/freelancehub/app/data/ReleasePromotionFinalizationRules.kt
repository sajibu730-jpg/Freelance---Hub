package com.freelancehub.app.data

/** Deterministic guard for post-promotion finalization evidence. */
object ReleasePromotionFinalizationRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val channels = setOf("PLAY_INTERNAL", "PLAY_CLOSED", "PLAY_PRODUCTION", "CONTROLLED_ROLLOUT")

    fun isSha256Valid(value: String): Boolean = sha256.matches(value)
    fun isChannelValid(value: String): Boolean = value in channels

    fun canFinalize(
        environment: String,
        executionArtifactSha256: String,
        finalArtifactSha256: String,
        deploymentChannel: String,
        verificationDecision: String,
        verificationEvidence: String
    ): Boolean =
        environment == "PRODUCTION" &&
            isSha256Valid(executionArtifactSha256) &&
            isSha256Valid(finalArtifactSha256) &&
            executionArtifactSha256.equals(finalArtifactSha256, ignoreCase = true) &&
            isChannelValid(deploymentChannel) &&
            verificationDecision in setOf("VERIFIED", "BLOCKED", "ROLLED_BACK") &&
            verificationEvidence.trim().isNotEmpty()
}
