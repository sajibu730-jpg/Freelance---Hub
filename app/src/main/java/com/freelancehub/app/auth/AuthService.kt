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
                    path = "/auth/v1/recover",
                    body = JSONObject().put("email", target).toString()
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
                require(otp.trim().matches(Regex("^[0-9]{6,8}$"))) {
                    "Enter the verification code from your message."
                }
                val payload = JSONObject().apply {
                    put("type", type)
                    put("token", otp.trim())
                    if (type == "email_change") put("email", target.trim())
                    else put("phone", target.trim())
                }
                request("POST", "/auth/v1/verify", payload.toString())

                val access = accessTokenForApiBlocking()
                    ?: error("Verification was accepted, but your session expired. Sign in again and check Account Security.")
                val savedUser = request(
                    method = "GET",
                    path = "/auth/v1/user",
                    accessToken = access
                )
                if (type == "email_change") {
                    val savedEmail = savedUser.optString("email").trim()
                    require(savedEmail.equals(target.trim(), ignoreCase = true)) {
                        "The code was accepted, but Supabase has not confirmed the new email as the active account email yet. Check both confirmation emails if prompted, then refresh Account Security."
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

    private fun makeSession(
        access: String,
        refresh: String,
        user: JSONObject,
        defaultRole: UserRole
    ): AuthSession {
        val email = user.optString("email")
        require(email.isNotBlank()) { "Supabase user email is missing." }

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
