package com.freelancehub.app.security

/** Safe client recovery decisions after an interrupted checkout. */
object CheckoutRecoveryRules {
    fun shouldRefresh(status: String): Boolean =
        status.uppercase() in setOf("PENDING", "UNKNOWN", "INTERRUPTED")

    fun canGrantAccess(status: String, paid: Boolean): Boolean =
        status.equals("PAID", true) && paid

    fun shouldShowRetry(status: String): Boolean =
        status.uppercase() in setOf("FAILED", "CANCELLED", "EXPIRED")

    fun isTerminal(status: String): Boolean =
        status.uppercase() in setOf("PAID", "FAILED", "CANCELLED", "EXPIRED")
}
