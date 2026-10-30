package com.freelancehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.freelancehub.app.auth.AuthService

class ForgotPasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ForgotPasswordFlow(
                    auth = remember { AuthService(applicationContext) },
                    onBack = { finish() },
                    onOpenApp = {
                        val launch = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(launch)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun ForgotPasswordFlow(
    auth: AuthService,
    onBack: () -> Unit,
    onOpenApp: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var identifier by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val isPhone = !identifier.trim().contains("@")
    val navy = Color(0xFF102B57)
    val blue = Color(0xFF315FCB)

    Surface(
        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        color = Color(0xFFF1F5FD)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE5EEFF), Color(0xFFF8FAFF))))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (busy) return@IconButton
                    if (page > 0) {
                        page--
                        error = ""
                        info = ""
                    } else onBack()
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = navy)
                }
                Text("Freelance Hub", style = MaterialTheme.typography.titleLarge, color = navy)
            }
            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        when (page) {
                            0 -> "Recover your account"
                            1 -> "Verify your account"
                            else -> "Create a new password"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = navy
                    )
                    Text(
                        when (page) {
                            0 -> "Enter the email address or mobile number you used when creating your account."
                            1 -> "Enter the verification code sent to your registered contact."
                            else -> "A new password is optional. You can continue without changing your current password."
                        },
                        color = Color(0xFF53627A),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier.weight(1f).height(5.dp)
                                    .background(
                                        if (index <= page) blue else Color(0xFFE2E8F2),
                                        RoundedCornerShape(8.dp)
                                    )
                            )
                        }
                    }

                    when (page) {
                        0 -> {
                            OutlinedTextField(
                                value = identifier,
                                onValueChange = { identifier = it; error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Email address or mobile number") },
                                placeholder = { Text("name@example.com or +971...") },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (identifier.contains("@")) KeyboardType.Email else KeyboardType.Phone
                                )
                            )
                            Text(
                                "Use the same contact information that is registered to your account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF69778D)
                            )
                            Button(
                                onClick = {
                                    val target = identifier.trim()
                                    val phone = !target.contains("@")
                                    if (target.isBlank()) {
                                        error = "Enter your registered email address or mobile number."
                                    } else if (phone && !target.matches(Regex("""^\+[1-9][0-9]{7,14}$"""))) {
                                        error = "Enter the mobile number with country code, for example +9715XXXXXXXX."
                                    } else if (!phone && !target.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))) {
                                        error = "Enter a valid email address."
                                    } else {
                                        busy = true
                                        error = ""
                                        info = ""
                                        auth.requestRecoveryCode(target, phone) { result ->
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                busy = false
                                                result.onSuccess {
                                                    page = 1
                                                    info = if (phone) {
                                                        "A verification code has been requested for your mobile number."
                                                    } else {
                                                        "A verification code has been requested. Check your inbox and spam folder."
                                                    }
                                                }.onFailure {
                                                    error = it.message ?: "Could not send the verification code."
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Next")
                            }
                        }
                        1 -> {
                            Text(
                                identifier,
                                color = navy,
                                style = MaterialTheme.typography.titleSmall
                            )
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(8); error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Verification code") },
                                placeholder = { Text("Enter 6–8 digit code") },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Button(
                                onClick = {
                                    busy = true
                                    error = ""
                                    auth.verifyRecoveryCode(identifier, code, isPhone) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess {
                                                page = 2
                                                code = ""
                                                info = "Your contact has been verified. You can now set a new password or continue without changing it."
                                            }.onFailure {
                                                error = it.message ?: "The code could not be verified."
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && code.length in 6..8,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Next")
                            }
                            TextButton(
                                onClick = {
                                    if (!busy) {
                                        busy = true
                                        error = ""
                                        auth.requestRecoveryCode(identifier, isPhone) { result ->
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                busy = false
                                                result.onSuccess { info = "A new code has been requested. Use the newest code." }
                                                    .onFailure { error = it.message ?: "Could not resend the code." }
                                            }
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Resend code") }
                        }
                        else -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("New password (optional)") },
                                singleLine = true,
                                enabled = !busy,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = "Toggle password visibility"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Confirm new password") },
                                singleLine = true,
                                enabled = !busy,
                                visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                        Icon(
                                            if (confirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = "Toggle confirmation visibility"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Text(
                                "Leave both password fields empty to keep your current password. You will still be signed in after verification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF69778D)
                            )
                            Button(
                                onClick = {
                                    when {
                                        password.isBlank() && confirmPassword.isBlank() -> onOpenApp()
                                        password.length < 8 -> error = "Use at least 8 characters, or leave both fields empty."
                                        password != confirmPassword -> error = "The passwords do not match."
                                        else -> {
                                            busy = true
                                            error = ""
                                            auth.updateVerifiedRecoveryPassword(password) { result ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    busy = false
                                                    result.onSuccess { onOpenApp() }
                                                        .onFailure { error = it.message ?: "Could not update the password." }
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Next")
                            }
                        }
                    }

                    if (info.isNotBlank()) {
                        Text(info, color = Color(0xFF28765D), style = MaterialTheme.typography.bodySmall)
                    }
                    if (error.isNotBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Secure account recovery", color = Color(0xFF69778D), style = MaterialTheme.typography.labelSmall)
        }
    }
}
