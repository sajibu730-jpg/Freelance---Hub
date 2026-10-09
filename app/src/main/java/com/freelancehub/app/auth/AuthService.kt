package com.freelancehub.app.auth

import android.content.Context
import com.freelancehub.app.data.BackendConfig
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.data.UserRole
import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.TokenStore
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.net.HttpURLConnection

/** Provider-neutral production authentication client. It never authenticates locally. */
class AuthService(context: Context) {
    private val config: BackendConfig = RuntimeBackendConfig.current()
    private val tokenStore = TokenStore(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()

    fun isConfigured(): Boolean = config.isConfigured()

    fun authenticate(signup: Boolean, name: String, email: String, password: String, role: UserRole, callback: (Result<AuthSession>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(config.isConfigured()) { "Production backend is not configured." }
                val client = SecureApiClient(config)
                val payload = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                    if (signup) { put("display_name", name.trim()); put("role", role.name) }
                }.toString().toByteArray(StandardCharsets.UTF_8)
                val path = if (signup) "/auth/signup" else "/auth/login"
                val connection = client.openJsonPost(path, payload, idempotencyKey = SecureApiClient.newIdempotencyKey())
                val body = client.readJsonResponse(connection)
                val json = JSONObject(String(body, StandardCharsets.UTF_8))
                val access = json.getString("accessToken")
                val refresh = json.getString("refreshToken")
                tokenStore.saveSession(access, refresh)
                val user = json.getJSONObject("user")
                AuthSession(access, refresh, user.getString("email"), user.optString("display_name", user.optString("name", email.substringBefore("@"))), runCatching { UserRole.valueOf(user.optString("role", role.name)) }.getOrDefault(role))
            }
            callback(result)
        }
    }

    fun refresh(callback: (Result<AuthSession>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(config.isConfigured()) { "Production backend is not configured." }
                val refreshToken = tokenStore.readRefreshToken() ?: error("No refresh session available.")
                val client = SecureApiClient(config)
                val payload = JSONObject().put("refreshToken", refreshToken).toString().toByteArray(StandardCharsets.UTF_8)
                val connection = client.openJsonPost("/auth/refresh", payload, idempotencyKey = SecureApiClient.newIdempotencyKey())
                val json = JSONObject(String(client.readJsonResponse(connection), StandardCharsets.UTF_8))
                val access = json.getString("accessToken")
                val rotatedRefresh = json.optString("refreshToken", refreshToken)
                val user = json.getJSONObject("user")
                tokenStore.saveSession(access, rotatedRefresh)
                AuthSession(access, rotatedRefresh, user.getString("email"), user.optString("display_name", user.optString("name", user.getString("email").substringBefore("@"))), runCatching { UserRole.valueOf(user.optString("role", UserRole.FREELANCER.name)) }.getOrDefault(UserRole.FREELANCER))
            }
            callback(result)
        }
    }

    fun restoreSession(callback: (Result<AuthSession>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(config.isConfigured()) { "Production backend is not configured." }
                val access = tokenStore.readAccessToken() ?: error("No access session available.")
                val client = SecureApiClient(config)
                fun currentUser(token: String): AuthSession {
                    val connection = client.openGet("/me", token)
                    val json = JSONObject(String(client.readJsonResponse(connection), StandardCharsets.UTF_8))
                    val user = json.optJSONObject("user") ?: json
                    val email = user.getString("email")
                    val name = user.optString("display_name", user.optString("name", email.substringBefore("@")))
                    val role = runCatching { UserRole.valueOf(user.optString("role", UserRole.FREELANCER.name)) }.getOrDefault(UserRole.FREELANCER)
                    return AuthSession(token, tokenStore.readRefreshToken() ?: "", email, name, role)
                }
                runCatching { currentUser(access) }.getOrElse {
                    refreshBlocking()
                }
            }
            if (result.isFailure) tokenStore.clear()
            callback(result)
        }
    }

    private fun refreshBlocking(): AuthSession {
        val refreshToken = tokenStore.readRefreshToken() ?: error("No refresh session available.")
        val client = SecureApiClient(config)
        val payload = JSONObject().put("refreshToken", refreshToken).toString().toByteArray(StandardCharsets.UTF_8)
        val connection = client.openJsonPost("/auth/refresh", payload, idempotencyKey = SecureApiClient.newIdempotencyKey())
        val json = JSONObject(String(client.readJsonResponse(connection), StandardCharsets.UTF_8))
        val access = json.getString("accessToken")
        val rotatedRefresh = json.optString("refreshToken", refreshToken)
        val user = json.getJSONObject("user")
        tokenStore.saveSession(access, rotatedRefresh)
        val email = user.getString("email")
        val name = user.optString("display_name", user.optString("name", email.substringBefore("@")))
        val role = runCatching { UserRole.valueOf(user.optString("role", UserRole.FREELANCER.name)) }.getOrDefault(UserRole.FREELANCER)
        return AuthSession(access, rotatedRefresh, email, name, role)
    }

    fun logout(callback: ((Result<Unit>) -> Unit)? = null) {
        executor.execute {
            val result = runCatching {
                val access = tokenStore.readAccessToken()
                if (!config.isConfigured() || access.isNullOrBlank()) return@runCatching Unit
                val client = SecureApiClient(config)
                val connection = client.openJsonPost(
                    "/auth/logout",
                    ByteArray(0),
                    accessToken = access,
                    idempotencyKey = SecureApiClient.newIdempotencyKey()
                )
                client.readJsonResponse(connection)
            }
            tokenStore.clear()
            callback?.invoke(result.map { Unit })
        }
    }
}

data class AuthSession(val accessToken:String,val refreshToken:String,val email:String,val name:String,val role:UserRole)
