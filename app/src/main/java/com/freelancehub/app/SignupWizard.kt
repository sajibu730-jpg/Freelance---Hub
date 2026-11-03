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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.freelancehub.app.auth.AuthService
import com.freelancehub.app.auth.AuthSession
import com.freelancehub.app.data.UserRole
import com.freelancehub.app.data.RuntimeBackendConfig
import com.freelancehub.app.data.RemoteAccountRepository
import com.freelancehub.app.network.SecureApiClient

private val signupCountryDialCodes = listOf(
    "Afghanistan" to "+93",
    "Albania" to "+355",
    "Algeria" to "+213",
    "American Samoa" to "+1",
    "Andorra" to "+376",
    "Angola" to "+244",
    "Anguilla" to "+1",
    "Antigua and Barbuda" to "+1",
    "Argentina" to "+54",
    "Armenia" to "+374",
    "Aruba" to "+297",
    "Australia" to "+61",
    "Austria" to "+43",
    "Azerbaijan" to "+994",
    "Bahamas" to "+1",
    "Bahrain" to "+973",
    "Bangladesh" to "+880",
    "Barbados" to "+1",
    "Belarus" to "+375",
    "Belgium" to "+32",
    "Belize" to "+501",
    "Benin" to "+229",
    "Bermuda" to "+1",
    "Bhutan" to "+975",
    "Bolivia" to "+591",
    "Bonaire, Sint Eustatius and Saba" to "+599",
    "Bosnia and Herzegovina" to "+387",
    "Botswana" to "+267",
    "Brazil" to "+55",
    "British Indian Ocean Territory" to "+246",
    "British Virgin Islands" to "+1",
    "Brunei" to "+673",
    "Bulgaria" to "+359",
    "Burkina Faso" to "+226",
    "Burundi" to "+257",
    "Cambodia" to "+855",
    "Cameroon" to "+237",
    "Canada" to "+1",
    "Cape Verde" to "+238",
    "Cayman Islands" to "+1",
    "Central African Republic" to "+236",
    "Chad" to "+235",
    "Chile" to "+56",
    "China" to "+86",
    "Christmas Island" to "+61",
    "Cocos (Keeling) Islands" to "+61",
    "Colombia" to "+57",
    "Comoros" to "+269",
    "Congo" to "+242",
    "Cook Islands" to "+682",
    "Costa Rica" to "+506",
    "Côte d’Ivoire" to "+225",
    "Croatia" to "+385",
    "Cuba" to "+53",
    "Curaçao" to "+599",
    "Cyprus" to "+357",
    "Czechia" to "+420",
    "Democratic Republic of the Congo" to "+243",
    "Denmark" to "+45",
    "Djibouti" to "+253",
    "Dominica" to "+1",
    "Dominican Republic" to "+1",
    "Ecuador" to "+593",
    "Egypt" to "+20",
    "El Salvador" to "+503",
    "Equatorial Guinea" to "+240",
    "Eritrea" to "+291",
    "Estonia" to "+372",
    "Eswatini" to "+268",
    "Ethiopia" to "+251",
    "Falkland Islands" to "+500",
    "Faroe Islands" to "+298",
    "Fiji" to "+679",
    "Finland" to "+358",
    "France" to "+33",
    "French Guiana" to "+594",
    "French Polynesia" to "+689",
    "French Southern Territories" to "+262",
    "Gabon" to "+241",
    "Gambia" to "+220",
    "Georgia" to "+995",
    "Germany" to "+49",
    "Ghana" to "+233",
    "Gibraltar" to "+350",
    "Greece" to "+30",
    "Greenland" to "+299",
    "Grenada" to "+1",
    "Guadeloupe" to "+590",
    "Guam" to "+1",
    "Guatemala" to "+502",
    "Guernsey" to "+44",
    "Guinea" to "+224",
    "Guinea-Bissau" to "+245",
    "Guyana" to "+592",
    "Haiti" to "+509",
    "Honduras" to "+504",
    "Hong Kong" to "+852",
    "Hungary" to "+36",
    "Iceland" to "+354",
    "India" to "+91",
    "Indonesia" to "+62",
    "Iran" to "+98",
    "Iraq" to "+964",
    "Ireland" to "+353",
    "Isle of Man" to "+44",
    "Israel" to "+972",
    "Italy" to "+39",
    "Jamaica" to "+1",
    "Japan" to "+81",
    "Jersey" to "+44",
    "Jordan" to "+962",
    "Kazakhstan" to "+7",
    "Kenya" to "+254",
    "Kiribati" to "+686",
    "Kosovo" to "+383",
    "Kuwait" to "+965",
    "Kyrgyzstan" to "+996",
    "Laos" to "+856",
    "Latvia" to "+371",
    "Lebanon" to "+961",
    "Lesotho" to "+266",
    "Liberia" to "+231",
    "Libya" to "+218",
    "Liechtenstein" to "+423",
    "Lithuania" to "+370",
    "Luxembourg" to "+352",
    "Macao" to "+853",
    "Madagascar" to "+261",
    "Malawi" to "+265",
    "Malaysia" to "+60",
    "Maldives" to "+960",
    "Mali" to "+223",
    "Malta" to "+356",
    "Marshall Islands" to "+692",
    "Martinique" to "+596",
    "Mauritania" to "+222",
    "Mauritius" to "+230",
    "Mayotte" to "+262",
    "Mexico" to "+52",
    "Micronesia" to "+691",
    "Moldova" to "+373",
    "Monaco" to "+377",
    "Mongolia" to "+976",
    "Montenegro" to "+382",
    "Montserrat" to "+1",
    "Morocco" to "+212",
    "Mozambique" to "+258",
    "Myanmar" to "+95",
    "Namibia" to "+264",
    "Nauru" to "+674",
    "Nepal" to "+977",
    "Netherlands" to "+31",
    "New Caledonia" to "+687",
    "New Zealand" to "+64",
    "Nicaragua" to "+505",
    "Niger" to "+227",
    "Nigeria" to "+234",
    "Niue" to "+683",
    "Norfolk Island" to "+672",
    "North Korea" to "+850",
    "North Macedonia" to "+389",
    "Northern Mariana Islands" to "+1",
    "Norway" to "+47",
    "Oman" to "+968",
    "Pakistan" to "+92",
    "Palau" to "+680",
    "Palestine" to "+970",
    "Panama" to "+507",
    "Papua New Guinea" to "+675",
    "Paraguay" to "+595",
    "Peru" to "+51",
    "Philippines" to "+63",
    "Pitcairn Islands" to "+64",
    "Poland" to "+48",
    "Portugal" to "+351",
    "Puerto Rico" to "+1",
    "Qatar" to "+974",
    "Réunion" to "+262",
    "Romania" to "+40",
    "Russia" to "+7",
    "Rwanda" to "+250",
    "Saint Barthélemy" to "+590",
    "Saint Helena, Ascension and Tristan da Cunha" to "+290",
    "Saint Kitts and Nevis" to "+1",
    "Saint Lucia" to "+1",
    "Saint Martin" to "+590",
    "Saint Pierre and Miquelon" to "+508",
    "Saint Vincent and the Grenadines" to "+1",
    "Samoa" to "+685",
    "San Marino" to "+378",
    "São Tomé and Príncipe" to "+239",
    "Saudi Arabia" to "+966",
    "Senegal" to "+221",
    "Serbia" to "+381",
    "Seychelles" to "+248",
    "Sierra Leone" to "+232",
    "Singapore" to "+65",
    "Sint Maarten" to "+1",
    "Slovakia" to "+421",
    "Slovenia" to "+386",
    "Solomon Islands" to "+677",
    "Somalia" to "+252",
    "South Africa" to "+27",
    "South Korea" to "+82",
    "South Sudan" to "+211",
    "Spain" to "+34",
    "Sri Lanka" to "+94",
    "Sudan" to "+249",
    "Suriname" to "+597",
    "Svalbard and Jan Mayen" to "+47",
    "Sweden" to "+46",
    "Switzerland" to "+41",
    "Syria" to "+963",
    "Taiwan" to "+886",
    "Tajikistan" to "+992",
    "Tanzania" to "+255",
    "Thailand" to "+66",
    "Timor-Leste" to "+670",
    "Togo" to "+228",
    "Tokelau" to "+690",
    "Tonga" to "+676",
    "Trinidad and Tobago" to "+1",
    "Tunisia" to "+216",
    "Türkiye" to "+90",
    "Turkmenistan" to "+993",
    "Turks and Caicos Islands" to "+1",
    "Tuvalu" to "+688",
    "Uganda" to "+256",
    "Ukraine" to "+380",
    "United Arab Emirates" to "+971",
    "United Kingdom" to "+44",
    "United States" to "+1",
    "United States Virgin Islands" to "+1",
    "Uruguay" to "+598",
    "Uzbekistan" to "+998",
    "Vanuatu" to "+678",
    "Vatican City" to "+39",
    "Venezuela" to "+58",
    "Vietnam" to "+84",
    "Wallis and Futuna" to "+681",
    "Western Sahara" to "+212",
    "Yemen" to "+967",
    "Zambia" to "+260",
    "Zimbabwe" to "+263"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupWizard(auth: AuthService, onComplete: (AuthSession) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("United Arab Emirates") }
    var countryIso by remember { mutableStateOf("United Arab Emirates") }
    var phoneRegion by remember { mutableStateOf("United Arab Emirates") }
    var phoneNational by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("Prefer not to say") }
    var birthday by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.FREELANCER) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
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
                    ?: throw IllegalStateException("Could not identify the selected image format.")
                require(type in setOf("image/jpeg", "image/png", "image/webp")) {
                    "Choose a JPG, PNG or WebP image."
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected image.")
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

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F8FF)).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SignupBrandHeader()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step == 0) onCancel() else { step--; error = ""; message = "" } }) { Icon(Icons.Filled.ArrowBack, null, tint = navy) }
            Column {
                Text("Create your account", color = navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Your work. Your world. Your future.", color = Color(0xFF61728E))
            }
        }
        SignupStageProgress(step)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                when (step) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                onClick = { pendingPhotoTarget = "profile"; photoPicker.launch("image/*") },
                                modifier = Modifier.size(76.dp),
                                shape = RoundedCornerShape(50),
                                color = Color(0xFFE6F2FF)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.CameraAlt, contentDescription = "Add profile photo", tint = Color(0xFF0759B8), modifier = Modifier.size(32.dp))
                                }
                            }
                            Text("Add Profile Photo", color = Color(0xFF0759B8), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text("Optional now", color = Color(0xFF7184A3), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("Create New Account", color = navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Let's get started! Fill in your personal information.", color = Color(0xFF667895), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(name, { name = it }, label = { Text("Full Name") }, leadingIcon = { Icon(Icons.Filled.Person, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        var roleOpen by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(roleOpen, { roleOpen = it }) {
                            OutlinedTextField(
                                value = if (role == UserRole.FREELANCER) "Freelancer" else "Client",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("I want to join as") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleOpen) },
                                leadingIcon = { Icon(Icons.Filled.Person, null) },
                                singleLine = true
                            )
                            ExposedDropdownMenu(roleOpen, { roleOpen = false }) {
                                DropdownMenuItem(text = { Text("Freelancer") }, onClick = { role = UserRole.FREELANCER; roleOpen = false })
                                DropdownMenuItem(text = { Text("Client") }, onClick = { role = UserRole.CLIENT; roleOpen = false })
                            }
                        }
                        SignupCountryPicker(
                            label = "Country",
                            selectedRegion = countryIso,
                            onSelected = { region, display -> countryIso = region; country = display }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            SignupCountryPicker(
                                label = "Code",
                                selectedRegion = phoneRegion,
                                onSelected = { region, _ -> phoneRegion = region },
                                modifier = Modifier.weight(0.42f),
                                showDialCode = true
                            )
                            OutlinedTextField(
                                value = phoneNational,
                                onValueChange = { phoneNational = it.filter(Char::isDigit).take(14) },
                                label = { Text("Mobile Number") },
                                leadingIcon = { Icon(Icons.Filled.Phone, null) },
                                modifier = Modifier.weight(0.58f),
                                singleLine = true
                            )
                        }
                        var genderOpen by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(genderOpen, { genderOpen = it }) {
                            OutlinedTextField(gender, {}, readOnly = true, label = { Text("Gender") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genderOpen) })
                            ExposedDropdownMenu(genderOpen, { genderOpen = false }) {
                                listOf("Male", "Female", "Other", "Prefer not to say").forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { gender = v; genderOpen = false }) }
                            }
                        }
                        var birthdayPickerOpen by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = birthday,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Birthday") },
                            placeholder = { Text("Choose your date of birth") },
                            trailingIcon = {
                                TextButton(onClick = { birthdayPickerOpen = true }) { Text("Choose") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (birthdayPickerOpen) {
                            val pickerState = rememberDatePickerState()
                            DatePickerDialog(
                                onDismissRequest = { birthdayPickerOpen = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        pickerState.selectedDateMillis?.let { millis ->
                                            val date = java.time.Instant.ofEpochMilli(millis)
                                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                            birthday = date.toString()
                                        }
                                        birthdayPickerOpen = false
                                    }) { Text("Confirm") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { birthdayPickerOpen = false }) { Text("Cancel") }
                                }
                            ) { DatePicker(state = pickerState) }
                        }
                    }
                    1 -> {
                        Text("Create New Account", color = navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Now, set up your account details.", color = Color(0xFF667895), style = MaterialTheme.typography.bodySmall)
                        SignupStageProgress(1)
                        OutlinedTextField(email, { email = it }, label = { Text("Email Address") }, leadingIcon = { Icon(Icons.Filled.Email, null, tint = Color(0xFF0759B8)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, leadingIcon = { Icon(Icons.Filled.Lock, null, tint = Color(0xFF0759B8)) }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(
                            confirm,
                            { confirm = it },
                            label = { Text("Confirm Password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null, tint = Color(0xFF0759B8)) },
                            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                    Icon(if (confirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("I agree to the Rules & Guidelines", color = navy, fontWeight = FontWeight.SemiBold) }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF0F7FF),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFDCEEFF)) {
                                            Icon(Icons.Filled.Public, null, tint = Color(0xFF0875D1), modifier = Modifier.padding(9.dp).size(22.dp))
                                        }
                                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFDCEEFF)) {
                                            Icon(Icons.Filled.Email, null, tint = Color(0xFF0875D1), modifier = Modifier.padding(9.dp).size(22.dp))
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Box(
                                            modifier = Modifier.width(112.dp).height(66.dp)
                                                .background(Color(0xFF092D67), RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("F", color = Color(0xFF20C9F5), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                                        }
                                        Box(
                                            modifier = Modifier.width(132.dp).height(7.dp)
                                                .background(Color(0xFF8DBCEB), RoundedCornerShape(50))
                                        )
                                    }
                                }
                                Text("Work Globally, Grow Freely", color = navy, fontWeight = FontWeight.Bold)
                                Text("Your skills. Our platform. A better future.", color = Color(0xFF667895), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    2 -> {
                        Text("Verify Your Email", color = navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("We've sent an 8-digit code to your email address.", color = Color(0xFF667895))
                        Text("✉️", style = MaterialTheme.typography.displayLarge)
                        OutlinedTextField(emailCode, { emailCode = it.filter(Char::isDigit).take(8) }, label = { Text("Enter 8-digit email code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    3 -> {
                        Text("Verify Your Mobile", color = navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Enter the 8-digit code sent to your mobile number.", color = Color(0xFF667895))
                        Text("📱", style = MaterialTheme.typography.displayLarge)
                        OutlinedTextField(mobileCode, { mobileCode = it.filter(Char::isDigit).take(8) }, label = { Text("Enter 8-digit SMS code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TextButton(enabled = !busy, onClick = { session?.let { s -> busy = true; auth.signupMobileVerification(s, "send", signupPhone(phoneRegion, phoneNational)) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { message = "A new code was requested." }.onFailure { error = it.message ?: "Could not resend code." } } } } }) { Text("Resend code") }
                    }
                    4 -> {
                        Text("Complete Your Profile", color = navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Add a cover photo to make your profile stand out.", color = Color(0xFF667895))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(142.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF0B4A8F), Color(0xFF48A4E8), Color(0xFFB8DDF8))),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                                Text(if (coverPhotoBytes == null) "Cover photo preview" else "Cover photo selected", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .background(Color(0xFFE6F2FF), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "Profile photo", tint = Color(0xFF0759B8), modifier = Modifier.size(30.dp))
                        }
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
                        0 -> if (name.trim().length < 2 || country.isBlank() || phoneNational.length !in 4..14 || !birthday.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) error = "Enter your name, country and birthday as YYYY-MM-DD." else step = 1
                        1 -> if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() || phoneNational.length !in 4..14 || password.length < 8 || password != confirm || !accepted) error = "Check email, mobile number, matching passwords and Rules acceptance." else {
                            busy = true
                            auth.beginSignup(name, email, password, role, country, gender, birthday) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { step = 2; message = "Check your inbox for the 8-digit code." }.onFailure { error = it.message ?: "Could not create account." } } }
                        }
                        2 -> if (emailCode.length != 8) error = "Enter the 8-digit email code." else {
                            busy = true
                            auth.verifySignupEmail(email, emailCode) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                                busy = false
                                r.onSuccess { s -> session = s; step = 3; busy = true; auth.signupMobileVerification(s, "send", signupPhone(phoneRegion, phoneNational)) { sent -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; sent.onSuccess { message = "The 8-digit SMS code was requested." }.onFailure { error = it.message ?: "SMS request failed." } } } }.onFailure { error = it.message ?: "Email verification failed." }
                            } }
                        }
                        3 -> if (mobileCode.length != 8) error = "Enter the 8-digit SMS code." else session?.let { s ->
                            busy = true
                            auth.signupMobileVerification(s, "verify", signupPhone(phoneRegion, phoneNational), mobileCode) { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; r.onSuccess { step = 4; message = "Mobile number verified." }.onFailure { error = it.message ?: "Mobile verification failed." } } }
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
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(when (step) { 0, 1 -> "Next Step"; 2 -> "Verify Email"; 3 -> "Verify Mobile"; else -> "Complete Sign Up" })
                }
            }
        }
    }
}



@Composable
private fun SignupStageProgress(step: Int) {
    val activeStage = when (step) { 0 -> 0; 1 -> 1; else -> 2 }
    val labels = listOf("Personal Info", "Account Details", "Verification")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier.size(25.dp).background(
                        if (index <= activeStage) Color(0xFF0868D8) else Color.White,
                        RoundedCornerShape(50)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = if (index <= activeStage) Color.White else Color(0xFF7184A3), fontWeight = FontWeight.Bold)
                }
                Text(label, color = if (index == activeStage) Color(0xFF073A83) else Color(0xFF7184A3), style = MaterialTheme.typography.labelSmall, fontWeight = if (index == activeStage) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SignupBrandHeader() {
    Box(
        modifier = Modifier.fillMaxWidth().height(142.dp).background(
            Brush.linearGradient(listOf(Color(0xFF06265F), Color(0xFF0757B8), Color(0xFF0788E8))),
            RoundedCornerShape(22.dp)
        ).padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(48.dp).background(Color(0xFF11C5F4), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("F", color = Color(0xFF052A67), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Freelance Hub", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Your work. Your world. Your future.", color = Color(0xFFDCEBFF), style = MaterialTheme.typography.labelSmall)
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter), color = Color(0xFF1768C7), shape = RoundedCornerShape(50)) {
            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text("GLOBAL FREELANCE MARKETPLACE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignupCountryPicker(
    label: String,
    selectedRegion: String,
    onSelected: (String, String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    showDialCode: Boolean = false
) {
    val regions = remember {
        signupCountryDialCodes.mapNotNull { (countryName, dialCode) ->
            val numericCode = dialCode.removePrefix("+").toIntOrNull() ?: return@mapNotNull null
            Triple(countryName, countryName, numericCode)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val selected = regions.firstOrNull { it.first == selectedRegion }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it; if (!it) search = "" },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (showDialCode) selected?.let { "+${it.third} (${it.first})" } ?: "+971 (AE)" else selected?.second ?: "Choose country",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; search = "" },
            modifier = Modifier.heightIn(max = 340.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search country or code") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                singleLine = true
            )
            regions.filter {
                it.second.contains(search, ignoreCase = true) ||
                    it.first.contains(search, ignoreCase = true) ||
                    "+${it.third}".contains(search.trim())
            }.forEach { item ->
                DropdownMenuItem(
                    text = { Text(if (showDialCode) "${item.second}  (+${item.third})" else item.second) },
                    onClick = {
                        onSelected(item.first, item.second)
                        expanded = false
                        search = ""
                    }
                )
            }
        }
    }
}

private fun signupPhone(region: String, nationalNumber: String): String {
    val callingCode = signupCountryDialCodes.firstOrNull { it.first == region }
        ?.second?.removePrefix("+")?.toIntOrNull()
        ?: PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)
    require(callingCode > 0) { "Choose a valid mobile country code." }
    val digits = nationalNumber.filter(Char::isDigit)
    require(digits.length in 4..14) { "Enter your mobile number." }
    return "+$callingCode$digits"
}
