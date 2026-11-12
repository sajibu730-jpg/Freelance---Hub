package com.freelancehub.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Downloads safe, explicitly-public app settings from Supabase.
 *
 * This does not download or execute code. It only returns JSON configuration
 * values that the installed app already knows how to interpret.
 */
data class RemoteAppConfig(
    val values: Map<String, String>,
    val fetchedAtMillis: Long
) {
    fun string(key: String, fallback: String = ""): String =
        values[key]?.let { runCatching { JSONObject("{\"v\":$it}").optString("v", fallback) }.getOrDefault(fallback) }
            ?: fallback

    fun boolean(key: String, fallback: Boolean = false): Boolean =
        values[key]?.let { runCatching { JSONObject("{\"v\":$it}").optBoolean("v", fallback) }.getOrDefault(fallback) }
            ?: fallback

    fun int(key: String, fallback: Int = 0): Int =
        values[key]?.let { runCatching { JSONObject("{\"v\":$it}").optInt("v", fallback) }.getOrDefault(fallback) }
            ?: fallback

    companion object {
        val EMPTY = RemoteAppConfig(emptyMap(), 0L)
    }
}

class RemoteConfigService(context: Context, private val config: BackendConfig) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "freelance_hub_remote_config", Context.MODE_PRIVATE
    )
    private val executor = Executors.newSingleThreadExecutor()

    fun load(callback: (Result<RemoteAppConfig>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(config.isConfigured() && config.anonKey.isNotBlank()) {
                    "Remote configuration is not configured."
                }
                val endpoint = config.baseUrl.trimEnd('/') +
                    "/rest/v1/app_remote_config?select=key,value&is_public=eq.true"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 10000
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("apikey", config.anonKey)
                    connection.setRequestProperty("Authorization", "Bearer " + config.anonKey)
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        val errorBody = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)
                            ?.use { it.readText() }.orEmpty()
                        error("Remote configuration request failed (HTTP $status): ${errorBody.take(160)}")
                    }
                    val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val rows = JSONArray(body)
                    val values = linkedMapOf<String, String>()
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val key = row.optString("key")
                        if (key.matches(Regex("^[a-z][a-z0-9_.-]{1,79}$"))) {
                            values[key] = row.opt("value")?.toString() ?: "null"
                        }
                    }
                    RemoteAppConfig(values, System.currentTimeMillis()).also(::save)
                } finally {
                    connection.disconnect()
                }
            }
            callback(result.recoverCatching { cached() })
        }
    }

    fun cached(): RemoteAppConfig {
        val raw = prefs.getString("values", null) ?: return RemoteAppConfig.EMPTY
        return runCatching {
            val json = JSONObject(raw)
            val values = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                values[key] = json.optString(key)
            }
            RemoteAppConfig(values, prefs.getLong("fetched_at", 0L))
        }.getOrDefault(RemoteAppConfig.EMPTY)
    }

    private fun save(remote: RemoteAppConfig) {
        val json = JSONObject()
        remote.values.forEach { (key, value) -> json.put(key, value) }
        prefs.edit()
            .putString("values", json.toString())
            .putLong("fetched_at", remote.fetchedAtMillis)
            .apply()
    }
}
