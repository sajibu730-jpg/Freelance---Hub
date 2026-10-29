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
        val token = recoveryAccessToken(intent?.data)
        setContent {
            MaterialTheme {
                RecoveryPasswordScreen(
                    accessToken = token,
                    auth = remember { AuthService(applicationContext) },
                    onFinished = { finish() }
                )
            }
        }
    }

    private fun recoveryAccessToken(uri: Uri?): String {
        if (uri == null) return ""
        val params = Uri.parse("https://recovery.invalid/?${uri.fragment.orEmpty()}")
        return params.getQueryParameter("access_token").orEmpty()
    }
}

@Composable
private fun RecoveryPasswordScreen(accessToken: String, auth: AuthService, onFinished: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (accessToken.isBlank()) "This reset link is invalid or expired. Request a new reset email." else "") }

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
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                        label = { Text("New password") }, singleLine = true, enabled = !busy && !success,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle password visibility") } })
                    OutlinedTextField(confirm, { confirm = it }, Modifier.fillMaxWidth(),
                        label = { Text("Confirm new password") }, singleLine = true, enabled = !busy && !success,
                        visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showConfirm = !showConfirm }) { Icon(if (showConfirm) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle confirmation visibility") } })
                    if (message.isNotBlank()) Text(message, color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        when {
                            accessToken.isBlank() -> message = "This reset link is invalid or expired. Request a new reset email."
                            password.length < 8 -> message = "Use at least 8 characters."
                            password != confirm -> message = "The passwords do not match."
                            else -> {
                                busy = true
                                message = ""
                                auth.updateRecoveryPassword(accessToken, password) { result ->
                                    runOnUiThread {
                                        busy = false
                                        result.onSuccess {
                                            success = true
                                            message = "Password updated. Return to Freelance Hub and sign in with your new password."
                                        }.onFailure { message = it.message ?: "Could not update password. Request a new reset email." }
                                    }
                                }
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy && !success && accessToken.isNotBlank()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Update password")
                    }
                    if (success) TextButton(onClick = onFinished, Modifier.align(Alignment.End)) { Text("Return to app") }
                }
            }
        }
    }
}
