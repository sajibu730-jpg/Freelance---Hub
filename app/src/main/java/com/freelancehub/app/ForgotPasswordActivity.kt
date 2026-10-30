package com.freelancehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.freelancehub.app.auth.AuthService

class ForgotPasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ForgotPasswordScreen(
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
private fun ForgotPasswordScreen(
    auth: AuthService,
    onBack: () -> Unit,
    onOpenApp: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val navy = Color(0xFF102B57)
    val blue = Color(0xFF315FCB)

    Surface(
        modifier = Modifier.fillMaxSize().imePadding(),
        color = Color(0xFFF1F5FD)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE5EEFF), Color(0xFFF8FAFF))))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !busy) {
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        if (sent) "Check your email" else "Recover your account",
                        style = MaterialTheme.typography.headlineSmall,
                        color = navy
                    )
                    Text(
                        if (sent)
                            "We requested a password-reset email. Open the newest message and tap its reset link. Freelance Hub will open the secure password page."
                        else
                            "Enter the email address registered to your Freelance Hub account. We will send a secure password-reset link.",
                        color = Color(0xFF53627A),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(2) { index ->
                            Box(
                                Modifier.weight(1f).height(5.dp).background(
                                    if (index == if (sent) 1 else 0) blue else Color(0xFFE2E8F2),
                                    RoundedCornerShape(8.dp)
                                )
                            )
                        }
                    }
                    if (!sent) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Registered email address") },
                            placeholder = { Text("name@example.com") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )
                        Text(
                            "Mobile/SMS recovery is not enabled on this Supabase project yet. Use your registered email for now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF69778D)
                        )
                        Button(
                            onClick = {
                                val target = email.trim()
                                if (!target.matches(Regex("""^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"""))) {
                                    error = "Enter a valid registered email address."
                                } else {
                                    busy = true
                                    error = ""
                                    auth.requestPasswordReset(target) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess { sent = true }
                                                .onFailure { error = it.message ?: "Could not request a reset email." }
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
                            else Text("Send reset link")
                        }
                    } else {
                        Text(
                            "If the email does not arrive, check Spam/Junk. Use only the newest reset email; older links may expire after a newer request.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF69778D)
                        )
                        OutlinedButton(
                            onClick = { sent = false; error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Try another email") }
                        TextButton(onClick = onOpenApp, modifier = Modifier.align(Alignment.End)) {
                            Text("Return to app")
                        }
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
