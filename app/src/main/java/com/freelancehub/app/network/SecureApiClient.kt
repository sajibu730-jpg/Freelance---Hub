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
    idempotencyKey: String? = null,
    preferRepresentation: Boolean = false
): HttpURLConnection {
    require(ApiRequestSecurityRules.canSendBody(body)) {
        "Request body is too large"
    }
    idempotencyKey?.let {
        require(ApiRequestSecurityRules.canUseIdempotencyKey(it)) {
            "Idempotency key is invalid"
        }
    }
    return open("PATCH", path, accessToken, body).also { connection ->
        connection.setRequestProperty(
            "Content-Type",
            "application/json; charset=utf-8"
        )
        if (preferRepresentation) {
            connection.setRequestProperty(
                "Prefer",
                "return=representation"
            )
        }
        idempotencyKey?.let {
            connection.setRequestProperty(
                SecurityHeaders.IDEMPOTENCY_KEY,
                it
            )
        }
        connection.doOutput = true
        connection.outputStream.use { it.write(body) }
    }
}


    fun openJsonPost(
        path: String,
        body: ByteArray,
        accessToken: String? = null,
        idempotencyKey: String? = null,
        preferRepresentation: Boolean = false
    ): HttpURLConnection {
        require(ApiRequestSecurityRules.canSendBody(body)) { "Request body is too large" }
        idempotencyKey?.let { require(ApiRequestSecurityRules.canUseIdempotencyKey(it)) { "Idempotency key is invalid" } }
        return open("POST", path, accessToken, body).also { connection ->
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (preferRepresentation) {
    connection.setRequestProperty("Prefer", "return=representation")
}
            if (preferRepresentation) {
    connection.setRequestProperty("Prefer", "return=representation")
}
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
        // Supabase project API key
config.anonKey.takeIf { it.isNotBlank() }?.let {
    connection.setRequestProperty("apikey", it)
}
        connection.setRequestProperty(SecurityHeaders.REQUEST_ID, UUID.randomUUID().toString())
        accessToken?.takeIf { ApiRequestSecurityRules.canSendAccessToken(it) }?.let {
            connection.setRequestProperty("Authorization", "Bearer ${it.trim()}")
        }
        return connection
    }

    fun uploadCoverPhoto(
        userId: String,
        imageBytes: ByteArray,
        contentType: String,
        accessToken: String
    ): String {
        require(userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Invalid user id"
        }
        require(contentType in listOf("image/jpeg", "image/png", "image/webp")) {
            "Only JPEG, PNG, and WebP images are allowed"
        }
        require(imageBytes.isNotEmpty() && imageBytes.size <= 5 * 1024 * 1024) {
            "Image must be between 1 byte and 5 MB"
        }

        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "webp"
        }
        val fileName = "${UUID.randomUUID()}.$extension"
        val objectPath = "$userId/$fileName"
        val requestPath = "/storage/v1/object/profile-covers/$objectPath"

        val connection = open("POST", requestPath, accessToken, imageBytes)
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("x-upsert", "false")
        connection.doOutput = true
        connection.outputStream.use { it.write(imageBytes) }
        readJsonResponse(connection)

        return "${config.baseUrl.trimEnd('/')}/storage/v1/object/public/profile-covers/$objectPath"
    }
    
    fun uploadProfilePhoto(
        userId: String,
        imageBytes: ByteArray,
        contentType: String,
        accessToken: String
    ): String {
        require(userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Invalid user id"
        }
        require(contentType in listOf("image/jpeg", "image/png", "image/webp")) {
            "Only JPEG, PNG, and WebP images are allowed"
        }
        require(imageBytes.isNotEmpty() && imageBytes.size <= 5 * 1024 * 1024) {
            "Image must be between 1 byte and 5 MB"
        }

        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "webp"
        }
        val objectPath = "$userId/${UUID.randomUUID()}.$extension"
        val connection = open(
            "POST",
            "/storage/v1/object/profile-photos/$objectPath",
            accessToken,
            imageBytes
        )
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("x-upsert", "false")
        connection.doOutput = true
        connection.outputStream.use { it.write(imageBytes) }
        readJsonResponse(connection)

        return objectPath
    }

    fun downloadProfilePhoto(
        objectPath: String,
        accessToken: String
    ): ByteArray {
        require(objectPath.matches(
            Regex("^[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.(jpg|png|webp)$")
        )) {
            "Invalid profile photo path"
        }

        val connection = open(
            "GET",
            "/storage/v1/object/profile-photos/$objectPath",
            accessToken,
            null
        )
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = connection.errorStream?.use {
                it.bufferedReader().use { reader -> reader.readText().take(300) }
            }.orEmpty()
            throw IllegalStateException(
                "Profile photo download failed: HTTP $status $error"
            )
        }

        val length = connection.getHeaderFieldLong("Content-Length", -1L)
        require(length <= 5L * 1024 * 1024) {
            "Profile photo exceeds 5 MB"
        }

        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 5 * 1024 * 1024) {
                    "Profile photo exceeds 5 MB"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        require(bytes.isNotEmpty()) { "Profile photo is empty" }
        return bytes
    }


    fun readJsonResponse(connection: HttpURLConnection): ByteArray {
        val status = connection.responseCode
        val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                val contentType = connection.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.take(80)
            ?: "missing"

        if (status !in 200..299) {
            val errorBody = connection.errorStream?.use { input ->
                input.bufferedReader().use { it.readText().take(500) }
            }.orEmpty()

            throw IllegalStateException(
                "API request rejected (${connection.url.path}): HTTP $status; " +
                    errorBody
            )
        }

        if (!com.freelancehub.app.security.ApiResponseSecurityRules.canAcceptJson(
                status, connection.contentType, contentLength
            )
        ) {
            val reason = when {
                status !in 200..299 -> "HTTP status $status"
                contentLength > com.freelancehub.app.security.ApiResponseSecurityRules.maxResponseBytes() ->
                    "response exceeds size limit"
                status != HttpURLConnection.HTTP_NO_CONTENT &&
                    contentType != "missing" &&
                    contentType != "application/json" &&
                    !contentType.endsWith("+json") -> "non-JSON Content-Type"
                else -> "response failed safety checks"
            }
            throw IllegalStateException(
                "API response rejected (${connection.url.path}): $reason; Content-Type=$contentType"
            )
        }
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
