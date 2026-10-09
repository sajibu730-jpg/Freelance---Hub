package com.freelancehub.app.data

/** v47.601-v48.000 source-level closure invariants. No live infrastructure is implied. */
object ProductionClosureRules {
    private val stableCode = Regex("^[A-Z][A-Z0-9_]{2,63}$")
    private val idempotencyKey = Regex("^[A-Za-z0-9._:-]{8,128}$")

    fun hasStableErrorCode(code: String): Boolean = stableCode.matches(code)
    fun hasSafeIdempotencyKey(key: String): Boolean = idempotencyKey.matches(key)

    /** Financial/account authorization must remain server-controlled. */
    fun clientMayFinalizePrivilegedOperation(operation: String): Boolean = false

    /** AI analysis is advisory and cannot finalize jobs, payments, refunds or enforcement. */
    fun aiMayFinalize(operation: String): Boolean = false

    /** Destructive recovery actions require an explicit server-side authorization path. */
    fun allowsOfflineDestructiveRecovery(operation: String): Boolean = false
}
