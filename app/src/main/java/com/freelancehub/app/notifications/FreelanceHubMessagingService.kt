package com.freelancehub.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.freelancehub.app.MainActivity
import com.freelancehub.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.messaging.FirebaseMessaging
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.security.TokenStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class FreelanceHubMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushTokenRegistrar.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"] ?: "Freelance Hub"
        val body = message.notification?.body
            ?: message.data["body"] ?: "You have a new notification."
        showNotification(this, title, body, message.data)
    }

    companion object {
        const val CHANNEL_ID = "freelance_hub_updates"

        fun showNotification(
            context: Context,
            title: String,
            body: String,
            data: Map<String, String> = emptyMap()
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Freelance Hub updates",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Job proposals, messages and account updates" }
                )
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data["screen"]?.let { putExtra("notification_screen", it) }
                data["resource_id"]?.let { putExtra("notification_resource_id", it) }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0x7fffffff).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title.take(120))
                .setContentText(body.take(300))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        }
    }
}

object PushTokenRegistrar {
    private val executor = Executors.newSingleThreadExecutor()

    fun refresh(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(context.applicationContext, token) }
    }

    fun register(context: Context, fcmToken: String) {
        if (fcmToken.isBlank()) return
        executor.execute {
            runCatching {
                val config = RuntimeBackendConfig.current()
                require(config.isConfigured() && config.anonKey.isNotBlank())
                val accessToken = TokenStore(context.applicationContext).readAccessToken()
                    ?: return@runCatching
                val base = config.baseUrl.trimEnd('/')

                val userConnection = (URL("$base/auth/v1/user").openConnection() as HttpURLConnection)
                val userId = try {
                    userConnection.requestMethod = "GET"
                    userConnection.connectTimeout = 10000
                    userConnection.readTimeout = 15000
                    userConnection.setRequestProperty("apikey", config.anonKey)
                    userConnection.setRequestProperty("Authorization", "Bearer $accessToken")
                    val status = userConnection.responseCode
                    val stream = if (status in 200..299) userConnection.inputStream else userConnection.errorStream
                    val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    require(status in 200..299) { "Could not verify signed-in user for push registration." }
                    JSONObject(text).optString("id").also {
                        require(it.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Invalid authenticated user id." }
                    }
                } finally {
                    userConnection.disconnect()
                }

                val payload = JSONObject()
                    .put("user_id", userId)
                    .put("fcm_token", fcmToken)
                    .put("platform", "android")
                    .put("device_label", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
                    .put("is_active", true)
                    .toString()

                val connection = (URL("$base/rest/v1/device_push_tokens?on_conflict=fcm_token").openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 15000
                    connection.doOutput = true
                    connection.setRequestProperty("apikey", config.anonKey)
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                    connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        val error = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                        error("Push token registration failed: HTTP $status ${error.take(300)}")
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
