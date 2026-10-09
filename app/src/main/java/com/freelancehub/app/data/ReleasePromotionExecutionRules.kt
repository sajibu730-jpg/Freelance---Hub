package com.freelancehub.app.data

/** Deterministic guard for production promotion execution evidence. */
object ReleasePromotionExecutionRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val channels = setOf("PLAY_INTERNAL", "PLAY_CLOSED", "PLAY_PRODUCTION", "CONTROLLED_ROLLOUT")

    fun isSha256Valid(value: String): Boolean = sha256.matches(value)
    fun isChannelValid(value: String): Boolean = value in channels

    fun canExecute(
        environment: String,
        artifactSha256: String,
        migrationSha256: String,
        sourceTreeSha256: String,
        deploymentChannel: String,
        executionDecision: String,
        approvalEvidence: String
    ): Boolean =
        environment == "PRODUCTION" &&
            isSha256Valid(artifactSha256) &&
            isSha256Valid(migrationSha256) &&
            isSha256Valid(sourceTreeSha256) &&
            isChannelValid(deploymentChannel) &&
            executionDecision == "EXECUTE" &&
            approvalEvidence.trim().isNotEmpty()
}
