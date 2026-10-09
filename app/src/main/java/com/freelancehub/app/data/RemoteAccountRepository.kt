package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Server-backed account/profile source. No SharedPreferences fallback is permitted. */
class RemoteAccountRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun currentUser(): RemoteAccount = parseUser(
        JSONObject(String(client.readJsonResponse(client.openGet("/me", accessTokenProvider())), StandardCharsets.UTF_8))
    )

    fun updateProfile(input: RemoteProfileUpdate): RemoteAccount {
        require(Validation.text(input.displayName, 2, 120))
        require(Validation.text(input.bio, 0, 500))
        require(Validation.text(input.capabilities, 0, 2000))
        require(Validation.text(input.skills, 0, 1000))
        val payload = JSONObject()
            .put("display_name", input.displayName.trim())
            .put("bio", input.bio.trim())
            .put("capabilities", input.capabilities.trim())
            .put("skills", input.skills.trim())
            .toString().toByteArray(StandardCharsets.UTF_8)
        return parseUser(JSONObject(String(client.readJsonResponse(
            client.openJsonPatch("/me", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        ), StandardCharsets.UTF_8)))
    }

    private fun parseUser(x: JSONObject) = RemoteAccount(
        id = x.optString("id"),
        email = x.getString("email"),
        displayName = x.optString("display_name", x.optString("name")),
        bio = x.optString("bio"),
        role = runCatching { UserRole.valueOf(x.optString("role", UserRole.FREELANCER.name)) }.getOrDefault(UserRole.FREELANCER),
        capabilities = x.optString("capabilities"),
        skills = x.optString("skills")
    )
}

data class RemoteAccount(
    val id: String,
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
