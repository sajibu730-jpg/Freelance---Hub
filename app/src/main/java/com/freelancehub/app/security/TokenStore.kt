package com.freelancehub.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local session storage using Android Keystore AES/GCM.
 * Session material is excluded from Android backup/transfer rules.
 * Production auth should still use short-lived access tokens and refresh-token rotation.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyAlias = "freelance_hub_session_key"
    private val transformation = "AES/GCM/NoPadding"

    fun saveAccessToken(token: String) {
        require(SessionSecurityRules.canStoreAccessToken(token))
        write("access_token", token)
    }

    fun saveRefreshToken(token: String) {
        require(SessionSecurityRules.canUseRefreshToken(token))
        write("refresh_token", token)
    }

    fun saveSession(accessToken: String, refreshToken: String) {
        require(SessionSecurityRules.canStoreAccessToken(accessToken))
        require(SessionSecurityRules.canUseRefreshToken(refreshToken))
        write("access_token", accessToken)
        write("refresh_token", refreshToken)
    }

    fun readAccessToken(): String? = read("access_token")

    fun readRefreshToken(): String? = read("refresh_token")

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("${name}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun read(name: String): String? = runCatching {
        val encoded = prefs.getString("${name}_data", null) ?: return null
        val ivEncoded = prefs.getString("${name}_iv", null) ?: return null
        val cipher = Cipher.getInstance(transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
