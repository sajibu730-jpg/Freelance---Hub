
package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.MessageValidation
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Server-backed messaging and notification source. */
class RemoteCommunicationRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun listConversations(limit: Int = 50, cursor: String? = null): List<RemoteConversation> {
        require(limit in 1..50)
        val token = requireAccessToken()
        val userId = currentUserId(token)
        val path = "/rest/v1/conversations?select=id,job_id,client_id,freelancer_id,updated_at&order=updated_at.desc&limit=$limit"
        val rows = JSONArray(String(
            client.readJsonResponse(client.openGet(path, token)),
            StandardCharsets.UTF_8
        ))

        return (0 until rows.length()).map { i ->
            val x = rows.getJSONObject(i)
            val clientId = x.optString("client_id")
            val freelancerId = x.optString("freelancer_id")
            val otherId = if (userId == clientId) freelancerId else clientId

            RemoteConversation(
                id = x.getString("id"),
                jobId = x.optString("job_id", ""),
                participantId = otherId,
                participantName = "",
                lastMessage = "",
                lastMessageAt = x.optString("updated_at", ""),
                unreadCount = 0
            )
        }
    }

    fun listMessages(conversationId: String, limit: Int = 50, cursor: String? = null): List<RemoteMessage> {
        requireUuid(conversationId)
        require(limit in 1..50)
        val token = requireAccessToken()
        val path = "/rest/v1/messages?select=id,conversation_id,sender_id,body,read_at,created_at" +
            "&conversation_id=eq.$conversationId&order=created_at.asc&limit=$limit"
        val rows = JSONArray(String(
            client.readJsonResponse(client.openGet(path, token)),
            StandardCharsets.UTF_8
        ))
        return (0 until rows.length()).map { parseMessage(rows.getJSONObject(it)) }
    }

    fun sendMessage(conversationId: String, body: String): RemoteMessage {
        requireUuid(conversationId)
        val token = requireAccessToken()
        val normalized = MessageValidation.normalize(body)
        val validationError = MessageValidation.validate(normalized)
        require(validationError == null) { validationError ?: "Invalid message" }

        val senderId = currentUserId(token)
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("sender_id", senderId)
            .put("body", normalized)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val response = client.readJsonResponse(
            client.openJsonPost(
                "/rest/v1/messages?select=id,conversation_id,sender_id,body,read_at,created_at",
                payload,
                token,
                SecureApiClient.newIdempotencyKey()
            )
        )
        val rows = JSONArray(String(response, StandardCharsets.UTF_8))
        check(rows.length() > 0) {
            "The server did not return the saved message. Check Prefer: return=representation."
        }
        return parseMessage(rows.getJSONObject(0))
    }

    // Message UPDATE is not attempted until a secure read-receipt RLS policy exists.
    fun markMessageRead(messageId: String): Boolean {
        requireUuid(messageId)
        return false
    }

    fun listNotifications(limit: Int = 50, cursor: String? = null): List<RemoteNotification> {
        require(limit in 1..50)
        val token = requireAccessToken()
        val path = "/rest/v1/notifications?select=id,title,body,created_at,is_read,read_at" +
            "&order=created_at.desc&limit=$limit"
        val rows = JSONArray(String(
            client.readJsonResponse(client.openGet(path, token)),
            StandardCharsets.UTF_8
        ))

        return (0 until rows.length()).map { i ->
            val x = rows.getJSONObject(i)
            RemoteNotification(
                id = x.getString("id"),
                title = x.optString("title", ""),
                message = x.optString("body", ""),
                createdAt = x.optString("created_at", ""),
                read = x.optBoolean("is_read", false) || !x.isNull("read_at")
            )
        }
    }

    fun markNotificationRead(notificationId: String): Boolean {
        requireUuid(notificationId)
        val token = requireAccessToken()
        val payload = JSONObject()
            .put("is_read", true)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        client.readJsonResponse(
            client.openJsonPatch(
                "/rest/v1/notifications?id=eq.$notificationId",
                payload,
                token,
                SecureApiClient.newIdempotencyKey()
            )
        )
        return true
    }

    private fun currentUserId(token: String): String {
        val user = JSONObject(String(
            client.readJsonResponse(client.openGet("/auth/v1/user", token)),
            StandardCharsets.UTF_8
        ))
        val id = user.optString("id")
        requireUuid(id)
        return id
    }

    private fun requireAccessToken(): String =
        accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Please sign in again to use Messages")

    private fun parseMessage(x: JSONObject) = RemoteMessage(
        id = x.getString("id"),
        conversationId = x.getString("conversation_id"),
        senderId = x.getString("sender_id"),
        senderName = "",
        body = x.getString("body"),
        createdAt = x.optString("created_at", ""),
        read = !x.isNull("read_at")
    )

    private fun requireUuid(value: String) {
        require(value.matches(
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        )) { "Invalid resource id" }
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
