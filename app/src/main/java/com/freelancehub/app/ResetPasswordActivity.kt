package com.freelancehub.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.freelancehub.app.auth.AuthService

class ResetPasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val token = recoveryAccessToken(uri)
        val linkError = recoveryLinkError(uri)
        setContent {
            MaterialTheme {
                RecoveryPasswordScreen(
                    accessToken = token,
                    initialLinkError = linkError,
                    auth = remember { AuthService(applicationContext) },
                    onFinished = { finish() }
                )
            }
        }
    }

    /**
     * Supabase implicit-flow recovery links carry tokens in the URL fragment,
     * while some email/link handlers may move parameters into the query.
     * Read both without logging or exposing any token.
     */
    private fun recoveryAccessToken(uri: Uri?): String {
        if (uri == null) return ""
        uri.getQueryParameter("access_token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val fragment = uri.fragment.orEmpty()
        if (fragment.isBlank()) return ""
        val fragmentUri = Uri.parse("https://recovery.invalid/?$fragment")
        return fragmentUri.getQueryParameter("access_token").orEmpty()
    }

    private fun recoveryLinkError(uri: Uri?): String {
        if (uri == null) return ""
        val fragment = uri.fragment.orEmpty()
        val fragmentUri = if (fragment.isBlank()) null
            else Uri.parse("https://recovery.invalid/?$fragment")
        val code = uri.getQueryParameter("error_code")
            ?: fragmentUri?.getQueryParameter("error_code")
        val description = uri.getQueryParameter("error_description")
            ?: fragmentUri?.getQueryParameter("error_description")
        return when {
            code == "otp_expired" -> "This reset link has expired or was already used. Request a new reset email and open the newest message once."
            !description.isNullOrBlank() -> description.replace('+', ' ')
            !code.isNullOrBlank() -> "The reset link could not be verified ($code). Request a new reset email."
            else -> ""
        }
    }
}

@Composable
private fun RecoveryPasswordScreen(
    accessToken: String,
    initialLinkError: String,
    auth: AuthService,
    onFinished: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var message by remember {
        mutableStateOf(
            initialLinkError.ifBlank {
                if (accessToken.isBlank()) "This reset link is invalid or expired. Request a new reset email." else ""
            }
        )
    }

    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFFF2F6FF)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Reset your password", style = MaterialTheme.typography.headlineSmall)
                    Text("Choose a new password for your Freelance Hub account.")
                    OutlinedTextField(
                        password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text("New password") }, singleLine = true, enabled = !busy && !success,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle password visibility")
                            }
                        }
                    )
                    OutlinedTextField(
                        confirm, { confirm = it }, Modifier.fillMaxWidth(),
                        label = { Text("Confirm new password") }, singleLine = true, enabled = !busy && !success,
                        visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showConfirm = !showConfirm }) {
                                Icon(if (showConfirm) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle confirmation visibility")
                            }
                        }
                    )
                    if (message.isNotBlank()) {
                        Text(message, color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            when {
                                accessToken.isBlank() -> message = initialLinkError.ifBlank {
                                    "This reset link is invalid or expired. Request a new reset email."
                                }
                                password.length < 8 -> message = "Use at least 8 characters."
                                password != confirm -> message = "The passwords do not match."
                                else -> {
                                    busy = true
                                    message = ""
                                    auth.updateRecoveryPassword(accessToken, password) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess {
                                                success = true
                                                message = "Password updated. Return to Freelance Hub and sign in with your new password."
                                            }.onFailure {
                                                message = it.message ?: "Could not update password. Request a new reset email."
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !success && accessToken.isNotBlank()
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Update password")
                    }
                    if (success) TextButton(onClick = onFinished, Modifier.align(Alignment.End)) {
                        Text("Return to app")
                    }
                }
            }
        }
    }
}
