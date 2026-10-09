package com.freelancehub.app.data

/** Fail-closed source-level continuity checks for the current release audit. */
object ReleaseAuditContinuityRules {
    fun isValid(
        releaseVersion: String,
        versionCode: Int,
        sourceGate: String,
        externalExecutionClaimed: Boolean
    ): Boolean =
        releaseVersion == "5.100.0" &&
        versionCode == 2310 &&
        sourceGate == "verify_v5100_release_audit_gate.sh" &&
        !externalExecutionClaimed
}
