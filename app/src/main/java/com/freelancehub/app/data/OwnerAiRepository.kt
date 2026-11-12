package com.freelancehub.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class OwnerAiContextMessage(val role: String, val text: String)

data class OwnerAiResult(
    val requestId: String,
    val reply: String,
    val proposalJson: String?,
    val modelName: String,
    val applied: Boolean
)

class OwnerAiRepository(private val config: BackendConfig) {
    fun ask(
        accessToken: String,
        prompt: String,
        context: List<OwnerAiContextMessage>
    ): OwnerAiResult {
        require(prompt.isNotBlank() && prompt.length <= 6000) {
            "Enter a message between 1 and 6000 characters."
        }
        val history = JSONArray()
        context.takeLast(6).forEach { item ->
            history.put(JSONObject().put("role", item.role).put("text", item.text.take(1500)))
        }
        val payload = JSONObject()
            .put("prompt", prompt.trim())
            .put("context", history)
            .toString()

        val connection = URL(config.baseUrl.trimEnd('/') + "/functions/v1/owner-ai-assistant")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("apikey", config.anonKey)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(payload.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(responseText) }
                .getOrElse { error("AI service returned an unreadable response (HTTP $status).") }
            if (status !in 200..299 || !response.optBoolean("success")) {
                error(response.optString("error").ifBlank { "AI request failed (HTTP $status)." })
            }
            val proposal = response.optJSONObject("proposal")
            return OwnerAiResult(
                requestId = response.optString("request_id"),
                reply = response.optString("reply"),
                proposalJson = proposal?.toString(),
                modelName = response.optString("model_name"),
                applied = response.optBoolean("applied", false)
            )
        } finally {
            connection.disconnect()
        }
    }
}
