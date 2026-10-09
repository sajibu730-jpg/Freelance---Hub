package com.freelancehub.app.security

/**
 * Client-side invariants for account-closure identity reuse.
 * Production identity uniqueness, banned-identity storage and appeals remain server-side.
 * The client receives only an allow/deny decision; it does not store a banned identity registry.
 */
object AccountClosureIdentityRules {
    enum class RegistryState { UNKNOWN, ACTIVE, CLOSED, BANNED, REINSTATED }

    fun normalizedIdentityKey(input: String): String? {
        val value = input.trim().lowercase()
        if (value.length !in 8..320) return null
        if (value.any { it.isWhitespace() }) return null
        return value
    }

    fun mayRegister(state: RegistryState): Boolean =
        state == RegistryState.UNKNOWN || state == RegistryState.REINSTATED

    fun mayRecreateAfterClosure(state: RegistryState): Boolean =
        state == RegistryState.UNKNOWN || state == RegistryState.REINSTATED

    fun requiresServerDecision(state: RegistryState): Boolean =
        state == RegistryState.CLOSED || state == RegistryState.BANNED
}
