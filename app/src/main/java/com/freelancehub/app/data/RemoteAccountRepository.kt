
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
                "/rest/v1/profiles?id=eq.$userId&select=id,display_name,bio,cover_photo_url",
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
email = authUser.optString("email"),
            displayName = profile.optString("display_name"),
            bio = profile.optString("bio"),
            role = UserRole.FREELANCER,
            capabilities = "",
            skills = ""
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

        val payload = JSONObject()
            .put("display_name", input.displayName.trim())
            .put("bio", input.bio.trim())
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
                .put("display_name", input.displayName.trim())
                .put("bio", input.bio.trim())
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
}

data class RemoteAccount(
    val id: String,
    val coverPhotoUrl: String?,
    val email: String,
    val displayName: String,
    val bio: String,
    val role: UserRole,
    val capabilities: String,
    val skills: String
)

data class RemoteProfileUpdate(
    val displayName: String,
    val bio: String,
    val capabilities: String,
    val skills: String
)
