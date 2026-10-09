package com.freelancehub.app.data

/** Client-side validation for server-issued payment receipts. The backend/provider remains authoritative. */
object PaymentReceiptRules {
    private val receiptId = Regex("^[A-Za-z0-9._:-]{8,200}$")
    private val providerRef = Regex("^[A-Za-z0-9._:-]{3,200}$")
    private val currency = Regex("^[A-Z]{3}$")

    fun validReceiptId(value: String): Boolean = receiptId.matches(value.trim())
    fun validProviderReference(value: String): Boolean = providerRef.matches(value.trim())
    fun validCurrency(value: String): Boolean = currency.matches(value.trim())
    fun validAmountMinor(value: Long): Boolean = value > 0L

    fun receiptStatusAllowed(value: String): Boolean =
        value.uppercase() in setOf("PENDING", "CONFIRMED", "FAILED", "REVERSED")

    fun unlockAllowed(status: String, paid: Boolean): Boolean =
        status.equals("CONFIRMED", true) && paid
}
