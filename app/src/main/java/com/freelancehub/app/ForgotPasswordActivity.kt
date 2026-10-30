package com.freelancehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
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

private enum class RecoveryMethod { EMAIL, PHONE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForgotPasswordScreen(
    auth: AuthService,
    onBack: () -> Unit,
    onOpenApp: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var method by remember { mutableStateOf(RecoveryMethod.EMAIL) }
    var identifier by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+971") }
    var phoneDigits by remember { mutableStateOf("") }
    var showCountryPicker by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }
    val phoneTarget = countryCode + phoneDigits.filter(Char::isDigit)
    var otp by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    val navy = Color(0xFF102B57)
    val blue = Color(0xFF315FCB)
    val isPhone = method == RecoveryMethod.PHONE
    val scrollState = rememberScrollState()
    val nextBringIntoView = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)

    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            nextBringIntoView.bringIntoView()
        } else {
            // Restore the form to its normal position after the keyboard is dismissed.
            scrollState.animateScrollTo(0)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().imePadding(),
        color = Color(0xFFF1F5FD)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE5EEFF), Color(0xFFF8FAFF))))
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (step == 1) onBack() else { step--; error = ""; info = "" } }, enabled = !busy) {
                    Text(if (step == 1) "Back" else "Previous")
                }
                Spacer(Modifier.weight(1f))
                Text("Freelance Hub", style = MaterialTheme.typography.titleLarge, color = navy)
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    Text(
                        when (step) {
                            1 -> "Recover your account"
                            2 -> "Verify your account"
                            else -> "Create a new password"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = navy
                    )
                    Text(
                        when (step) {
                            1 -> "Enter the email address or mobile number already registered to your account. We will send a one-time code."
                            2 -> "Enter the 8-digit verification code sent to your registered contact."
                            else -> "Changing your password is optional. You can set a new password now or skip and continue to your account."
                        },
                        color = Color(0xFF53627A),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier.weight(1f).height(5.dp).background(
                                    if (index < step) blue else Color(0xFFE2E8F2),
                                    RoundedCornerShape(8.dp)
                                )
                            )
                        }
                    }

                    if (showCountryPicker) {
                        val filteredCountries = CountryCallingCodes.all.filter {
                            it.first.contains(countrySearch, ignoreCase = true) || it.second.contains(countrySearch)
                        }
                        AlertDialog(
                            onDismissRequest = { showCountryPicker = false; countrySearch = "" },
                            title = { Text("Choose country code", color = navy) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = countrySearch,
                                        onValueChange = { countrySearch = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Search country or code") },
                                        placeholder = { Text("Country name or +971") },
                                        singleLine = true
                                    )
                                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                        items(filteredCountries) { country ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    countryCode = country.second
                                                    showCountryPicker = false
                                                    countrySearch = ""
                                                    error = ""
                                                }.padding(vertical = 10.dp, horizontal = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(country.first, color = navy)
                                                Text(country.second, color = blue)
                                            }
                                        }
                                    }
                                    if (filteredCountries.isEmpty()) Text("No matching country found.")
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showCountryPicker = false; countrySearch = "" }) { Text("Done") }
                            }
                        )
                    }

                    if (step == 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (method == RecoveryMethod.EMAIL) {
                                Button(
                                    onClick = { method = RecoveryMethod.EMAIL; identifier = ""; error = "" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Email") }
                            } else {
                                OutlinedButton(
                                    onClick = { method = RecoveryMethod.EMAIL; identifier = ""; error = "" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Email") }
                            }
                            if (method == RecoveryMethod.PHONE) {
                                Button(
                                    onClick = { method = RecoveryMethod.PHONE; identifier = ""; error = "" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Mobile") }
                            } else {
                                OutlinedButton(
                                    onClick = { method = RecoveryMethod.PHONE; identifier = ""; error = "" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Mobile") }
                            }
                        }
                        if (isPhone) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { showCountryPicker = true },
                                    enabled = !busy,
                                    modifier = Modifier.weight(0.38f).height(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) { Text(countryCode, color = navy) }
                                OutlinedTextField(
                                    value = phoneDigits,
                                    onValueChange = { phoneDigits = it.filter(Char::isDigit).take(14); error = "" },
                                    modifier = Modifier.weight(0.62f),
                                    label = { Text("Mobile number") },
                                    placeholder = { Text("Enter phone number") },
                                    singleLine = true,
                                    enabled = !busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                                )
                            }
                            Text(
                                "Choose your country code, then enter the mobile number without the country code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF69778D)
                            )
                        } else {
                            OutlinedTextField(
                                value = identifier,
                                onValueChange = { identifier = it; error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Registered email address") },
                                placeholder = { Text("Enter your email address") },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                            )
                            Text(
                                "Use the email address currently linked to your Freelance Hub account. We will send an 8-digit code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF69778D)
                            )
                        }
                        Button(
                            onClick = {
                                val target = identifier.trim()
                                val valid = if (isPhone) {
                                    target.matches(Regex("""^\+[1-9][0-9]{7,14}$"""))
                                } else {
                                    target.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))
                                }
                                if (!valid) {
                                    error = if (isPhone) {
                                        "Enter a valid mobile number for the selected country code."
                                    } else {
                                        "Enter a valid registered email address."
                                    }
                                } else {
                                    busy = true
                                    error = ""
                                    info = ""
                                    auth.requestRecoveryCode(target, isPhone) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess {
                                                step = 2
                                                info = if (isPhone) "An 8-digit verification code has been requested by SMS." else "An 8-digit verification code has been requested by email. Check your inbox and spam folder."
                                            }.onFailure {
                                                error = it.message ?: "Could not send the verification code."
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nextBringIntoView),
                            enabled = !busy,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Next")
                        }
                    } else if (step == 2) {
                        Text(
                            if (isPhone) "Code destination: " + phoneTarget else "Code destination: " + identifier,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF53627A)
                        )
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { value -> otp = value.filter(Char::isDigit).take(8); error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("8-digit verification code") },
                            placeholder = { Text("Enter 8 digits") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Button(
                            onClick = {
                                if (!otp.matches(Regex("^[0-9]{8}$"))) {
                                    error = "Enter the 8-digit verification code sent to your contact."
                                } else {
                                    busy = true
                                    error = ""
                                    auth.verifyRecoveryCode(if (isPhone) phoneTarget else identifier.trim(), otp.trim(), isPhone) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess {
                                                step = 3
                                                info = "Your contact is verified. You are ready to continue."
                                            }.onFailure {
                                                error = it.message ?: "The verification code could not be confirmed."
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nextBringIntoView),
                            enabled = !busy,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Next")
                        }
                        TextButton(
                            onClick = {
                                busy = true
                                error = ""
                                auth.requestRecoveryCode(if (isPhone) phoneTarget else identifier.trim(), isPhone) { result ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        busy = false
                                        result.onSuccess { info = "A new code has been requested. Use the newest code." }
                                            .onFailure { error = it.message ?: "Could not resend the code." }
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("Resend code") }
                    } else {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New password (optional)") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show")
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
                            enabled = !busy && password.isNotBlank(),
                            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { confirmVisible = !confirmVisible }) {
                                    Text(if (confirmVisible) "Hide" else "Show")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        Button(
                            onClick = {
                                if (password.isBlank()) {
                                    onOpenApp()
                                } else if (password.length < 8) {
                                    error = "Use at least 8 characters for your new password."
                                } else if (password != confirmPassword) {
                                    error = "The passwords do not match."
                                } else {
                                    busy = true
                                    error = ""
                                    auth.updateVerifiedRecoveryPassword(password) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            busy = false
                                            result.onSuccess { onOpenApp() }
                                                .onFailure { error = it.message ?: "Could not update your password." }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nextBringIntoView),
                            enabled = !busy,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (password.isBlank()) "Continue to Freelance Hub" else "Update password & continue")
                        }
                        TextButton(
                            onClick = onOpenApp,
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("Skip password and continue") }
                    }

                    if (info.isNotBlank()) {
                        Text(info, color = Color(0xFF28734A), style = MaterialTheme.typography.bodySmall)
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
