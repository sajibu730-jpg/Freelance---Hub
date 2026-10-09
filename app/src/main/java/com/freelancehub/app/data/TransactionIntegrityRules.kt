package com.freelancehub.app.data

/** v48.901-v49.400 transactional integrity contracts. Server authorization remains authoritative. */
object TransactionIntegrityRules {
    private val safeIdempotencyKey = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
    private val stableReference = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun idempotencyKeyIsSafe(key: String): Boolean = safeIdempotencyKey.matches(key)
    fun referenceIsStable(reference: String): Boolean = stableReference.matches(reference)

    /** A replay of the same provider event must not create a second financial mutation. */
    fun duplicateEventMustBeNoOp(alreadyProcessed: Boolean): Boolean = alreadyProcessed

    /** Financial mutation requires a server-authorized transition and a stable idempotency key. */
    fun mayApplyFinancialMutation(serverAuthorized: Boolean, idempotencyKeyValid: Boolean): Boolean =
        serverAuthorized && idempotencyKeyValid

    /** Client and AI may never finalize a financial or contractual outcome. */
    fun clientMayFinalizeTransaction(): Boolean = false
    fun aiMayFinalizeTransaction(): Boolean = false

    /** Recovery may not silently convert a failed/destructive operation into success. */
    fun destructiveRecoveryRequiresExplicitServerAuthorization(): Boolean = true
}
