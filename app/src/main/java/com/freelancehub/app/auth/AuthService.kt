package com.freelancehub.app.auth

import android.content.Context
import com.freelancehub.app.data.BackendConfig
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.data.UserRole
import com.freelancehub.app.security.TokenStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class AuthService(context: Context) {

    private val config: BackendConfig = RuntimeBackendConfig.current()
    private val tokenStore = TokenStore(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()

    fun isConfigured(): Boolean =
        config.isConfigured() && config.anonKey.isNotBlank()

    fun authenticate(
        signup: Boolean,
        name: String,
        email: String,
        password: String,
        role: UserRole,
        callback: (Result<AuthSession>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                require(isConfigured()) {
                    "Supabase configuration is incomplete. Check the project URL and anon key."
                }
                require(email.trim().isNotBlank()) { "Enter your email address." }
                require(password.isNotBlank()) { "Enter your password." }

                val payload = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                    if (signup) {
                        put(
                            "data",
                            JSONObject().apply {
                                put("display_name", name.trim())
                                put("role", role.name)
                            }
                        )
                    }
                }

                val path = if (signup) {
                    "/auth/v1/signup"
                } else {
                    "/auth/v1/token?grant_type=password"
                }

                val json = request("POST", path, payload.toString())
                val access = json.optString("access_token")
                val refresh = json.optString("refresh_token")

                if (access.isBlank() || refresh.isBlank()) {
                    error(
                        if (signup) {
                            "Account created. Check your email to confirm it, then sign in."
                        } else {
                            "Supabase did not return a valid session."
                        }
                    )
                }

                val user = json.optJSONObject("user")
                    ?: error("Supabase response did not contain a user.")

                val session = makeSession(
                    access = access,
                    refresh = refresh,
                    user = user,
                    defaultRole = role
                )

                tokenStore.saveSession(session.accessToken, session.refreshToken)
                session
            }

            callback(result)
        }
    }

    /**
     * Starts signup without persisting an authenticated session. Supabase sends
     * its configured email confirmation OTP; phone verification is a separate step.
     */
    fun beginSignup(
        name: String,
        email: String,
        password: String,
        role: UserRole,
        country: String,
        gender: String,
        birthday: String,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                require(isConfigured()) { "Supabase configuration is incomplete." }
                require(name.trim().length in 2..80) { "Enter your full name." }
                require(email.trim().matches(Regex("""^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"""))) { "Enter a valid email address." }
                require(password.length >= 8) { "Use at least 8 characters for your password." }
                val metadata = JSONObject().apply {
                    put("display_name", name.trim())
                    put("role", role.name)
                    put("country", country.trim())
                    put("gender", gender.trim())
                    put("birthday", birthday.trim())
                }
                val payload = JSONObject().put("email", email.trim())
                    .put("password", password).put("data", metadata)
                request("POST", "/auth/v1/signup", payload.toString())
                Unit
            })
        }
    }

    /** Verifies the email signup OTP but deliberately keeps its session in memory only. */
    fun verifySignupEmail(
        email: String,
        code: String,
        callback: (Result<AuthSession>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                require(code.trim().matches(Regex("^[0-9]{6}$"))) { "Enter the 6-digit email verification code." }
                val json = request("POST", "/auth/v1/verify",
                    JSONObject().put("type", "signup").put("email", email.trim())
                        .put("token", code.trim()).toString())
                val access = json.optString("access_token")
                val refresh = json.optString("refresh_token")
                val user = json.optJSONObject("user") ?: error("Email verification did not return an account.")
                require(access.isNotBlank() && refresh.isNotBlank()) { "Email verified, but no secure session was returned. Please sign in." }
                makeSession(access, refresh, user, UserRole.FREELANCER)
            })
        }
    }

    /** Sends or verifies the 8-digit Twilio signup OTP using the email-verified bearer token. */
    fun signupMobileVerification(
        session: AuthSession,
        action: String,
        phone: String,
        code: String? = null,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                require(action == "send" || action == "verify") { "Invalid mobile verification action." }
                require(phone.matches(Regex("""^\\+[1-9][0-9]{7,14}$"""))) { "Enter the mobile number with its country code." }
                if (action == "verify") require(code.orEmpty().matches(Regex("^[0-9]{8}$"))) { "Enter the 8-digit SMS code." }
                val payload = JSONObject().put("action", action).put("phone", phone)
                if (!code.isNullOrBlank()) payload.put("code", code.trim())
                val connection = URL(config.baseUrl.trim().trimEnd('/') + "/functions/v1/signup-mobile-verification")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 25000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("apikey", config.anonKey)
                    connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val result = if (body.isBlank()) JSONObject() else JSONObject(body)
                    if (status !in 200..299) error(result.optString("error", "Mobile verification request failed."))
                    if (action == "verify") tokenStore.saveSession(session.accessToken, session.refreshToken)
                    Unit
                } finally { connection.disconnect() }
            })
        }
    }

    fun refresh(callback: (Result<AuthSession>) -> Unit) {
        executor.execute {
            callback(runCatching { refreshBlocking() })
        }
    }

    fun restoreSession(callback: (Result<AuthSession>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(isConfigured()) { "Supabase is not configured." }

                val access = tokenStore.readAccessToken()
                    ?: error("No saved session is available.")

                try {
                    val json = request(
                        method = "GET",
                        path = "/auth/v1/user",
                        accessToken = access
                    )
                    val refresh = tokenStore.readRefreshToken().orEmpty()
                    makeSession(
                        access = access,
                        refresh = refresh,
                        user = json,
                        defaultRole = UserRole.FREELANCER
                    )
                } catch (_: Exception) {
                    refreshBlocking()
                }
            }

            if (result.isFailure) tokenStore.clear()
            callback(result)
        }
    }

    @Synchronized
    fun accessTokenForApiBlocking(): String? {
        val access = tokenStore.readAccessToken() ?: return null

        val expiresAt = runCatching {
            val parts = access.split(".")
            require(parts.size == 3)

            val payloadBytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING
            )

            JSONObject(
                String(payloadBytes, StandardCharsets.UTF_8)
            ).optLong("exp", 0L)
        }.getOrDefault(0L)

        if (expiresAt == 0L) return access

        return if (
            expiresAt <= System.currentTimeMillis() / 1000L + 90L
        ) {
            refreshBlocking().accessToken
        } else {
            access
        }
    }

    private fun refreshBlocking(): AuthSession {
        require(isConfigured()) { "Supabase is not configured." }

        val refreshToken = tokenStore.readRefreshToken()
            ?: error("No refresh session is available. Please sign in again.")

        val json = request(
            method = "POST",
            path = "/auth/v1/token?grant_type=refresh_token",
            body = JSONObject()
                .put("refresh_token", refreshToken)
                .toString()
        )

        val access = json.optString("access_token")
        val rotatedRefresh = json.optString("refresh_token")
        val user = json.optJSONObject("user")
            ?: error("Supabase response did not contain a user.")

        require(access.isNotBlank() && rotatedRefresh.isNotBlank()) {
            "Supabase did not return refreshed session tokens."
        }

        val session = makeSession(
            access = access,
            refresh = rotatedRefresh,
            user = user,
            defaultRole = UserRole.FREELANCER
        )

        tokenStore.saveSession(session.accessToken, session.refreshToken)
        return session
    }

    fun logout(callback: ((Result<Unit>) -> Unit)? = null) {
        executor.execute {
            val result = runCatching {
                val access = tokenStore.readAccessToken()

                if (isConfigured() && !access.isNullOrBlank()) {
                    request(
                        method = "POST",
                        path = "/auth/v1/logout",
                        accessToken = access
                    )
                }
            }

            tokenStore.clear()
            callback?.invoke(result.map { Unit })
        }
    }

    /**
     * Requests a Supabase Auth change. Supabase sends the confirmation to the
     * configured email/SMS provider; this method never treats a request as verified.
     */
    /**
     * Sends Supabase's password-recovery email. The user must follow the
     * verified recovery link before choosing a new password.
     */
    fun requestPasswordReset(email: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(isConfigured()) { "Password recovery is unavailable because the backend is not configured." }
                val target = email.trim()
                require(target.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))) {
                    "Enter the email address for your account first."
                }
                request(
                    method = "POST",
                    path = "/auth/v1/recover?redirect_to=freelancehub%3A%2F%2Fauth%2Freset",
                    body = JSONObject().put("email", target).toString()
                )
                Unit
            })
        }
    }


    /**
     * Sends a one-time recovery code to the registered email or phone.
     * Phone delivery requires Supabase Phone Auth and a configured SMS provider.
     */
    fun requestRecoveryCode(identifier: String, isPhone: Boolean, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(isConfigured()) { "Account recovery is currently unavailable. Please try again later." }
                val target = identifier.trim()
                if (isPhone) {
                    require(target.matches(Regex("""^\+[1-9][0-9]{7,14}$"""))) {
                        "Enter the mobile number with its country code, for example +9715XXXXXXXX."
                    }
                    mobileRecoveryRequest("send", target)
                } else {
                    require(target.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))) {
                        "Enter the email address registered to your account."
                    }
                    request(
                        method = "POST",
                        path = "/auth/v1/recover",
                        body = JSONObject().put("email", target).toString()
                    )
                }
                Unit
            })
        }
    }

    /** Verifies the recovery OTP and stores the authenticated session on this device. */
    fun verifyRecoveryCode(
        identifier: String,
        otp: String,
        isPhone: Boolean,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                val expectedLength = 8
                require(otp.trim().matches(Regex("^[0-9]{$expectedLength}$"))) {
                    "Enter the $expectedLength-digit code sent to your registered contact."
                }
                val target = identifier.trim()
                val response = if (isPhone) {
                    val verified = mobileRecoveryRequest("verify", target, otp.trim())
                    val tokenHash = verified.optString("token_hash")
                    require(tokenHash.isNotBlank()) {
                        "The mobile number was verified, but a secure recovery session was not returned. Please request a new code."
                    }
                    request(
                        "POST",
                        "/auth/v1/verify",
                        JSONObject()
                            .put("token_hash", tokenHash)
                            .put("type", "recovery")
                            .toString()
                    )
                } else {
                    request(
                        "POST",
                        "/auth/v1/verify",
                        JSONObject()
                            .put("type", "recovery")
                            .put("token", otp.trim())
                            .put("email", target)
                            .toString()
                    )
                }
                val access = response.optString("access_token")
                val refresh = response.optString("refresh_token")
                val user = response.optJSONObject("user")
                    ?: error("Verification succeeded without an account session. Request a new code.")
                require(access.isNotBlank() && refresh.isNotBlank()) {
                    "Supabase did not return a valid login session. Please request a new code."
                }
                val session = makeSession(
                    access = access,
                    refresh = refresh,
                    user = user,
                    defaultRole = UserRole.FREELANCER
                )
                tokenStore.saveSession(session.accessToken, session.refreshToken)
                Unit
            })
        }
    }

    /** Optional password change after successful recovery-code verification. */
    fun updateVerifiedRecoveryPassword(newPassword: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(newPassword.length >= 8) { "Use at least 8 characters for your new password." }
                val access = accessTokenForApiBlocking()
                    ?: error("Your verified recovery session has expired. Request a new code.")
                request(
                    method = "PUT",
                    path = "/auth/v1/user",
                    body = JSONObject().put("password", newPassword).toString(),
                    accessToken = access
                )
                Unit
            })
        }
    }

    /** Updates a password using the short-lived token returned by a verified recovery link. */
    fun updateRecoveryPassword(accessToken: String, newPassword: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(accessToken.isNotBlank()) { "The recovery link is missing its access token. Request a new reset email." }
                require(newPassword.length >= 8) { "Use at least 8 characters for your new password." }
                request(
                    method = "PUT",
                    path = "/auth/v1/user",
                    body = JSONObject().put("password", newPassword).toString(),
                    accessToken = accessToken
                )
                Unit
            })
        }
    }

    fun requestEmailChange(newEmail: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(newEmail.trim().contains("@")) { "Enter a valid email address." }
                val access = accessTokenForApiBlocking()
                    ?: error("Please sign in again before changing your email.")
                request(
                    method = "PUT",
                    path = "/auth/v1/user",
                    body = JSONObject().put("email", newEmail.trim()).toString(),
                    accessToken = access
                )
                Unit
            })
        }
    }

    fun requestPhoneChange(newPhone: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(newPhone.matches(Regex("""^\+[1-9][0-9]{7,14}$"""))) {
                    "Enter a valid phone number with country code, such as +9715XXXXXXXX."
                }
                val access = accessTokenForApiBlocking()
                    ?: error("Please sign in again before changing your phone number.")
                request(
                    method = "PUT",
                    path = "/auth/v1/user",
                    body = JSONObject().put("phone", newPhone).toString(),
                    accessToken = access
                )
                Unit
            })
        }
    }

    /**
     * Verifies the OTP delivered by Supabase for a supported account change.
     * The exact type and contact must match the pending change and Auth settings.
     */
    /**
     * Verifies both confirmation codes when Supabase Secure Email Change is enabled.
     * One code is sent to the current email and another to the requested new email.
     */
    fun verifyEmailChange(
        currentEmail: String,
        newEmail: String,
        currentEmailOtp: String,
        newEmailOtp: String,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                require(currentEmail.trim().contains("@") && newEmail.trim().contains("@")) {
                    "A valid current and new email address are required."
                }
                require(currentEmailOtp.trim().matches(Regex("^[0-9]{6,8}$")) &&
                    newEmailOtp.trim().matches(Regex("^[0-9]{6,8}$"))) {
                    "Enter both verification codes from your email messages."
                }

                val currentVerification = request(
                    "POST",
                    "/auth/v1/verify",
                    JSONObject()
                        .put("type", "email_change")
                        .put("email", currentEmail.trim())
                        .put("token", currentEmailOtp.trim())
                        .toString()
                )
                persistVerifiedSession(currentVerification)

                val newVerification = request(
                    "POST",
                    "/auth/v1/verify",
                    JSONObject()
                        .put("type", "email_change")
                        .put("email", newEmail.trim())
                        .put("token", newEmailOtp.trim())
                        .toString()
                )
                persistVerifiedSession(newVerification)

                val access = accessTokenForApiBlocking()
                    ?: error("Email verification was accepted, but your session expired. Sign in again and check Account Security.")
                val savedUser = request(
                    method = "GET",
                    path = "/auth/v1/user",
                    accessToken = access
                )
                require(savedUser.optString("email").trim().equals(newEmail.trim(), ignoreCase = true)) {
                    "Both email confirmations must be completed before the new address becomes active. Check both inboxes and enter each code."
                }
                Unit
            })
        }
    }

    /**
     * Verifies the OTP delivered by Supabase for a supported account change.
     * The exact type and contact must match the pending change and Auth settings.
     */
    fun verifyAccountChange(
        type: String,
        target: String,
        otp: String,
        callback: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            callback(runCatching {
                require(type == "email_change" || type == "phone_change") {
                    "Unsupported verification type."
                }
                require(otp.trim().matches(Regex("^[0-9]{6}$"))) {
                    "Enter the verification code from your message."
                }
                val payload = JSONObject().apply {
                    put("type", type)
                    put("token", otp.trim())
                    if (type == "email_change") put("email", target.trim())
                    else put("phone", target.trim())
                }
                val verification = request("POST", "/auth/v1/verify", payload.toString())
                persistVerifiedSession(verification)

                val access = accessTokenForApiBlocking()
                    ?: error("Verification was accepted, but your session expired. Sign in again and check Account Security.")
                val savedUser = request(
                    method = "GET",
                    path = "/auth/v1/user",
                    accessToken = access
                )
                if (type == "email_change") {
                    require(savedUser.optString("email").trim().equals(target.trim(), ignoreCase = true)) {
                        "The code was accepted, but the new email is not active yet. Complete both email confirmations if Secure Email Change is enabled."
                    }
                } else {
                    val savedPhone = savedUser.optString("phone").trim()
                    require(savedPhone == target.trim()) {
                        "The code was accepted, but Supabase has not confirmed the new phone number as the active account number yet. Check the SMS code and refresh Account Security before trying again."
                    }
                }
                Unit
            })
        }
    }

    private fun persistVerifiedSession(response: JSONObject) {
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        val user = response.optJSONObject("user")
        if (access.isNotBlank() && refresh.isNotBlank() && user != null) {
            val session = makeSession(
                access = access,
                refresh = refresh,
                user = user,
                defaultRole = UserRole.FREELANCER
            )
            tokenStore.saveSession(session.accessToken, session.refreshToken)
        }
    }

    /**
     * Password updates are deliberately separate from contact-change verification.
     * The caller must only expose this after its required verification step.
     */
    fun updatePassword(currentPassword: String, newPassword: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(currentPassword.isNotBlank()) { "Enter your current password to verify this change." }
                require(newPassword.length >= 8) { "Use at least 8 characters for your password." }
                val access = accessTokenForApiBlocking()
                    ?: error("Please sign in again before changing your password.")
                request(
                    method = "PUT",
                    path = "/auth/v1/user",
                    body = JSONObject()
                        .put("current_password", currentPassword)
                        .put("password", newPassword)
                        .toString(),
                    accessToken = access
                )
                Unit
            })
        }
    }

    /** Reads the server-controlled mobile recovery feature flag. Fails closed. */
    fun isMobileRecoveryEnabled(callback: (Result<Boolean>) -> Unit) {
        executor.execute {
            callback(runCatching {
                require(isConfigured()) { "Backend is not configured." }
                val url = URL(
                    config.baseUrl.trim().trimEnd('/') +
                        "/rest/v1/app_feature_flags?key=eq.mobile_recovery_enabled&select=enabled"
                )
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("apikey", config.anonKey)
                    connection.setRequestProperty("Authorization", "Bearer " + config.anonKey)
                    val status = connection.responseCode
                    if (status !in 200..299) error("Feature settings could not be loaded.")
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val rows = org.json.JSONArray(response)
                    rows.length() > 0 && rows.optJSONObject(0)?.optBoolean("enabled", false) == true
                } finally {
                    connection.disconnect()
                }
            })
        }
    }

    private fun mobileRecoveryRequest(
        action: String,
        phone: String,
        code: String? = null
    ): JSONObject {
        require(isConfigured()) { "Account recovery is unavailable because the backend is not configured." }
        require(action == "send" || action == "verify") { "Invalid mobile recovery action." }

        val payload = JSONObject()
            .put("action", action)
            .put("phone", phone)
        if (!code.isNullOrBlank()) payload.put("code", code)

        val connection = URL(config.baseUrl.trim().trimEnd('/') + "/functions/v1/mobile-recovery")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", config.anonKey)
            connection.setRequestProperty("Authorization", "Bearer " + config.anonKey)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024 * 1024) { "Recovery response is too large." }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }.orEmpty()

            val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (status !in 200..299) {
                error(response.optString("error", "Mobile recovery request failed."))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun makeSession(
        access: String,
        refresh: String,
        user: JSONObject,
        defaultRole: UserRole
    ): AuthSession {
        val email = user.optString("email").ifBlank { user.optString("phone") }
        require(email.isNotBlank()) { "Supabase user contact information is missing." }

        val metadata = user.optJSONObject("user_metadata")
        val name = metadata?.optString("display_name")
            ?.takeIf { !it.isNullOrBlank() }
            ?: email.substringBefore("@")

        val roleName = metadata?.optString("role")
            ?.takeIf { !it.isNullOrBlank() }

        val role = runCatching {
            UserRole.valueOf(roleName ?: defaultRole.name)
        }.getOrDefault(defaultRole)

        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            email = email,
            name = name,
            role = role
        )
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        accessToken: String? = null
    ): JSONObject {
        require(isConfigured()) { "Supabase configuration is incomplete." }
        require(path.startsWith("/auth/v1/")) { "Invalid authentication path." }

        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", config.anonKey)

            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $accessToken"
                )
            }

            if (body != null) {
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                connection.doOutput = true
                connection.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break

                    total += count
                    require(total <= 1024 * 1024) {
                        "Supabase response is too large."
                    }

                    output.write(buffer, 0, count)
                }

                String(output.toByteArray(), StandardCharsets.UTF_8)
            }.orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    val errorJson = JSONObject(response)
                    errorJson.optString(
                        "msg",
                        errorJson.optString(
                            "message",
                            errorJson.optString(
                                "error_description",
                                errorJson.optString("error", "Request failed")
                            )
                        )
                    )
                }.getOrDefault("Request failed")

                if (
                    path == "/auth/v1/user" &&
                    method == "PUT" &&
                    (message.contains("already been registered", ignoreCase = true) ||
                        message.contains("email_exists", ignoreCase = true))
                ) {
                    error("This email address is already registered to another account. Use an email address that is not linked to an existing account.")
                }

                error("Supabase HTTP $status: $message")
            }

            if (response.isBlank()) return JSONObject()
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
    val name: String,
    val role: UserRole
)
