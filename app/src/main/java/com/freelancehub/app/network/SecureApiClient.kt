package com.freelancehub.app.network

import com.freelancehub.app.data.BackendConfig
import com.freelancehub.app.data.SecurityHeaders
import com.freelancehub.app.security.ApiRequestSecurityRules
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Small provider-neutral HTTPS transport foundation.
 * Authentication/business mapping stays outside this class so secrets are never embedded in the APK.
 */
class SecureApiClient(private val config: BackendConfig) {
    init {
        val base = URI(config.baseUrl.trim())
        require(base.scheme.equals("https", true) && !base.host.isNullOrBlank()) {
            "Production API must use HTTPS and include a host"
        }
        require(base.userInfo.isNullOrBlank() && base.fragment.isNullOrBlank()) {
            "API base URL must not contain user-info or fragments"
        }
    }

    fun openGet(path: String, accessToken: String? = null): HttpURLConnection {
        return open("GET", path, accessToken, null)
    }

    fun openJsonPatch(
        path: String,
        body: ByteArray,
        accessToken: String? = null,
        idempotencyKey: String? = null
    ): HttpURLConnection {
        require(ApiRequestSecurityRules.canSendBody(body)) { "Request body is too large" }
        idempotencyKey?.let { require(ApiRequestSecurityRules.canUseIdempotencyKey(it)) { "Idempotency key is invalid" } }
        return open("PATCH", path, accessToken, body).also { connection ->
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            idempotencyKey?.let { connection.setRequestProperty(SecurityHeaders.IDEMPOTENCY_KEY, it) }
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
    }

    fun openJsonPost(
        path: String,
        body: ByteArray,
        accessToken: String? = null,
        idempotencyKey: String? = null
    ): HttpURLConnection {
        require(ApiRequestSecurityRules.canSendBody(body)) { "Request body is too large" }
        idempotencyKey?.let { require(ApiRequestSecurityRules.canUseIdempotencyKey(it)) { "Idempotency key is invalid" } }
        return open("POST", path, accessToken, body).also { connection ->
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            idempotencyKey?.let { connection.setRequestProperty(SecurityHeaders.IDEMPOTENCY_KEY, it) }
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
    }

    private fun open(method: String, path: String, accessToken: String?, body: ByteArray?): HttpURLConnection {
        require(com.freelancehub.app.data.Validation.apiPath(path)) {
            "API path is invalid"
        }
        val url = URI(config.baseUrl.trimEnd('/') + path).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        // Do not transparently follow redirects: a backend must not be able to
        // downgrade an HTTPS API request to another scheme/host without a fresh
        // application-level validation step.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(SecurityHeaders.REQUEST_ID, UUID.randomUUID().toString())
        accessToken?.takeIf { ApiRequestSecurityRules.canSendAccessToken(it) }?.let {
            connection.setRequestProperty("Authorization", "Bearer ${it.trim()}")
        }
        return connection
    }

    fun readJsonResponse(connection: HttpURLConnection): ByteArray {
        val status = connection.responseCode
        val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
        require(com.freelancehub.app.security.ApiResponseSecurityRules.canAcceptJson(
            status, connection.contentType, contentLength
        )) { "Unexpected or unsafe API response" }
        if (status == HttpURLConnection.HTTP_NO_CONTENT) return ByteArray(0)
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: throw IllegalStateException("API request failed with HTTP $status")
        val maxBytes = com.freelancehub.app.security.ApiResponseSecurityRules.maxResponseBytes()
        val body = stream.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("API response is too large")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        require(com.freelancehub.app.security.ApiResponseSecurityRules.canAcceptBodySize(body.size)) {
            "API response is too large"
        }
        if (status !in 200..299) throw IllegalStateException("API request failed with HTTP $status: ${String(body, java.nio.charset.StandardCharsets.UTF_8).take(500)}")
        return body
    }

    companion object {
        fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }
}
