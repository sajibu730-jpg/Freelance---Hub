
package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Server-backed account/profile source. */
class RemoteAccountRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun currentUser(): RemoteAccount {
        val token = accessTokenProvider()
            ?: throw IllegalStateException("Please sign in again to load your account")

        val authUser = JSONObject(
            String(
                client.readJsonResponse(
                    client.openGet("/auth/v1/user", token)
                ),
                StandardCharsets.UTF_8
            )
        )

        val userId = authUser.optString("id")
        require(userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Supabase Auth returned an invalid user id"
        }

        val bytes = client.readJsonResponse(
            client.openGet(
               "/rest/v1/profiles?id=eq.$userId&select=id,display_name,bio,capabilities,skills,cover_photo_url,photo_url,mobile_number,mobile_number_changed_at",
                token
            )
        )

        val rows = JSONArray(String(bytes, StandardCharsets.UTF_8))
        val profile = if (rows.length() > 0) {
            rows.getJSONObject(0)
        } else {
            JSONObject()
        }

        return RemoteAccount(
    id = userId,
    coverPhotoUrl = profile.optString("cover_photo_url").takeIf { it.isNotBlank() },
    photoPath = profile.optString("photo_url").takeIf { it.isNotBlank() },
    email = authUser.optString("email"),
            displayName = profile.optString("display_name"),
            bio = profile.optString("bio"),
            role = UserRole.FREELANCER,
                        capabilities = profile.optString("capabilities"),
            skills = profile.optString("skills"),
            mobileNumber = profile.optString("mobile_number"),
            mobileNumberChangedAt = profile.optString("mobile_number_changed_at").takeIf { it.isNotBlank() }
        )
    }

    /**
     * Checks the server-recorded six-month mobile-number cooldown before asking
     * Supabase Auth to change the verified phone number.
     */
    fun ensureMobileChangeAllowed() {
        val changedAt = currentUser().mobileNumberChangedAt ?: return
        val raw = changedAt.take(19)
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val changed = parser.parse(raw) ?: return
        val allowedAt = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            time = changed
            add(java.util.Calendar.MONTH, 6)
        }.time
        if (System.currentTimeMillis() < allowedAt.time) {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
            throw IllegalStateException(
                "For account security, this mobile number cannot be changed again until ${formatter.format(allowedAt)}."
            )
        }
    }

    /**
     * Saves the phone only after Supabase Auth confirms that the same phone is verified.
     */
    fun syncVerifiedPhoneToProfile(): RemoteAccount {
        ensureMobileChangeAllowed()
        val token = accessTokenProvider()
            ?: throw IllegalStateException("Please sign in again to save the verified mobile number")
        val authUser = JSONObject(
            String(client.readJsonResponse(client.openGet("/auth/v1/user", token)), StandardCharsets.UTF_8)
        )
        val phone = authUser.optString("phone")
        val confirmedAt = authUser.optString("phone_confirmed_at")
        require(phone.matches(Regex("""^\+[1-9][0-9]{7,14}$""")) && confirmedAt.isNotBlank()) {
            "Verify the new mobile number before saving it to your profile."
        }
        val userId = authUser.optString("id")
        require(userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Supabase Auth returned an invalid user id"
        }
        val payload = JSONObject().put("mobile_number", phone).toString()
            .toByteArray(StandardCharsets.UTF_8)
        val updatedBytes = client.readJsonResponse(
            client.openJsonPatch(
                "/rest/v1/profiles?id=eq.$userId",
                payload,
                token,
                SecureApiClient.newIdempotencyKey(),
                preferRepresentation = true
            )
        )
        require(JSONArray(String(updatedBytes, StandardCharsets.UTF_8)).length() > 0) {
            "The verified number was not confirmed as saved by the server."
        }
        return currentUser()
    }

    fun updateCoverPhoto(
        imageBytes: ByteArray,
        contentType: String
    ): RemoteAccount {
        val token = accessTokenProvider()
            ?: throw IllegalStateException("Please sign in again to update your cover photo")

        val userId = currentUser().id
        val photoUrl = client.uploadCoverPhoto(
            userId,
            imageBytes,
            contentType,
            token
        )

        val existingBytes = client.readJsonResponse(
            client.openGet(
                "/rest/v1/profiles?id=eq.$userId&select=id",
                token
            )
        )
        val existing = JSONArray(
            String(existingBytes, StandardCharsets.UTF_8)
        )

        val payload = JSONObject()
            .put("cover_photo_url", photoUrl)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        if (existing.length() > 0) {
            client.readJsonResponse(
                client.openJsonPatch(
                    "/rest/v1/profiles?id=eq.$userId",
                    payload,
                    token,
                    SecureApiClient.newIdempotencyKey()
                )
            )
        } else {
            val newProfile = JSONObject()
                .put("id", userId)
                .put("cover_photo_url", photoUrl)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            client.readJsonResponse(
                client.openJsonPost(
                    "/rest/v1/profiles",
                    newProfile,
                    token,
                    SecureApiClient.newIdempotencyKey()
                )
            )
        }

        return currentUser()
    }

    fun updateProfile(input: RemoteProfileUpdate): RemoteAccount {
        require(Validation.text(input.displayName, 2, 120))
        require(Validation.text(input.bio, 0, 500))

        val token = accessTokenProvider()
            ?: throw IllegalStateException("Please sign in again to update your profile")

        val authUser = JSONObject(
            String(
                client.readJsonResponse(
                    client.openGet("/auth/v1/user", token)
                ),
                StandardCharsets.UTF_8
            )
        )

        val userId = authUser.optString("id")
        require(userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Supabase Auth returned an invalid user id"
        }

        val existingBytes = client.readJsonResponse(
            client.openGet(
                "/rest/v1/profiles?id=eq.$userId&select=id",
                token
            )
        )
        val existing = JSONArray(
            String(existingBytes, StandardCharsets.UTF_8)
        )

                val payloadJson = JSONObject()
    .put("display_name", input.displayName.trim())
    .put("bio", input.bio.trim())
    .put("capabilities", input.capabilities.trim())
    .put("skills", input.skills.trim())

input.mobileNumber?.let {
    payloadJson.put("mobile_number", it.trim())
}

val payload = payloadJson.toString()
    .toByteArray(StandardCharsets.UTF_8)

        
        if (existing.length() > 0) {
            val updatedBytes = client.readJsonResponse(
                client.openJsonPatch(
                    "/rest/v1/profiles?id=eq.$userId",
                    payload,
                    token,
                    SecureApiClient.newIdempotencyKey(),
                    preferRepresentation = true
                )
            )

            val updatedRows = JSONArray(
                String(updatedBytes, StandardCharsets.UTF_8)
            )

            if (updatedRows.length() == 0) {
                throw IllegalStateException(
                    "Profile update was not confirmed by the server"
                )
            }
        } else {

                        
val newProfileJson = JSONObject()
    .put("id", userId)
    .put("display_name", input.displayName.trim())
    .put("bio", input.bio.trim())
    .put("capabilities", input.capabilities.trim())
    .put("skills", input.skills.trim())

input.mobileNumber?.let {
    newProfileJson.put("mobile_number", it.trim())
}

val newProfile = newProfileJson.toString()
    .toByteArray(StandardCharsets.UTF_8)


            client.readJsonResponse(
                client.openJsonPost(
                    "/rest/v1/profiles",
                    newProfile,
                    token,
                    SecureApiClient.newIdempotencyKey()
                )
            )
        }

        return currentUser()
    }
}

data class RemoteAccount(
    val id: String,
    val coverPhotoUrl: String?,
    val photoPath: String?,
    val email: String,
    val displayName: String,
    val bio: String,
    val role: UserRole,
    val capabilities: String,
        val skills: String,
    val mobileNumber: String = "",
    val mobileNumberChangedAt: String? = null
)

data class RemoteProfileUpdate(
    val displayName: String,
    val bio: String,
    val capabilities: String,
        val skills: String,
    val mobileNumber: String? = null
)
