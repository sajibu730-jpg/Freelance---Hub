package com.freelancehub.app.data

/** Pure payment-domain validation and lifecycle rules. Provider/webhook handling stays server-side. */
object PaymentRules {
    private val currency = Regex("^[A-Z]{3}$")
    private val idempotency = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")

    fun validCurrency(value: String): Boolean = currency.matches(value.trim())
    fun validAmountMinor(value: Long): Boolean = value > 0L
    fun validIdempotencyKey(value: String): Boolean = idempotency.matches(value.trim())

    fun escrowAllowed(from: String, to: String): Boolean = when (from to to) {
        "PENDING" to "FUNDED" -> true
        "FUNDED" to "RELEASE_REQUESTED" -> true
        "RELEASE_REQUESTED" to "RELEASED" -> true
        "FUNDED" to "REFUNDED" -> true
        "PENDING" to "CANCELLED" -> true
        "FUNDED" to "DISPUTED" -> true
        "RELEASE_REQUESTED" to "DISPUTED" -> true
        "DISPUTED" to "REFUNDED" -> true
        "DISPUTED" to "RELEASED" -> true
        else -> false
    }

    fun ledgerAllowed(direction: String, entryType: String): Boolean = when (direction.uppercase() to entryType.uppercase()) {
        "CREDIT" to "FUND", "CREDIT" to "RELEASE", "CREDIT" to "REFUND", "CREDIT" to "ADJUSTMENT" -> true
        "DEBIT" to "HOLD", "DEBIT" to "RELEASE", "DEBIT" to "REFUND", "DEBIT" to "FEE", "DEBIT" to "ADJUSTMENT" -> true
        else -> false
    }
}
