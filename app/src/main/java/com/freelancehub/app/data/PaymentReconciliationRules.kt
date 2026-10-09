package com.freelancehub.app.data

/**
 * Client-visible reconciliation invariants. The production server/provider ledger remains authoritative.
 * These rules prevent the UI from accepting contradictory payment records.
 */
object PaymentReconciliationRules {
    fun providerAmountMatchesLedger(providerMinor: Long, ledgerMinor: Long): Boolean =
        providerMinor > 0L && ledgerMinor > 0L && providerMinor == ledgerMinor

    fun providerCurrencyMatchesLedger(provider: String, ledger: String): Boolean =
        provider.trim().uppercase() == ledger.trim().uppercase() && PaymentIntegrityRules.validCurrency(provider)

    fun eventNotAlreadyApplied(appliedEventIds: Set<String>, eventId: String): Boolean =
        PaymentWebhookRules.validEventId(eventId) && eventId !in appliedEventIds

    fun terminalPaymentStatus(status: String): Boolean =
        status.trim().uppercase() in setOf("PAID", "FAILED", "CANCELLED", "EXPIRED", "REFUNDED", "REVERSED")

    fun terminalStatusImmutable(current: String, incoming: String): Boolean {
        val c = current.trim().uppercase()
        val i = incoming.trim().uppercase()
        if (!terminalPaymentStatus(c)) return true
        return c == i || (c == "PAID" && i in setOf("REFUNDED", "REVERSED"))
    }
}
