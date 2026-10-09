package com.freelancehub.app.data

/** Client-side payment/escrow invariants. Server/provider authorization remains authoritative. */
object PaymentIntegrityRules {
    private val currency = Regex("^[A-Z]{3}$")
    private val idempotency = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")

    fun validCurrency(value: String): Boolean = currency.matches(value.trim())
    fun validAmountMinor(value: Long): Boolean = value > 0L
    fun validIdempotencyKey(value: String): Boolean = idempotency.matches(value.trim())
    fun amountMatchesContract(amountMinor: Long, agreedBudgetMinor: Long): Boolean =
        amountMinor > 0L && agreedBudgetMinor > 0L && amountMinor == agreedBudgetMinor

    fun statusTransitionAllowed(from: String, to: String): Boolean = when (from to to) {
        "PENDING" to "FUNDED", "PENDING" to "CANCELLED" -> true
        "FUNDED" to "RELEASE_REQUESTED", "FUNDED" to "REFUNDED", "FUNDED" to "DISPUTED" -> true
        "RELEASE_REQUESTED" to "RELEASED", "RELEASE_REQUESTED" to "DISPUTED" -> true
        "DISPUTED" to "REFUNDED", "DISPUTED" to "RELEASED" -> true
        else -> false
    }

    fun ledgerEntryAllowed(direction: String, entryType: String): Boolean = when (direction.uppercase() to entryType.uppercase()) {
        "CREDIT" to "FUND", "CREDIT" to "RELEASE", "CREDIT" to "REFUND", "CREDIT" to "ADJUSTMENT" -> true
        "DEBIT" to "HOLD", "DEBIT" to "RELEASE", "DEBIT" to "REFUND", "DEBIT" to "FEE", "DEBIT" to "ADJUSTMENT" -> true
        else -> false
    }
}
