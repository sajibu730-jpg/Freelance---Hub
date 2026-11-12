package com.freelancehub.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class AdminConfigEntry(
    val key: String,
    val value: String,
    val isPublic: Boolean
)

class RemoteAdminRepository(private val config: BackendConfig) {
    fun isOwner(accessToken: String?): Boolean {
        if (accessToken.isNullOrBlank() || !config.isConfigured()) return false
        val rows = request(
            "GET",
            "/rest/v1/app_admins?select=role,is_active&limit=1",
            accessToken
        ) as JSONArray
        if (rows.length() == 0) return false
        val row = rows.optJSONObject(0) ?: return false
        return row.optString("role") == "owner" && row.optBoolean("is_active")
    }

    fun listConfig(accessToken: String): List<AdminConfigEntry> {
        val rows = request(
            "GET",
            "/rest/v1/app_remote_config?select=key,value,is_public&order=key.asc",
            accessToken
        ) as JSONArray
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { row ->
                AdminConfigEntry(
                    key = row.optString("key"),
                    value = row.opt("value")?.toString() ?: "null",
                    isPublic = row.optBoolean("is_public", true)
                )
            }
        }
    }

    fun saveConfig(accessToken: String, key: String, valueJson: String) {
        require(key.matches(Regex("^[a-z][a-z0-9_.-]{1,79}$"))) {
            "Use a valid setting key."
        }
        val value = runCatching { org.json.JSONTokener(valueJson).nextValue() }
            .getOrElse { error("Enter a valid JSON value.") }
        val payload = JSONArray().put(
            JSONObject()
                .put("key", key)
                .put("value", value)
                .put("is_public", true)
        ).toString()
        request(
            "POST",
            "/rest/v1/app_remote_config",
            accessToken,
            payload,
            "resolution=merge-duplicates,return=minimal"
        )
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String,
        body: String? = null,
        prefer: String? = null
    ): Any {
        val connection = URL(config.baseUrl.trimEnd('/') + path)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.setRequestProperty("apikey", config.anonKey)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (prefer != null) connection.setRequestProperty("Prefer", prefer)
                connection.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }.orEmpty()
                error("Owner request failed (HTTP $status): ${errorBody.take(180)}")
            }
            val response = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            return if (response.isBlank()) JSONArray() else JSONArray(response)
        } finally {
            connection.disconnect()
        }
    }
}
