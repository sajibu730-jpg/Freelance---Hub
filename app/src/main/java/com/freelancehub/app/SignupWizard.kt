package com.freelancehub.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.freelancehub.app.auth.AuthService
import com.freelancehub.app.auth.AuthSession
import com.freelancehub.app.data.UserRole
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.data.RemoteAccountRepository
import com.freelancehub.app.network.SecureApiClient

@Composable
fun SignupWizard(auth: AuthService, onComplete: (AuthSession) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("United Arab Emirates") }
    var gender by remember { mutableStateOf("Prefer not to say") }
    var birthday by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.FREELANCER) }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    var mobileCode by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var profilePhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var profilePhotoType by remember { mutableStateOf<String?>(null) }
    var coverPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var coverPhotoType by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var pendingPhotoTarget by remember { mutableStateOf("profile") }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val type = context.contentResolver.getType(uri)?.lowercase()
                    ?: error("Could not identify the selected image format.")
                require(type in setOf("image/jpeg", "image/png", "image/webp")) {
                    "Choose a JPG, PNG or WebP image."
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected image.")
                require(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) {
                    "Each image must be 5 MB or smaller."
                }
                if (pendingPhotoTarget == "profile") {
                    profilePhotoBytes = bytes
                    profilePhotoType = type
                } else {
                    coverPhotoBytes = bytes
                    coverPhotoType = type
                }
                error = ""
            }.onFailure { error = it.message ?: "Image selection failed." }
        }
    }
    val navy = Color(0xFF142B52)
    val blue = Color(0xFF4169E1)
    val teal = Color(0xFF14A89A)

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F8FF)).verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step == 0) onCancel() else { step--; error = ""; message = "" } }) { Icon(Icons.Filled.ArrowBack, null, tint = navy) }
            Column {
                Text("Create your account", color = navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Your work. Your world. Your future.", color = Color.Gray)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(5) { i -> Box(Modifier.weight(1f).height(5.dp).background(if (i <= step) blue else Color(0xFFDCE5F5), RoundedCornerShape(5.dp))) }
        }
        Text("STEP ${step + 1} OF 5", color = blue, style = MaterialTheme.typography.labelMedium)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                when (step) {
                    0 -> {
                        Text("Personal information", color = navy, style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, leadingIcon = { Icon(Icons.Filled.Person, null) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(country, { country = it }, label = { Text("Country") }, leadingIcon = { Icon(Icons.Filled.Public, null) }, modifier = Modifier.fillMaxWidth())
                        var genderOpen by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(genderOpen, { genderOpen = it }) {
                            OutlinedTextField(gender, {}, readOnly = true, label = { Text("Gender") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genderOpen) })
                            ExposedDropdownMenu(genderOpen, { genderOpen = false }) {
                                listOf("Male", "Female", "Other", "Prefer not to say").forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { gender = v; genderOpen = false }) }
                            }
                        }
                        OutlinedTextField(birthday, { birthday = it }, label = { Text("Birthday (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                        Text("I want to join as", color = navy, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(role == UserRole.FREELANCER, { role = UserRole.FREELANCER }, label = { Text("Freelancer") })
                            FilterChip(role == UserRole.CLIENT, { role = UserRole.CLIENT }, label = { Text("Client") })
                        }
                    }
                    1 -> {
                        Text("Account details", color = navy, style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(email, { email = it }, label = { Text("Email address") }, leadingIcon = { Icon(Icons.Filled.Email, null) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(phone, { phone = it }, label = { Text("Mobile with country code, e.g. +971...") }, leadingIcon = { Icon(Icons.Filled.Phone, null) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password (8+ characters)") }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("I agree to the Rules & Guidelines") }
                    }
                    2 -> {
                        Text("Verify your email", color = navy, style = MaterialTheme.typography.headlineSmall)
                        Text("Enter the 8-digit code sent to $email.")
                        OutlinedTextField(emailCode, { emailCode = it.filter(Char::isDigit).take(8) }, label = { Text("8-digit email code") }, modifier = Modifier.fillMaxWidth())
                    }
                    3 -> {
                        Text("Verify your mobile", color = navy, style = MaterialTheme.typography.headlineSmall)
                        Text("Enter the 8-digit SMS code sent to $phone.")
                        OutlinedTextField(mobileCode, { mobileCode = it.filter(Char::isDigit).take(8) }, label = { Text("8-digit SMS code") }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy, onClick = { session?.let { s -> busy = true; auth.signupMobileVerification(s, "send", phone.trim()) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { message = "A new code was requested." }.onFailure { error = it.message ?: "Could not resend code." } } } } }) { Text("Resend code") }
                    }
                    4 -> {
                        Text("Profile photo & cover photo", color = navy, style = MaterialTheme.typography.headlineSmall)
                        Text("Add your profile and cover images to complete your public profile.")
                        OutlinedButton(
                            onClick = { pendingPhotoTarget = "profile"; photoPicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Person, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (profilePhotoBytes == null) "Choose profile photo" else "Profile photo selected")
                        }
                        OutlinedButton(
                            onClick = { pendingPhotoTarget = "cover"; photoPicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Public, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (coverPhotoBytes == null) "Choose cover photo" else "Cover photo selected")
                        }
                        Text("JPG, PNG or WebP • Maximum 5 MB each", color = Color.Gray)
                        Text("You can skip either image and add it later.", color = Color.Gray)
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (message.isNotBlank()) Text(message, color = teal)
                Button(enabled = !busy, onClick = {
                    error = ""; message = ""
                    when (step) {
                        0 -> if (name.trim().length < 2 || country.isBlank() || !birthday.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) error = "Enter your name, country and birthday as YYYY-MM-DD." else step = 1
                        1 -> if (!Validation.email(email) || !phone.matches(Regex("""^\+[1-9][0-9]{7,14}$""")) || password.length < 8 || password != confirm || !accepted) error = "Check email, mobile number, matching passwords and Rules acceptance." else {
                            busy = true
                            auth.beginSignup(name, email, password, role, country, gender, birthday) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { step = 2; message = "Check your inbox for the 8-digit code." }.onFailure { error = it.message ?: "Could not create account." } } }
                        }
                        2 -> if (emailCode.length != 8) error = "Enter the 8-digit email code." else {
                            busy = true
                            auth.verifySignupEmail(email, emailCode) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                                busy = false
                                r.onSuccess { s -> session = s; step = 3; busy = true; auth.signupMobileVerification(s, "send", phone.trim()) { sent -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; sent.onSuccess { message = "The 8-digit SMS code was requested." }.onFailure { error = it.message ?: "SMS request failed." } } } }.onFailure { error = it.message ?: "Email verification failed." }
                            } }
                        }
                        3 -> if (mobileCode.length != 8) error = "Enter the 8-digit SMS code." else session?.let { s ->
                            busy = true
                            auth.signupMobileVerification(s, "verify", phone.trim(), mobileCode) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { step = 4; message = "Mobile number verified." }.onFailure { error = it.message ?: "Mobile verification failed." } } }
                        } ?: run { error = "Signup session expired. Please start again." }
                        4 -> session?.let { s ->
                            busy = true
                            Thread {
                                val result = runCatching {
                                    val client = SecureApiClient(RuntimeBackendConfig.current())
                                    val account = RemoteAccountRepository(client) { s.accessToken }
                                    account.saveSignupProfile(name, country, gender, birthday)
                                    profilePhotoBytes?.let { bytes ->
                                        account.updateProfilePhoto(bytes, profilePhotoType ?: "image/jpeg")
                                    }
                                    coverPhotoBytes?.let { bytes ->
                                        account.updateCoverPhoto(bytes, coverPhotoType ?: "image/jpeg")
                                    }
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    busy = false
                                    result.onSuccess {
                                        auth.completeSignup(s)
                                        onComplete(s)
                                    }.onFailure {
                                        error = it.message ?: "Could not save your profile. Please try again."
                                    }
                                }
                            }.start()
                        } ?: run { error = "Signup session expired. Please start again." }
                    }
                }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = blue)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(if (step == 4) "Complete account setup" else "Continue")
                }
            }
        }
    }
}
