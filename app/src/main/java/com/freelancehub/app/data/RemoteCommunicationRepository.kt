package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.MessageValidation
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URLEncoder

/** Server-backed messaging and notification source. No local fallback is permitted. */
class RemoteCommunicationRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun listConversations(limit: Int = 50, cursor: String? = null): List<RemoteConversation> {
        require(limit in 1..50)
        val query = pageQuery(limit, cursor)
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/conversations$query", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { c ->
            val x = items.getJSONObject(c)
            RemoteConversation(
                id = x.getString("id"),
                jobId = x.optString("job_id"),
                participantId = x.optString("participant_id"),
                participantName = x.optString("participant_name"),
                lastMessage = x.optString("last_message"),
                lastMessageAt = x.optString("last_message_at"),
                unreadCount = x.optInt("unread_count", 0)
            )
        }
    }

    fun listMessages(conversationId: String, limit: Int = 50, cursor: String? = null): List<RemoteMessage> {
        requireUuid(conversationId)
        require(limit in 1..50)
        val query = pageQuery(limit, cursor)
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/conversations/$conversationId/messages$query", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { parseMessage(items.getJSONObject(it)) }
    }

    fun sendMessage(conversationId: String, body: String): RemoteMessage {
        requireUuid(conversationId)
        val normalized = MessageValidation.normalize(body)
        require(MessageValidation.validate(normalized) == null) { MessageValidation.validate(normalized) ?: "Invalid message" }
        val payload = JSONObject().put("body", normalized).toString().toByteArray(StandardCharsets.UTF_8)
        return parseMessage(JSONObject(String(client.readJsonResponse(
            client.openJsonPost("/conversations/$conversationId/messages", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        ), StandardCharsets.UTF_8)))
    }

    fun markMessageRead(messageId: String): Boolean {
        requireUuid(messageId)
        client.readJsonResponse(client.openJsonPatch(
            "/messages/$messageId/read", ByteArray(0), accessTokenProvider(), SecureApiClient.newIdempotencyKey()
        ))
        return true
    }

    fun listNotifications(limit: Int = 50, cursor: String? = null): List<RemoteNotification> {
        require(limit in 1..50)
        val query = pageQuery(limit, cursor)
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/notifications$query", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { n ->
            val x = items.getJSONObject(n)
            RemoteNotification(
                id = x.getString("id"),
                title = x.getString("title"),
                message = x.getString("message"),
                createdAt = x.optString("created_at"),
                read = !x.isNull("read_at")
            )
        }
    }

    fun markNotificationRead(notificationId: String): Boolean {
        requireUuid(notificationId)
        client.readJsonResponse(client.openJsonPatch(
            "/notifications/$notificationId/read", ByteArray(0), accessTokenProvider(), SecureApiClient.newIdempotencyKey()
        ))
        return true
    }

    private fun parseMessage(x: JSONObject) = RemoteMessage(
        id = x.getString("id"),
        conversationId = x.getString("conversation_id"),
        senderId = x.getString("sender_id"),
        senderName = x.optString("sender_name"),
        body = x.getString("body"),
        createdAt = x.optString("created_at"),
        read = !x.isNull("read_at")
    )

    private fun pageQuery(limit: Int, cursor: String?): String = buildString {
        append("?limit=").append(limit)
        if (!cursor.isNullOrBlank()) append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
    }

    private fun requireUuid(value: String) {
        require(value.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Invalid resource id" }
    }
}

data class RemoteConversation(
    val id: String,
    val jobId: String,
    val participantId: String,
    val participantName: String,
    val lastMessage: String,
    val lastMessageAt: String,
    val unreadCount: Int
)

data class RemoteMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val createdAt: String,
    val read: Boolean
)

data class RemoteNotification(
    val id: String,
    val title: String,
    val message: String,
    val createdAt: String,
    val read: Boolean
)
