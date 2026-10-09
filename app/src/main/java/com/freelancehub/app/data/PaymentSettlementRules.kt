package com.freelancehub.app.data

/** Prevents impossible client-side settlement states. Server/database ledger is authoritative. */
object PaymentSettlementRules {
    fun settlementAllowed(paymentStatus: String, settlementStatus: String): Boolean {
        val p = paymentStatus.uppercase()
        val s = settlementStatus.uppercase()
        return when (s) {
            "UNSETTLED" -> p in setOf("PENDING", "FAILED", "CANCELLED", "EXPIRED")
            "SETTLED" -> p == "PAID"
            "REVERSED" -> p in setOf("REFUNDED", "REVERSED")
            else -> false
        }
    }

    fun refundAllowed(paymentStatus: String): Boolean =
        paymentStatus.uppercase() in setOf("PAID", "SETTLED")

    fun doubleSettlementBlocked(previous: String, next: String): Boolean =
        previous.equals("SETTLED", true) && next.equals("SETTLED", true)
}
