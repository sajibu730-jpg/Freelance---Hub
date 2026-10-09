package com.freelancehub.app.security

import com.freelancehub.app.data.UserRole

/** Account role is initialized once and is immutable for the lifetime of the local account.
 * Production backend identity/claims remain authoritative.
 */
object AccountRoleRules {
    fun canInitializeRole(isAlreadyInitialized: Boolean): Boolean = !isAlreadyInitialized

    fun roleForSave(currentRole: UserRole, requestedRole: UserRole, isAlreadyInitialized: Boolean): UserRole =
        if (isAlreadyInitialized) currentRole else requestedRole

    fun validRole(role: UserRole): Boolean = role == UserRole.CLIENT || role == UserRole.FREELANCER
}
