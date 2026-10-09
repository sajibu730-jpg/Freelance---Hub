package com.freelancehub.app.data

object TrustResilienceAuditRules {
    private val requiredFields = setOf("event_id", "actor_id", "event_type", "occurred_at", "request_id", "schema_version")
    private val sensitiveEvents = setOf("PAYMENT", "REFUND", "DISPUTE", "ACCOUNT_CLOSURE", "ROLE_CHANGE", "BYPASS_ENFORCEMENT", "SESSION_REVOCATION")

    fun hasRequiredFields(fields: Set<String>): Boolean = requiredFields.all(fields::contains)
    fun requiresAudit(eventType: String): Boolean = eventType in sensitiveEvents
    fun stableErrorCode(code: String): Boolean = code.matches(Regex("^[A-Z][A-Z0-9_]{2,63}$"))
}
