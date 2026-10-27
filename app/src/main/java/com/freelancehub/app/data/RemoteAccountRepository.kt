
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
               "/rest/v1/profiles?id=eq.$userId&select=id,display_name,bio,capabilities,skills,cover_photo_url,photo_url,mobile_number",
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
            mobileNumber = profile.optString("mobile_number")
        )
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

    fun requestEmailChange(newEmail: String) {
        require(Validation.email(newEmail)) { "Enter a valid email address." }
        val token = accessTokenProvider()
            ?: throw IllegalStateException("Please sign in again to change your email")
        val current = currentUser()
        require(!newEmail.trim().equals(current.email.trim(), ignoreCase = true)) {
            "This is already your current email address."
        }
        val payload = JSONObject()
            .put("email", newEmail.trim())
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        client.readJsonResponse(
            client.openJsonPut("/auth/v1/user", payload, token)
        )
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
    val mobileNumber: String = ""
)

data class RemoteProfileUpdate(
    val displayName: String,
    val bio: String,
    val capabilities: String,
        val skills: String,
    val mobileNumber: String? = null
)
