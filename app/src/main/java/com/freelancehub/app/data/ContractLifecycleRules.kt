package com.freelancehub.app.data

/** Client-side guardrails for rendering/initiating contract lifecycle actions.
 * Server authorization remains authoritative; these rules never grant access.
 */
object ContractLifecycleRules {
    const val ACTIVE = "ACTIVE"
    const val WORK_SUBMITTED = "WORK_SUBMITTED"
    const val CHANGES_REQUESTED = "CHANGES_REQUESTED"
    const val APPROVED = "APPROVED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val DISPUTED = "DISPUTED"

    fun canTransition(from: String, to: String): Boolean = when (from) {
        ACTIVE -> to in setOf(WORK_SUBMITTED, CANCELLED, DISPUTED)
        WORK_SUBMITTED -> to in setOf(CHANGES_REQUESTED, APPROVED, DISPUTED)
        CHANGES_REQUESTED -> to in setOf(WORK_SUBMITTED, DISPUTED)
        APPROVED -> to in setOf(COMPLETED, DISPUTED)
        DISPUTED -> to in setOf(ACTIVE, CANCELLED, COMPLETED)
        COMPLETED, CANCELLED -> false
        else -> false
    }

    fun requiresClientReview(status: String): Boolean =
        status == WORK_SUBMITTED || status == CHANGES_REQUESTED

    fun isTerminal(status: String): Boolean =
        status == COMPLETED || status == CANCELLED
}
