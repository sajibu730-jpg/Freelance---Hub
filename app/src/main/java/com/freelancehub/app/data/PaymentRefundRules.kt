package com.freelancehub.app.data

/** Refund lifecycle invariants. Server/provider authorization remains authoritative. */
object PaymentRefundRules {
    fun requestAllowed(paymentStatus: String, refundStatus: String): Boolean {
        val p = paymentStatus.uppercase()
        val r = refundStatus.uppercase()
        return when (r) {
            "NOT_REQUESTED" -> p in setOf("PAID", "SETTLED", "REFUNDED", "REVERSED")
            "REQUESTED" -> p in setOf("PAID", "SETTLED")
            "PROCESSING" -> p in setOf("PAID", "SETTLED")
            "COMPLETED" -> p in setOf("REFUNDED", "REVERSED")
            "REJECTED" -> p in setOf("PAID", "SETTLED")
            else -> false
        }
    }

    fun duplicateRefundBlocked(previous: String, next: String): Boolean =
        previous.uppercase() == "COMPLETED" && next.uppercase() == "COMPLETED"
}
