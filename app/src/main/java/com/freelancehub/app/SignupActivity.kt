package com.freelancehub.app

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.freelancehub.app.auth.AuthService
import com.freelancehub.app.data.RemoteAccountRepository
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.data.UserRole
import com.freelancehub.app.network.SecureApiClient
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SignupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AuthService(this)
        setContent {
            MaterialTheme {
                SignupFlow(auth = auth) {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                    finish()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignupFlow(auth: AuthService, onComplete: () -> Unit) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val navy = Color(0xFF10264D)
    val blue = Color(0xFF246BCE)
    val pale = Color(0xFFF2F6FF)
    val ink = Color(0xFF26364D)

    var stage by remember { mutableIntStateOf(0) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf(UserRole.FREELANCER) }
    var country by remember { mutableStateOf("United Arab Emirates") }
    var dialCode by remember { mutableStateOf("+971") }
    var mobileLocal by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var birthday by remember { mutableStateOf("") }
    var acceptedRules by remember { mutableStateOf(false) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var photoBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var emailOtp by remember { mutableStateOf("") }
    var phoneOtp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showCountryPicker by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }
    var showGenderPicker by remember { mutableStateOf(false) }

    val fullPhone = dialCode + mobileLocal.filter(Char::isDigit).removePrefix("0")
    val emailValid = email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
    val passwordValid = password.length >= 8 && password.any(Char::isLetter) && password.any(Char::isDigit)
    val formValid = fullName.trim().length >= 2 && emailValid && passwordValid &&
        password == confirmPassword && mobileLocal.filter(Char::isDigit).length >= 6 &&
        gender.isNotBlank() && birthday.isNotBlank() && acceptedRules && photoBytes != null &&
        fullPhone.matches(Regex("^\\+[1-9][0-9]{7,14}$")) && auth.isConfigured()

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected photo.")
                val original = input.use { it.readBytes() }
                val decoded = BitmapFactory.decodeByteArray(original, 0, original.size)
                    ?: error("Choose a valid image file.")
                val maxSide = 1280
                val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
                val resized = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else decoded
                val output = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 86, output)
                val bytes = output.toByteArray()
                require(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) {
                    "The photo must be no larger than 5 MB."
                }
                photoBytes = bytes
                photoBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                error = ""
            }.onFailure { error = it.message ?: "Could not load this photo." }
        }
    }

    fun chooseBirthday() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                birthday = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
            },
            now.get(Calendar.YEAR) - 20,
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    fun submitSignup() {
        if (!formValid || busy) return
        busy = true
        error = ""
        message = ""
        auth.authenticate(
            signup = true,
            name = fullName.trim(),
            email = email.trim(),
            password = password,
            role = role,
            signupMetadata = mapOf(
                "country" to country,
                "mobile_number" to fullPhone,
                "gender" to gender,
                "birthday" to birthday
            )
        ) { result ->
            handler.post {
                busy = false
                result.onSuccess {
                    error = "An unexpected session was returned before email confirmation. Please sign in and continue verification."
                }.onFailure { failure ->
                    val text = failure.message.orEmpty()
                    if (text.contains("Account created. Check your email", ignoreCase = true)) {
                        stage = 1
                        message = "We sent an 8-digit verification code to $email."
                    } else {
                        error = text.ifBlank { "Could not create your account. Please try again." }
                    }
                }
            }
        }
    }

    fun verifyEmail() {
        if (busy) return
        busy = true
        error = ""
        auth.verifySignupEmailOtp(email, emailOtp) { result ->
            handler.post {
                result.onSuccess {
                    auth.requestPhoneChange(fullPhone) { phoneResult ->
                        handler.post {
                            busy = false
                            phoneResult.onSuccess {
                                stage = 2
                                message = "We sent an 8-digit verification code to your mobile number."
                            }.onFailure { failure ->
                                error = failure.message ?: "Could not send the mobile verification code."
                            }
                        }
                    }
                }.onFailure {
                    busy = false
                    error = it.message ?: "Email verification failed."
                }
            }
        }
    }

    fun verifyPhone() {
        if (busy) return
        busy = true
        error = ""
        auth.verifySignupPhoneOtp(fullPhone, phoneOtp) { result ->
            handler.post {
                result.onSuccess {
                    Thread {
                        val saved = runCatching {
                            val api = SecureApiClient(RuntimeBackendConfig.current())
                            val repository = RemoteAccountRepository(api) {
                                auth.accessTokenForApiBlocking()
                            }
                            repository.createSignupProfile(
                                displayName = fullName.trim(),
                                country = country,
                                mobileNumber = fullPhone,
                                gender = gender,
                                birthday = birthday,
                                photoBytes = photoBytes ?: error("Select a profile photo before continuing."),
                                photoContentType = "image/jpeg"
                            )
                        }
                        handler.post {
                            busy = false
                            saved.onSuccess {
                                stage = 3
                                message = "Your email, mobile number and profile have been verified and saved."
                                onComplete()
                            }.onFailure { failure ->
                                error = failure.message ?: "Your profile could not be saved. Please try again."
                            }
                        }
                    }.start()
                }.onFailure {
                    busy = false
                    error = it.message ?: "Mobile verification failed."
                }
            }
        }
    }

    if (showCountryPicker) {
        val filtered = CountryCallingCodes.all.filter {
            it.first.contains(countrySearch, ignoreCase = true) || it.second.contains(countrySearch)
        }
        AlertDialog(
            onDismissRequest = { showCountryPicker = false; countrySearch = "" },
            title = { Text("Select your country") },
            text = {
                Column {
                    OutlinedTextField(
                        value = countrySearch,
                        onValueChange = { countrySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search country") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered) { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    country = item.first
                                    dialCode = item.second
                                    showCountryPicker = false
                                    countrySearch = ""
                                }.padding(vertical = 12.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.first, Modifier.weight(1f))
                                Text(item.second, color = blue)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false; countrySearch = "" }) { Text("Close") }
            }
        )
    }

    if (showGenderPicker) {
        AlertDialog(
            onDismissRequest = { showGenderPicker = false },
            title = { Text("Select gender") },
            text = {
                Column {
                    listOf("Male", "Female", "Prefer not to say").forEach { option ->
                        Text(
                            option,
                            Modifier.fillMaxWidth().clickable {
                                gender = option
                                showGenderPicker = false
                            }.padding(14.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGenderPicker = false }) { Text("Close") } }
        )
    }

    Column(
        Modifier.fillMaxSize().background(pale).verticalScroll(rememberScrollState()).imePadding()
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF071B43), Color(0xFF1557A8), Color(0xFF2677D8)))
            ).padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFE2F3FF)) {
                    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        Text("F", style = MaterialTheme.typography.headlineLarge, color = blue)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Create your account", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Your work. Your world. Your future.", color = Color(0xFFD9E9FF))
                Spacer(Modifier.height(12.dp))
                Text(
                    when (stage) {
                        0 -> "PERSONAL DETAILS  •  STEP 1 OF 3"
                        1 -> "EMAIL VERIFICATION  •  STEP 2 OF 3"
                        2 -> "MOBILE VERIFICATION  •  STEP 3 OF 3"
                        else -> "ACCOUNT READY"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFC6E7FF)
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { index ->
                        Box(
                            Modifier.weight(1f).height(4.dp)
                                .clip(CircleShape)
                                .background(if (index <= stage) Color.White else Color(0x557FAFE8))
                        )
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (stage == 0) {
                    Text("Personal information", style = MaterialTheme.typography.titleLarge, color = navy)
                    Text("Set up your global freelance profile.", color = ink)

                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(104.dp).clickable { photoPicker.launch("image/*") },
                                shape = CircleShape,
                                color = Color(0xFFEAF1FF),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD1DDF4))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (photoBitmap != null) {
                                        Image(photoBitmap!!, null, Modifier.fillMaxSize().clip(CircleShape))
                                    } else {
                                        Icon(Icons.Filled.AddAPhoto, "Add profile photo", tint = blue, modifier = Modifier.size(34.dp))
                                    }
                                }
                            }
                            TextButton(onClick = { photoPicker.launch("image/*") }) {
                                Text(if (photoBytes == null) "Add profile photo" else "Change photo")
                            }
                            Text("Clear photo • Good lighting • Up to 5 MB", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }

                    OutlinedTextField(
                        value = fullName, onValueChange = { fullName = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Full name") }, leadingIcon = { Icon(Icons.Filled.Person, null) },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Text("I want to join as", color = navy, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = role == UserRole.FREELANCER,
                            onClick = { role = UserRole.FREELANCER },
                            label = { Text("Freelancer") },
                            leadingIcon = { Icon(Icons.Filled.Work, null) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = role == UserRole.CLIENT,
                            onClick = { role = UserRole.CLIENT },
                            label = { Text("Client") },
                            leadingIcon = { Icon(Icons.Filled.Business, null) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedButton(
                        onClick = { showCountryPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.Public, null)
                        Spacer(Modifier.width(8.dp))
                        Text("$country  •  $dialCode")
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, null)
                    }

                    OutlinedTextField(
                        value = mobileLocal,
                        onValueChange = { mobileLocal = it.filter(Char::isDigit).take(15) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Mobile number") },
                        leadingIcon = { Text(dialCode, color = blue) },
                        placeholder = { Text("Enter number without country code") },
                        shape = RoundedCornerShape(14.dp)
                    )

                    OutlinedButton(
                        onClick = { showGenderPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.PersonOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text(gender.ifBlank { "Select gender" })
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, null)
                    }

                    OutlinedButton(
                        onClick = { chooseBirthday() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.CalendarMonth, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (birthday.isBlank()) "Date of birth" else birthday)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, null)
                    }

                    HorizontalDivider()
                    Text("Account details", style = MaterialTheme.typography.titleLarge, color = navy)
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Email address") }, leadingIcon = { Icon(Icons.Filled.Email, null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Password") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Toggle password visibility")
                                }
                                TextButton(enabled = password.isNotEmpty(), onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Password", password))
                                }) { Text("Copy") }
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Text("At least 8 characters, including a letter and a number.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    OutlinedTextField(
                        value = confirmPassword, onValueChange = { confirmPassword = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Confirm password") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                Icon(if (confirmVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Toggle confirm password visibility")
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acceptedRules, onCheckedChange = { acceptedRules = it })
                        Text("I agree to the Rules & Guidelines and Privacy Policy.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (password.isNotEmpty() && confirmPassword.isNotEmpty() && password != confirmPassword) {
                        Text("Passwords do not match.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { submitSignup() },
                        enabled = formValid && !busy,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = blue)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Continue to email verification")
                    }
                    TextButton(onClick = { (context as? ComponentActivity)?.finish() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Already have an account? Sign in")
                    }
                } else if (stage == 1) {
                    Icon(Icons.Filled.MarkEmailRead, null, tint = blue, modifier = Modifier.size(42.dp))
                    Text("Verify your email", style = MaterialTheme.typography.titleLarge, color = navy)
                    Text("Enter the 8-digit code sent to $email.", color = ink)
                    OutlinedTextField(
                        value = emailOtp,
                        onValueChange = { emailOtp = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("8-digit email code") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(onClick = { verifyEmail() }, enabled = emailOtp.length == 8 && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Verify email")
                    }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        auth.resendSignupEmailOtp(email) { result ->
                            handler.post {
                                busy = false
                                result.onSuccess { message = "A new email code has been requested." }
                                    .onFailure { error = it.message ?: "Could not resend email code." }
                            }
                        }
                    }) { Text("Resend email code") }
                } else if (stage == 2) {
                    Icon(Icons.Filled.Sms, null, tint = blue, modifier = Modifier.size(42.dp))
                    Text("Verify your mobile number", style = MaterialTheme.typography.titleLarge, color = navy)
                    Text("Enter the 8-digit SMS code sent to $fullPhone.", color = ink)
                    OutlinedTextField(
                        value = phoneOtp,
                        onValueChange = { phoneOtp = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("8-digit SMS code") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(onClick = { verifyPhone() }, enabled = phoneOtp.length == 8 && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Verify and create profile")
                    }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        auth.requestPhoneChange(fullPhone) { result ->
                            handler.post {
                                busy = false
                                result.onSuccess { message = "A new SMS code has been requested." }
                                    .onFailure { error = it.message ?: "Could not resend SMS code." }
                            }
                        }
                    }) { Text("Resend SMS code") }
                } else {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF15966E), modifier = Modifier.size(48.dp))
                    Text("Your account is ready", style = MaterialTheme.typography.titleLarge, color = navy)
                    Text(message, color = ink)
                }

                if (message.isNotBlank() && stage < 3) {
                    Text(message, color = Color(0xFF167B62), style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
