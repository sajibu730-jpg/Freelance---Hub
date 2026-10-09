package com.freelancehub.app.security

/**
 * User-visible platform-protection rules for marketplace communication.
 *
 * This is not surveillance: it only evaluates text the user is actively
 * attempting to send. Production enforcement must be duplicated server-side.
 */
object PlatformProtectionRules {
    enum class Action { ALLOW, WARN, BLOCK }

    data class Result(val action: Action, val reasons: List<String>)

    private val externalContactPatterns = listOf(
        Regex("""(?i)\b(?:https?://|www\.)\S+"""),
        Regex("""(?i)\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b"""),
        Regex("""(?<!\d)(?:\+?\d[\d ()-]{7,}\d)(?!\d)""")
    )

    private val offPlatformPaymentPatterns = listOf(
        Regex("""(?i)\b(?:paypal|payoneer|wise|western\s+union|crypto|bitcoin|usdt)\b"""),
        Regex("""(?i)\b(?:pay|payment|transfer)\s+(?:me|us)\s+(?:directly|outside|off[- ]platform)\b"""),
        Regex("""(?i)\b(?:send|pay)\s+(?:money|funds)\s+(?:directly|outside)\b""")
    )

    fun evaluate(message: String): Result {
        val text = message.trim()
        val validationError = MessageValidation.validate(text)
        if (validationError != null) return Result(Action.BLOCK, listOf(validationError))

        val reasons = buildList {
            if (externalContactPatterns.any { it.containsMatchIn(text) }) {
                add("External contact information or links may bypass platform protections")
            }
            if (offPlatformPaymentPatterns.any { it.containsMatchIn(text) }) {
                add("Off-platform payment language may bypass escrow and payment protections")
            }
        }

        return if (reasons.isEmpty()) Result(Action.ALLOW, emptyList())
        else Result(Action.WARN, reasons)
    }
}
