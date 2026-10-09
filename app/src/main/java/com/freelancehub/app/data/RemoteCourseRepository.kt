package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.CheckoutSecurityRules
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URLEncoder

/**
 * Server-backed learning and premium data source.
 * The app never treats local course metadata as the source of truth for paid access.
 */
class RemoteCourseRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun listCourses(limit: Int = 50, cursor: String? = null): List<RemoteCourse> {
        require(limit in 1..50)
        val query = buildString {
            append("?limit=").append(limit)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
        }
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/courses$query", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("courses") ?: JSONArray()
        return (0 until items.length()).map { parseCourse(items.getJSONObject(it)) }
    }

    fun getCourse(courseId: String): RemoteCourse {
        requireUuid(courseId)
        return parseCourse(JSONObject(String(
            client.readJsonResponse(client.openGet("/courses/$courseId", accessTokenProvider())), StandardCharsets.UTF_8
        )))
    }

    fun myCourses(limit: Int = 50): List<RemoteEnrollment> {
        require(limit in 1..50)
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/me/courses?limit=$limit", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("courses") ?: JSONArray()
        return (0 until items.length()).map { parseEnrollment(items.getJSONObject(it)) }
    }

    fun enroll(courseId: String): RemoteEnrollment {
        requireUuid(courseId)
        val body = JSONObject().put("course_id", courseId).toString().toByteArray(StandardCharsets.UTF_8)
        return parseEnrollment(JSONObject(String(
            client.readJsonResponse(client.openJsonPost("/courses/$courseId/enroll", body, accessTokenProvider(), SecureApiClient.newIdempotencyKey())),
            StandardCharsets.UTF_8
        )))
    }

    fun startCourseCheckout(courseId: String): RemoteCheckoutSession {
        requireUuid(courseId)
        val body = JSONObject().put("course_id", courseId).toString().toByteArray(StandardCharsets.UTF_8)
        val root = JSONObject(String(client.readJsonResponse(
            client.openJsonPost("/courses/$courseId/checkout", body, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        ), StandardCharsets.UTF_8))
        return parseCheckoutSession(root)
    }

    fun checkoutStatus(checkoutId: String): RemoteCheckoutStatus {
        require(CheckoutSecurityRules.isValidCheckoutId(checkoutId)) { "Invalid checkout session" }
        val root = JSONObject(String(
            client.readJsonResponse(client.openGet("/payments/$checkoutId", accessTokenProvider())), StandardCharsets.UTF_8
        ))
        val status = root.optString("status", "PENDING").uppercase()
        val paid = root.optBoolean("paid", false)
        require(CheckoutSecurityRules.isKnownCheckoutStatus(status)) { "Invalid payment status" }
        require(!(status == "PAID" && !paid)) { "Inconsistent paid state" }
        if (root.has("receipt_id")) {
            require(PaymentReceiptRules.validReceiptId(root.getString("receipt_id"))) { "Invalid receipt" }
        }
        if (root.has("provider_reference")) {
            require(PaymentReceiptRules.validProviderReference(root.getString("provider_reference"))) { "Invalid provider reference" }
        }
        return RemoteCheckoutStatus(
            checkoutId = checkoutId,
            status = status,
            paid = paid,
            receiptId = root.optString("receipt_id").takeIf { it.isNotBlank() },
            providerReference = root.optString("provider_reference").takeIf { it.isNotBlank() }
        )
    }

    fun premiumStatus(): RemotePremiumStatus {
        return parsePremiumStatus(JSONObject(String(
            client.readJsonResponse(client.openGet("/premium", accessTokenProvider())), StandardCharsets.UTF_8
        )))
    }

    fun startPremiumCheckout(planId: String): RemoteCheckoutSession {
        require(planId.matches(Regex("^[A-Za-z0-9._-]{1,80}$"))) { "Invalid premium plan" }
        val body = JSONObject().put("plan_id", planId).toString().toByteArray(StandardCharsets.UTF_8)
        val root = JSONObject(String(client.readJsonResponse(
            client.openJsonPost("/premium/checkout", body, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        ), StandardCharsets.UTF_8))
        return parseCheckoutSession(root)
    }

    private fun parseCourse(x: JSONObject) = RemoteCourse(
        id = x.getString("id"),
        title = x.getString("title"),
        description = x.optString("description"),
        priceMinor = x.optLong("price_minor", 0),
        currency = x.optString("currency", "USD"),
        access = x.optString("access", if (x.optLong("price_minor", 0) == 0L) "FREE" else "PAID"),
        instructorName = x.optString("instructor_name"),
        lessonCount = x.optInt("lesson_count", 0)
    )

    private fun parseEnrollment(x: JSONObject) = RemoteEnrollment(
        id = x.getString("id"), courseId = x.getString("course_id"), status = x.optString("status", "ACTIVE"),
        progressPercent = x.optInt("progress_percent", 0).coerceIn(0, 100)
    )

    private fun parseCheckoutSession(x: JSONObject): RemoteCheckoutSession {
        val checkoutId = x.getString("checkout_id")
        val checkoutUrl = x.getString("checkout_url")
        require(CheckoutSecurityRules.isValidCheckoutId(checkoutId)) { "Invalid checkout session" }
        require(CheckoutSecurityRules.isSafeExternalCheckoutUrl(checkoutUrl)) { "Unsafe checkout URL" }
        return RemoteCheckoutSession(checkoutId, checkoutUrl)
    }

    private fun parsePremiumStatus(x: JSONObject) = RemotePremiumStatus(
        active = x.optBoolean("active", false), planId = x.optString("plan_id"), renewsAt = x.optString("renews_at")
    )

    private fun requireUuid(value: String) {
        require(value.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Invalid resource id" }
    }
}

data class RemoteCourse(
    val id: String,
    val title: String,
    val description: String,
    val priceMinor: Long,
    val currency: String,
    val access: String,
    val instructorName: String,
    val lessonCount: Int
)

data class RemoteEnrollment(
    val id: String,
    val courseId: String,
    val status: String,
    val progressPercent: Int
)

data class RemotePremiumStatus(val active: Boolean, val planId: String, val renewsAt: String)
data class RemoteCheckoutSession(val checkoutId: String, val checkoutUrl: String)
data class RemoteCheckoutStatus(
    val checkoutId: String,
    val status: String,
    val paid: Boolean,
    val receiptId: String? = null,
    val providerReference: String? = null
)
