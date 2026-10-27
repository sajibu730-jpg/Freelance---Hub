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


    /**
     * Re-authenticate with the current password before any sensitive account change.
     * Email changes remain pending until the Supabase confirmation link is completed.
     */
    private fun reauthenticateBlocking(currentPassword: String): AuthSession {
        require(currentPassword.isNotBlank()) { "Enter your current password to verify your identity." }
        val currentUser = request(
            method = "GET",
            path = "/auth/v1/user",
            accessToken = tokenStore.readAccessToken()
        )
        val email = currentUser.optString("email")
        require(email.isNotBlank()) { "Could not verify the current account email." }

        val json = request(
            method = "POST",
            path = "/auth/v1/token?grant_type=password",
            body = JSONObject().put("email", email).put("password", currentPassword).toString()
        )
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val user = json.optJSONObject("user") ?: error("Identity verification failed.")
        require(access.isNotBlank() && refresh.isNotBlank()) {
            "Identity verification did not return a valid session."
        }
        val session = makeSession(access, refresh, user, UserRole.FREELANCER)
        tokenStore.saveSession(session.accessToken, session.refreshToken)
        return session
    }

    fun requestEmailChangeBlocking(newEmail: String, currentPassword: String) {
        require(newEmail.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            "Enter a valid new email address."
        }
        val session = reauthenticateBlocking(currentPassword)
        request(
            method = "PATCH",
            path = "/auth/v1/user",
            body = JSONObject().put("email", newEmail.trim()).toString(),
            accessToken = session.accessToken
        )
    }

    fun changePasswordBlocking(currentPassword: String, newPassword: String) {
        require(newPassword.length >= 8) { "Use at least 8 characters for the new password." }
        val session = reauthenticateBlocking(currentPassword)
        request(
            method = "PUT",
            path = "/auth/v1/user",
            body = JSONObject().put("password", newPassword).toString(),
            accessToken = session.accessToken
        )
    }

    fun requestPhoneChangeBlocking(phoneE164: String, currentPassword: String) {
        require(phoneE164.matches(Regex("^\\+[1-9][0-9]{6,14}$"))) {
            "Choose a country code and enter a valid mobile number."
        }
        val session = reauthenticateBlocking(currentPassword)
        request(
            method = "PUT",
            path = "/auth/v1/user",
            body = JSONObject().put("phone", phoneE164).toString(),
            accessToken = session.accessToken
        )
    }

    fun verifyPhoneChangeBlocking(phoneE164: String, otp: String) {
        require(otp.trim().matches(Regex("^[0-9]{4,8}$"))) {
            "Enter the verification code sent to your phone."
        }
        val json = request(
            method = "POST",
            path = "/auth/v1/verify",
            body = JSONObject()
                .put("type", "phone_change")
                .put("phone", phoneE164)
                .put("token", otp.trim())
                .toString()
        )
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val user = json.optJSONObject("user")
        if (access.isNotBlank() && refresh.isNotBlank() && user != null) {
            val session = makeSession(access, refresh, user, UserRole.FREELANCER)
            tokenStore.saveSession(session.accessToken, session.refreshToken)
        }
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
