package com.freelancehub.app.data

/** Pure client-side financial safety checks. The backend remains authoritative. */
object FinancialSafetyRules {
    private val currency = Regex("^[A-Z]{3}$")
    private val payoutReference = Regex("^[A-Za-z0-9._:-]{3,160}$")
    private val reasonCode = Regex("^[A-Z0-9_]{3,40}$")

    fun validCurrency(value: String): Boolean = currency.matches(value.trim())
    fun validPositiveMinorAmount(value: Long): Boolean = value > 0L
    fun validPayoutReference(value: String): Boolean = payoutReference.matches(value.trim())
    fun validDisputeReason(value: String): Boolean = reasonCode.matches(value.trim())

    fun payoutTransitionAllowed(from: String, to: String): Boolean = when (from.uppercase() to to.uppercase()) {
        "REQUESTED" to "PROCESSING" -> true
        "PROCESSING" to "PAID" -> true
        "PROCESSING" to "FAILED" -> true
        "REQUESTED" to "CANCELLED" -> true
        "FAILED" to "CANCELLED" -> true
        else -> false
    }

    fun disputeTransitionAllowed(from: String, to: String): Boolean = when (from.uppercase() to to.uppercase()) {
        "OPEN" to "UNDER_REVIEW" -> true
        "UNDER_REVIEW" to "RESOLVED_RELEASE" -> true
        "UNDER_REVIEW" to "RESOLVED_REFUND" -> true
        "RESOLVED_RELEASE" to "CLOSED" -> true
        "RESOLVED_REFUND" to "CLOSED" -> true
        else -> false
    }

    fun webhookOutcomeRetryable(outcome: String): Boolean = outcome.uppercase() == "RETRYABLE_FAILURE"
}
