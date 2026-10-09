package com.freelancehub.app.data

/** Pure dispute/refund domain guards. Final authorization and money movement stay server-side. */
object DisputeRefundRules {
    fun validReason(value: String): Boolean = value.trim().length in 10..2000
    fun validEvidenceRef(value: String): Boolean = value.trim().length in 1..500

    fun disputeTransitionAllowed(from: String, to: String): Boolean = when (from to to) {
        "OPEN" to "UNDER_REVIEW",
        "OPEN" to "WITHDRAWN",
        "UNDER_REVIEW" to "RESOLVED_REFUND",
        "UNDER_REVIEW" to "RESOLVED_RELEASE",
        "UNDER_REVIEW" to "DISMISSED" -> true
        else -> false
    }

    fun refundTransitionAllowed(from: String, to: String): Boolean = when (from to to) {
        "REQUESTED" to "APPROVED",
        "REQUESTED" to "REJECTED",
        "APPROVED" to "PROCESSING",
        "PROCESSING" to "COMPLETED",
        "PROCESSING" to "FAILED" -> true
        else -> false
    }

    fun refundAmountAllowed(requestedMinor: Long, paymentAmountMinor: Long): Boolean =
        requestedMinor > 0L && paymentAmountMinor > 0L && requestedMinor <= paymentAmountMinor
}
