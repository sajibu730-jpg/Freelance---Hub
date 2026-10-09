package com.freelancehub.app.data

/** Provider webhook event invariants. Signature verification must happen server-side with provider secrets. */
object PaymentWebhookRules {
    private val id = Regex("^[A-Za-z0-9._:-]{8,200}$")

    fun validEventId(value: String): Boolean = id.matches(value.trim())
    fun validCheckoutId(value: String): Boolean = id.matches(value.trim())
    fun validEventType(value: String): Boolean =
        value.trim().uppercase() in setOf("PAYMENT_CONFIRMED", "PAYMENT_FAILED", "PAYMENT_REFUNDED", "PAYMENT_REVERSED")

    fun statusMatchesEvent(eventType: String, paymentStatus: String): Boolean = when (eventType.trim().uppercase()) {
        "PAYMENT_CONFIRMED" -> paymentStatus.equals("PAID", true)
        "PAYMENT_FAILED" -> paymentStatus.equals("FAILED", true)
        "PAYMENT_REFUNDED" -> paymentStatus.equals("REFUNDED", true)
        "PAYMENT_REVERSED" -> paymentStatus.equals("REVERSED", true)
        else -> false
    }
}
