package com.freelancehub.app.data

object ReleaseChangeControlRules {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val statuses = setOf("APPROVED", "BLOCKED", "REQUIRES_ACTION")

    fun canRecord(
        releaseVersion: String,
        changeSetSha256: String,
        approvalEvidence: String,
        testEvidence: String,
        rollbackPlanEvidence: String,
        securityReviewEvidence: String,
        status: String
    ): Boolean =
        releaseVersion == "4.200.0" &&
            sha256.matches(changeSetSha256) &&
            approvalEvidence.trim().isNotEmpty() &&
            testEvidence.trim().isNotEmpty() &&
            rollbackPlanEvidence.trim().isNotEmpty() &&
            securityReviewEvidence.trim().isNotEmpty() &&
            status in statuses
}
