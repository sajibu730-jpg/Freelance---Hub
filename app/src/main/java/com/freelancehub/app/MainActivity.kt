package com.freelancehub.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freelancehub.app.data.*
import com.freelancehub.app.auth.AuthService
import com.freelancehub.app.update.PlayUpdateManager
import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.TokenStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
class MainActivity:ComponentActivity(){
    private lateinit var playUpdateManager: PlayUpdateManager
    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        playUpdateManager = PlayUpdateManager(this)
        setContent{FreelanceHubApp(AppRepository(this))}
    }
    override fun onResume(){
        super.onResume()
        if (::playUpdateManager.isInitialized) playUpdateManager.resumeIfInterrupted()
    }
    override fun onStart(){
        super.onStart()
        if (::playUpdateManager.isInitialized) playUpdateManager.checkForUpdate()
    }
}
@Composable fun FreelanceHubApp(repo:AppRepository){
    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { com.freelancehub.app.auth.AuthService(context) }
    var loggedIn by remember{mutableStateOf(false)}
    var sessionChecking by remember{mutableStateOf(true)}
    LaunchedEffect(Unit) {
        auth.restoreSession { result ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { session ->
                    repo.initializeAccountRole(session.role)
                    repo.cacheAuthenticatedProfile(session.name, session.email, session.role)
                    loggedIn = true
                }.onFailure {
                    loggedIn = false
                }
                sessionChecking = false
            }
        }
    }
    if(sessionChecking){
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if(!loggedIn){
        LoginScreen(authConfigured=auth.isConfigured()){email,name,password,role,isSignup,setError->
            auth.authenticate(isSignup,name,email,password,role){result->
                android.os.Handler(android.os.Looper.getMainLooper()).post{
                    result.onSuccess { session ->
                        val old=repo.profile(); repo.initializeAccountRole(session.role)
                        repo.saveProfile(old.copy(email=session.email,name=session.name,role=session.role))
                        repo.cacheAuthenticatedProfile(session.name, session.email, session.role); loggedIn=true
                    }.onFailure { setError(it.message ?: "Authentication failed.") }
                }
            }
        }
    } else MainShell(repo, auth){
        auth.logout { android.os.Handler(android.os.Looper.getMainLooper()).post {
            repo.clearSession()
            loggedIn=false
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(
    authConfigured: Boolean,
    onLogin: (
        String,
        String,
        String,
        UserRole,
        Boolean,
        (String) -> Unit
    ) -> Unit
) {
    var signup by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.FREELANCER) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
var openingSignup by remember { mutableStateOf(false) }
var passwordVisible by remember { mutableStateOf(false) }
var resetBusy by remember { mutableStateOf(false) }
var resetMessage by remember { mutableStateOf("") }
val screenContext = LocalContext.current
val resetAuthService = remember(screenContext) { AuthService(screenContext) }
val scope = rememberCoroutineScope()

val loginScrollState = rememberScrollState()

LaunchedEffect(signup) {
    loginScrollState.scrollTo(0)
}
    val clipboardManager = LocalClipboardManager.current

    
val navy = androidx.compose.ui.graphics.Color(0xFF142B52)
val blue = androidx.compose.ui.graphics.Color(0xFF4169E1)
val light = androidx.compose.ui.graphics.Color(0xFFF0F5FF)
val dark = androidx.compose.ui.graphics.Color(0xFF26364D)
val white = androidx.compose.ui.graphics.Color.White

val lavender = androidx.compose.ui.graphics.Color(0xFFF0EEFF)
val teal = androidx.compose.ui.graphics.Color(0xFF14A89A)
val paleBlue = androidx.compose.ui.graphics.Color(0xFFE5EEFF)

    val keyboardOpen = WindowInsets.isImeVisible

    val valid = authConfigured &&
        Validation.email(email) &&
        Validation.password(pass) &&
        (!signup || Validation.text(name, 2, 80))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
    androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(
            androidx.compose.ui.graphics.Color(0xFFDCE8FF),
            androidx.compose.ui.graphics.Color(0xFFF7F9FF)
        )
    )
)
 .verticalScroll(loginScrollState)        .padding(
    horizontal = 0.dp,
    vertical = if (keyboardOpen) 10.dp else 0.dp
),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = if (keyboardOpen) {
            Arrangement.Top
        } else {
            Arrangement.Center
        }
    ) {
        Spacer(Modifier.height(if (keyboardOpen) 0.dp else 8.dp))

        
        // BRAND HEADER — GLOBAL FREELANCE HUB DESIGN
Surface(
    modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 18.dp),
    shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = 34.dp,
        bottomEnd = 34.dp
    ),
    color = androidx.compose.ui.graphics.Color(0xFF071B43),
    shadowElevation = 8.dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF071B43),
                        androidx.compose.ui.graphics.Color(0xFF123D83),
                        androidx.compose.ui.graphics.Color(0xFF0757B8)
                    )
                )
            )
            .padding(horizontal = 18.dp, vertical = 24.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Public,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color(0x334FC3FF),
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .size(100.dp)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = androidx.compose.ui.graphics.Color(0xFFDBF2FF)
            ) {
                Box(
                    modifier = Modifier.size(58.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "F",
                        color = androidx.compose.ui.graphics.Color(0xFF0878E8),
                        style = MaterialTheme.typography.headlineLarge
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Freelance Hub",
                style = MaterialTheme.typography.headlineMedium,
                color = androidx.compose.ui.graphics.Color.White
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Your work. Your world. Your future.",
                style = MaterialTheme.typography.bodyLarge,
                color = androidx.compose.ui.graphics.Color(0xFFDCEBFF)
            )

            Spacer(Modifier.height(18.dp))

            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                color = androidx.compose.ui.graphics.Color(0xFF1455A5),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    androidx.compose.ui.graphics.Color(0xFF428DEB)
                )
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 10.dp
                    ),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = "Global marketplace",
                        tint = androidx.compose.ui.graphics.Color(0xFFBCEBFF),
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "GLOBAL FREELANCE MARKETPLACE",
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}

        Spacer(Modifier.height(20.dp))


        // LOGIN OR SEPARATE SIGNUP PAGE
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            color = white,
            shadowElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.padding(22.dp)
            ) {
                if (signup) {
                    TextButton(
                        onClick = {
                            signup = false
                            error = ""
                            busy = false
                        }
                    ) {
                        Text("← Back to Sign In", color = blue)
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Create your account",
                        style = MaterialTheme.typography.headlineSmall,
                        color = navy
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Start your journey with Freelance Hub.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dark
                    )

                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = "ACCOUNT SETUP  •  STEP 1",
                        style = MaterialTheme.typography.labelMedium,
                        color = blue
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(3) { index ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                color = if (index == 0) blue else light
                            ) {}
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full name") },
                        leadingIcon = {
                            Icon(Icons.Filled.Person, contentDescription = null)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "I want to join as",
                        style = MaterialTheme.typography.titleMedium,
                        color = navy
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = role == UserRole.FREELANCER,
                            onClick = { role = UserRole.FREELANCER },
                            label = { Text("Freelancer") },
                            leadingIcon = {
                                Icon(Icons.Filled.Work, contentDescription = null)
                            },
                            modifier = Modifier.weight(1f)
                        )

                        FilterChip(
                            selected = role == UserRole.CLIENT,
                            onClick = { role = UserRole.CLIENT },
                            label = { Text("Client") },
                            leadingIcon = {
                                Icon(Icons.Filled.Business, contentDescription = null)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                } else {
                    Text(
                        text = "Welcome Back",
                        style = MaterialTheme.typography.headlineSmall,
                        color = navy
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Sign in to continue to your workspace.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dark
                    )

                    Spacer(Modifier.height(22.dp))
                }

                if (!authConfigured) {
                    Text(
                        text = "Authentication is unavailable because the production backend is not configured.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(12.dp))
                }

                // EMAIL FIELD
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = {
                        Icon(Icons.Filled.Email, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(14.dp))

                // PASSWORD FIELD — EYE AND COPY PRESERVED
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    leadingIcon = {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment =
                                androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    passwordVisible = !passwordVisible
                                }
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = if (passwordVisible) {
                                        "Hide password"
                                    } else {
                                        "Show password"
                                    },
                                    tint = blue
                                )
                            }

                            TextButton(
                                enabled = pass.isNotEmpty(),
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(pass))
                                }
                            ) {
                                Text("Copy")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(6.dp))

                if (!signup) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = !resetBusy,
                            onClick = {
                                if (email.trim().isBlank()) {
                                    error = "Enter your account email first, then tap Forgot password."
                                    resetMessage = ""
                                } else {
                                    resetBusy = true
                                    error = ""
                                    resetMessage = ""
                                    resetAuthService.requestPasswordReset(email) { result ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            resetBusy = false
                                            result.onSuccess {
                                                resetMessage = "If an account exists for this email, a password recovery message has been sent. Check your inbox and spam folder."
                                            }.onFailure { failure ->
                                                error = failure.message ?: "Could not request password recovery. Please try again."
                                            }
                                        }
                                    }
                                }
                            }
                        ) {
                            if (resetBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Forgot password?")
                        }
                    }
                }

                if (resetMessage.isNotBlank()) {
                    Text(
                        text = resetMessage,
                        color = teal,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(10.dp))
                }

                // PRIMARY ACTION
                Button(
                    onClick = {
                        if (valid && !busy) {
                            busy = true
                            error = ""

                            onLogin(
                                email.trim(),
                                name.trim(),
                                pass,
                                role,
                                signup
                            ) { message ->
                                busy = false
                                error = message
                            }
                        }
                    },
                    enabled = valid && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = blue
                    )
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = white,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (signup) "Create account" else "Sign In",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment =
                        androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = if (signup) {
                            "Already have an account?"
                        } else {
                            "New to Freelance Hub?"
                        },
                        color = dark,
                        style = MaterialTheme.typography.bodySmall
                    )

                    
TextButton(
    enabled = !openingSignup,
    onClick = {
        if (signup) {
            signup = false
            error = ""
            busy = false
        } else if (!openingSignup) {
            openingSignup = true
            scope.launch {
                delay(2200)
                signup = true
                error = ""
                busy = false
                openingSignup = false
            }
        }
    }
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (openingSignup) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = blue
            )
            Spacer(Modifier.width(8.dp))
            Text("Opening account setup…", color = blue)
        } else {
            Text(
                text = if (signup) "Sign in" else "Create a new account",
                color = blue
            )
        }
    }
}


                }

                HorizontalDivider(
                    color = androidx.compose.ui.graphics.Color(0xFFE4EAF3)
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment =
                        androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = blue,
                        modifier = Modifier.size(15.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    Text(
                        text = "Your account credentials are protected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = dark
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "FREELANCE HUB  •  GLOBAL OPPORTUNITIES",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFF6D7D94)
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable fun MainShell(repo:AppRepository, auth: com.freelancehub.app.auth.AuthService, logout:()->Unit){
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardVisible = WindowInsets.isImeVisible || WindowInsets.ime.getBottom(density) > 0
    val backend = remember { RuntimeBackendConfig.current() }

    val remoteMarketplace = remember {
        RemoteMarketplaceRepository(
            SecureApiClient(backend),
            auth::accessTokenForApiBlocking
        )
    }

    val remoteAccount = remember {
        RemoteAccountRepository(
            SecureApiClient(backend),
            auth::accessTokenForApiBlocking
        )
    }
    var tab by remember{mutableStateOf(0)}
    var editing by remember{mutableStateOf(false)}
    var showSettings by remember{mutableStateOf(false)}
    var role by remember{mutableStateOf(repo.profile().role)}
    var showPostJob by remember{mutableStateOf(false)}
    var showWorkChecker by remember{mutableStateOf(false)}
    var showProjectAnalysis by remember{mutableStateOf(false)}
    var showMatching by remember{mutableStateOf(false)}
    var showFreelancerAnalysis by remember{mutableStateOf(false)}
    var activityVersion by remember{mutableStateOf(0)}
    var showExitDialog by remember { mutableStateOf(false) }

BackHandler {
    when {
        showExitDialog -> showExitDialog = false
        showPostJob -> showPostJob = false
        showWorkChecker -> showWorkChecker = false
        showProjectAnalysis -> showProjectAnalysis = false
        showMatching -> showMatching = false
        showFreelancerAnalysis -> showFreelancerAnalysis = false
        showSettings -> showSettings = false
        editing -> editing = false
        tab != 0 -> tab = 0
        else -> showExitDialog = true
    }
}

if (showExitDialog) {
    AlertDialog(
        onDismissRequest = { showExitDialog = false },
        title = { Text("Exit Freelance Hub?") },
        text = {
            Text("Do you want to exit the app?")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showExitDialog = false
                    (context as? android.app.Activity)?.finish()
                }
            ) {
                Text("Exit")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { showExitDialog = false }
            ) {
                Text("Stay")
            }
        }
    )
}

Scaffold(bottomBar={
    if (!keyboardVisible) NavigationBar(
    containerColor = androidx.compose.ui.graphics.Color.White,
    contentColor = androidx.compose.ui.graphics.Color(0xFF243B64),
    tonalElevation = 8.dp
) {
    listOf(
        "Home",
        "Jobs",
        "Notifications",
        "Messages",
        "Profile"
    ).forEachIndexed { i, n ->
        NavigationBarItem(
            selected = tab == i,
            onClick = { tab = i },
            icon = {
                Icon(
                    imageVector = listOf(
                        Icons.Filled.Home,
                        Icons.Filled.Work,
                        Icons.Filled.Notifications,
                        Icons.Filled.Mail,
                        Icons.Filled.Person
                    )[i],
                    contentDescription = n,
                    modifier = Modifier.size(26.dp)
                )
            },
            label = { Text(n) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor =
                    androidx.compose.ui.graphics.Color(0xFF3157D5),
                selectedTextColor =
                    androidx.compose.ui.graphics.Color(0xFF243B64),
                indicatorColor =
                    androidx.compose.ui.graphics.Color(0xFFE8EDFF),
                unselectedIconColor =
                    androidx.compose.ui.graphics.Color(0xFF738096),
                unselectedTextColor =
                    androidx.compose.ui.graphics.Color(0xFF738096)
            )
        )
    }
}

}) { p ->
        
        val pageBackground = when {
            showPostJob -> androidx.compose.ui.graphics.Color(0xFFF0F6FF)
            showWorkChecker -> androidx.compose.ui.graphics.Color(0xFFEAF8F2)
            showProjectAnalysis -> androidx.compose.ui.graphics.Color(0xFFF2EEFF)
            showMatching -> androidx.compose.ui.graphics.Color(0xFFEAF3FF)
            showFreelancerAnalysis -> androidx.compose.ui.graphics.Color(0xFFEAF8F5)
            editing -> androidx.compose.ui.graphics.Color.White
            tab == 0 -> androidx.compose.ui.graphics.Color(0xFFF0F5FF)
            tab == 1 -> androidx.compose.ui.graphics.Color(0xFFECF8F2)
            tab == 2 -> androidx.compose.ui.graphics.Color(0xFFF3F0FF)
            tab == 3 -> androidx.compose.ui.graphics.Color(0xFFFFF5EC)
            else -> androidx.compose.ui.graphics.Color.White
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(p)
                .background(pageBackground)
        ) { when {

            showPostJob->RemotePostJobScreen(role,remoteMarketplace,{showPostJob=false}){activityVersion++;showPostJob=false}
            showWorkChecker->WorkCheckerScreen({showWorkChecker=false})
            showProjectAnalysis->ProjectAnalysisScreen({showProjectAnalysis=false})
            showMatching->RemoteJobMatchingScreen(remoteMarketplace,remoteAccount,{showMatching=false})
            showFreelancerAnalysis->RemoteFreelancerAnalysisScreen(remoteAccount,{showFreelancerAnalysis=false})
            showSettings->RemoteSettingsScreen(remoteAccount,auth,{showSettings=false})
            editing->RemoteProfileScreen(remoteAccount,auth,{editing=false},{logout()})
            tab==0->RemoteHomeScreen(remoteMarketplace,remoteAccount,role,{showWorkChecker=true},{showProjectAnalysis=true},{showMatching=true},{showFreelancerAnalysis=true},{showSettings=true},{logout()}){if(role==UserRole.CLIENT)showPostJob=true}
            tab==1->RemoteJobsScreen(remoteMarketplace,remoteAccount,role,{if(role==UserRole.CLIENT)showPostJob=true})
            tab==2->RemoteNotificationsScreen()
            tab==3->RemoteCommunicationScreen()
            else->RemoteProfileScreen(remoteAccount,auth,{showSettings=true},{logout()})
        }}
    }
}


@Composable
fun RemoteHomeScreen(
    marketplace: RemoteMarketplaceRepository,
    account: RemoteAccountRepository,
    role: UserRole,
    onWorkCheck: () -> Unit,
    onProjectAnalysis: () -> Unit,
    onMatching: () -> Unit,
    onFreelancerAnalysis: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onPostJob: () -> Unit
) {
    var me by remember { mutableStateOf<RemoteAccount?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<RemoteJob>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun reload() {
        loading = true
        error = ""
        scope.launch(Dispatchers.IO) {
            val accountResult = runCatching { account.currentUser() }
            val jobsResult = runCatching { marketplace.listJobs(50) }
            withContext(Dispatchers.Main) {
                accountResult.onSuccess { me = it }
                    .onFailure {
                        error = it.message ?: "Unable to load account."
                    }
                jobsResult.onSuccess { jobs = it }
                    .onFailure {
                        error = it.message ?: "Unable to load jobs."
                    }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val mine = me
    val myJobs = if (mine == null) 0
        else jobs.count { it.ownerId == mine.id }
    val openJobs = jobs.count {
        it.status.equals(JobStatus.OPEN, true)
    }

    val navy = androidx.compose.ui.graphics.Color(0xFF102A56)
    val blue = androidx.compose.ui.graphics.Color(0xFF246BFD)
    val paleBlue = androidx.compose.ui.graphics.Color(0xFFEAF1FF)
    val green = androidx.compose.ui.graphics.Color(0xFFE6F7EF)
    val purple = androidx.compose.ui.graphics.Color(0xFFF0EBFF)
    val ink = androidx.compose.ui.graphics.Color(0xFF1C2B42)
    val muted = androidx.compose.ui.graphics.Color(0xFF65748B)
    val white = androidx.compose.ui.graphics.Color.White

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFF4F7FC)),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 18.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                color = navy
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    androidx.compose.ui.graphics.Color(0xFF102A56),
                                    androidx.compose.ui.graphics.Color(0xFF245DD8)
                                )
                            )
                        )
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🌐  GLOBAL FREELANCE MARKETPLACE", color = androidx.compose.ui.graphics.Color(0xFFBFD5FF), style = MaterialTheme.typography.labelMedium)
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Main menu", tint = white)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Account settings") }, leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) }, onClick = { menuExpanded = false; onOpenSettings() })
                                DropdownMenuItem(text = { Text("Refresh home") }, leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) }, onClick = { menuExpanded = false; reload() })
                            }
                        }
                    }
                    Text(
                        "Welcome to\nFreelance Hub",
                        color = white,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        "Your work. Your world. Your future.",
                        color = white,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = androidx.compose.ui.graphics.Color(0x332FFFFFF)
                    ) {
                        Text(
                            text = if (role == UserRole.CLIENT)
                                "CLIENT WORKSPACE"
                            else
                                "FREELANCER WORKSPACE",
                            modifier = Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 9.dp
                            ),
                            color = white,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Live Dashboard",
                    style = MaterialTheme.typography.titleLarge,
                    color = ink,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "Your marketplace overview",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = paleBlue
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📂", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (role == UserRole.CLIENT) "$myJobs"
                            else "$openJobs",
                            style = MaterialTheme.typography.headlineMedium,
                            color = navy,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            if (role == UserRole.CLIENT) "My posted jobs"
                            else "Open jobs",
                            style = MaterialTheme.typography.bodySmall,
                            color = ink
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = green
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("👤", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            mine?.displayName ?: if (loading) "…" else "Account",
                            style = MaterialTheme.typography.titleMedium,
                            color = androidx.compose.ui.graphics.Color(0xFF176B49),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            maxLines = 2
                        )
                        Text(
                            "Your account",
                            style = MaterialTheme.typography.bodySmall,
                            color = ink
                        )
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = white
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "Marketplace status",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = ink
                        )
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "● Live",
                                color = androidx.compose.ui.graphics.Color(0xFF16845B),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Messages & notifications: open Messages",
                        color = muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = { reload() }) {
                        Text("↻  Refresh live data")
                    }
                }
            }
        }

        item {
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleLarge,
                color = ink,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = white
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (role == UserRole.CLIENT) {
                        Button(
                            onClick = onPostJob,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = blue
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            Text("＋  Post a Job")
                        }
                        OutlinedButton(
                            onClick = onProjectAnalysis,
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            Text("Analyze My Project")
                        }
                    } else {
                        Button(
                            onClick = onWorkCheck,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = blue
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            Text("✓  Check My Work")
                        }
                        OutlinedButton(
                            onClick = onFreelancerAnalysis,
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) {
                            Text("Analyze My Freelancer Profile")
                        }
                    }

                    OutlinedButton(
                        onClick = onMatching,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            if (role == UserRole.CLIENT)
                                "Explore Marketplace"
                            else
                                "Find Jobs For Me"
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Recent Jobs",
                        style = MaterialTheme.typography.titleLarge,
                        color = ink,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        "Latest available opportunities",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                }
            }
        }

        if (!loading && jobs.isEmpty() && error.isBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = white
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text("🌍", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No jobs available yet",
                            color = ink,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "New opportunities will appear here.",
                            color = muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        items(jobs.take(5)) { job ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = white,
                shadowElevation = 2.dp
            ) {
                Column(Modifier.padding(17.dp)) {
                    Text(
                        job.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = navy,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Budget: ${job.budgetMinor / 100.0} ${job.currency}",
                        color = ink,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = purple
                    ) {
                        Text(
                            "Status: ${job.status}",
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 5.dp
                            ),
                            color = navy,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}


@Composable fun HomeScreen(repo:AppRepository,role:UserRole,onWorkCheck:()->Unit,onProjectAnalysis:()->Unit,onMatching:()->Unit,onFreelancerAnalysis:()->Unit,onPostJob:()->Unit){
    val me=repo.profile(); val allJobs=(DemoJobs.all+repo.savedJobs()).distinctBy{it.id}; val myJobs=allJobs.count{it.ownerEmail.equals(me.email,true)}; val myProposals=repo.proposals().count{it.freelancerEmail.equals(me.email,true)}; val saved=repo.favoriteJobIds().size
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{Text("Welcome to Freelance Hub",style=MaterialTheme.typography.headlineMedium);Text("Work globally. Learn skills. Build your career.");Spacer(Modifier.height(16.dp));Text("Dashboard",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(8.dp))
            if(role==UserRole.CLIENT){Text("My jobs: $myJobs");Text("Activity updates: available in Messages")}else{Text("My proposals: $myProposals");Text("Saved jobs: $saved")}
            Text("Messages & notifications: open Messages");Spacer(Modifier.height(20.dp));Text("Quick actions",style=MaterialTheme.typography.titleLarge);
            if(role==UserRole.CLIENT){Spacer(Modifier.height(10.dp));Button(onClick=onPostJob,modifier=Modifier.fillMaxWidth()){Text("Post a job")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick=onProjectAnalysis,modifier=Modifier.fillMaxWidth()){Text("Analyze My Project")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick=onMatching,modifier=Modifier.fillMaxWidth()){Text("Find My Job Matches") }} else {Spacer(Modifier.height(10.dp));Button(onClick=onWorkCheck,modifier=Modifier.fillMaxWidth()){Text("Check My Work")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick=onMatching,modifier=Modifier.fillMaxWidth()){Text("Find Jobs For Me")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick=onFreelancerAnalysis,modifier=Modifier.fillMaxWidth()){Text("Analyze My Freelancer Profile")}}
        }
        items(listOf("Find freelance jobs","Learn practical skills","Build your portfolio","Explore Premium")){ElevatedCard(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(it,Modifier.padding(18.dp))}}
    }
}
@Composable fun RemoteJobsScreen(
    marketplace:RemoteMarketplaceRepository,
    account:RemoteAccountRepository,
    role:UserRole,
    post:()->Unit
){
    var jobs by remember{mutableStateOf<List<RemoteJob>>(emptyList())}
    var selected by remember{mutableStateOf<RemoteJob?>(null)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun reload(){
        loading=true; error=""
        scope.launch(Dispatchers.IO){
            runCatching{marketplace.listJobs(50)}.onSuccess{result->withContext(Dispatchers.Main){jobs=result;loading=false}}.onFailure{e->withContext(Dispatchers.Main){error=e.message?:"Unable to load jobs.";loading=false}}
        }
    }
    LaunchedEffect(Unit){reload()}
    if(selected!=null){
        RemoteJobDetailsScreen(selected!!,account,marketplace,role,{selected=null})
        return
    }
    var query by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Text("Jobs",style=MaterialTheme.typography.headlineSmall)
            if(role==UserRole.CLIENT)Button(onClick=post){Text("Post job")}
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query,{query=it},label={Text("Search jobs or skills")},modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if(loading)CircularProgressIndicator()
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        val filtered=jobs.filter{it.title.contains(query,true)||it.description.contains(query,true)}
        LazyColumn{items(filtered){j->
            ElevatedCard(onClick={selected=j},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){
                Column(Modifier.padding(16.dp)){
                    Text(j.title,style=MaterialTheme.typography.titleMedium)
                    Text("Budget: ${j.budgetMinor/100.0} ${j.currency}")
                    Text("Status: ${j.status}")
                    if(j.description.isNotBlank())Text(j.description,maxLines=3)
                }
            }
        }}
    }
}

@Composable fun RemotePostJobScreen(role:UserRole,marketplace:RemoteMarketplaceRepository,back:()->Unit,onPublished:()->Unit){
    var title by remember{mutableStateOf("")};var budget by remember{mutableStateOf("")};var skills by remember{mutableStateOf("")};var desc by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    if(role!=UserRole.CLIENT){Column(Modifier.fillMaxSize().padding(20.dp)){Text("Only client accounts can post jobs.",color=MaterialTheme.colorScheme.error);TextButton(onClick=back){Text("Back")}};return}
    val budgetMinor=budget.trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExactOrNull()
    val valid=Validation.text(title,3,160)&&Validation.text(desc,20,10000)&&budgetMinor!=null&&budgetMinor>=0&&Validation.text(skills,0,1000)
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Post a job",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp))
        OutlinedTextField(title,{title=it},label={Text("Job title")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(budget,{budget=it},label={Text("Budget (USD)")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(skills,{skills=it},label={Text("Skills (comma separated)")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(desc,{desc=it},label={Text("Description (20–10000 chars)")},minLines=5,modifier=Modifier.fillMaxWidth())
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(enabled=valid&&!busy,onClick={
            busy=true;error=""
            scope.launch(Dispatchers.IO){
                runCatching{marketplace.createJob(RemoteJobCreate(title.trim(),desc.trim(),budgetMinor!!,"USD",skills.split(',').map{it.trim()}.filter{it.isNotBlank()}))}
                    .onSuccess{withContext(Dispatchers.Main){onPublished()}}
                    .onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Job publication failed."}}
            }
        },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Publishing…" else "Publish job")}
        TextButton(onClick=back){Text("Cancel")}
    }
}

private fun java.math.BigDecimal.longValueExactOrNull():Long?=runCatching{longValueExact()}.getOrNull()

@Composable fun RemoteJobDetailsScreen(j:RemoteJob,account:RemoteAccountRepository,marketplace:RemoteMarketplaceRepository,role:UserRole,back:()->Unit){
    var current by remember(j.id){mutableStateOf(j)}
    var proposalOpen by remember{mutableStateOf(false)}
    var workOpen by remember{mutableStateOf(false)}
    var reviewOpen by remember{mutableStateOf(false)}
    if(proposalOpen){RemoteProposalScreen(current,marketplace,back={proposalOpen=false}){proposalOpen=false};return}
    if(workOpen){RemoteWorkDeliveryScreen(current,marketplace,{workOpen=false});return}
    if(reviewOpen){RemoteWorkReviewScreen(current,marketplace,{reviewOpen=false});return}
    var me by remember{mutableStateOf<RemoteAccount?>(null)}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(Unit){withContext(Dispatchers.IO){runCatching{account.currentUser()}.onSuccess{me=it}.onFailure{error=it.message?:"Unable to load account."}}}
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text(current.title,style=MaterialTheme.typography.headlineSmall)
        Text("Budget: ${current.budgetMinor/100.0} ${current.currency}")
        Text("Status: ${current.status}")
        Spacer(Modifier.height(10.dp));Text(current.description)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(20.dp))
        if(role==UserRole.FREELANCER&&current.status==JobStatus.OPEN){Button(onClick={proposalOpen=true},modifier=Modifier.fillMaxWidth()){Text("Submit proposal")}}
        if(role==UserRole.FREELANCER&&current.status!=JobStatus.CLOSED){
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick={workOpen=true},modifier=Modifier.fillMaxWidth()){Text("Submit work & analyze delivery")}
            Text("Server authorization will allow this only when you are the assigned freelancer for the job.",style=MaterialTheme.typography.bodySmall)
        }
        if(role==UserRole.CLIENT&&current.status!=JobStatus.CLOSED){
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick={reviewOpen=true},modifier=Modifier.fillMaxWidth()){Text("Review submitted work")}
        }
        TextButton(onClick=back){Text("Back")}
    }
}

@Composable fun RemoteWorkDeliveryScreen(j:RemoteJob,marketplace:RemoteMarketplaceRepository,back:()->Unit){
    var summary by remember{mutableStateOf("")}
    var links by remember{mutableStateOf("")}
    var completed by remember{mutableStateOf("")}
    var issues by remember{mutableStateOf("")}
    var solutions by remember{mutableStateOf("")}
    var results by remember{mutableStateOf("")}
    var improvements by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    var success by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Work Submission & Analysis",style=MaterialTheme.typography.headlineSmall)
        Text(j.title,style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text("Submit the finished work, then record a structured analysis. Client approval remains separate.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(summary,{summary=it},label={Text("Completed work / deliverables")},minLines=5,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(links,{links=it},label={Text("File links / delivery location")},minLines=2,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(completed,{completed=it},label={Text("Requirements completed")},minLines=3,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(issues,{issues=it},label={Text("Issues encountered (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(solutions,{solutions=it},label={Text("Solutions / decisions (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(results,{results=it},label={Text("Results / outcome (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(improvements,{improvements=it},label={Text("Future improvements (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(success.isNotBlank())Text(success,color=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Button(enabled=!busy&&summary.trim().length>=10&&completed.isNotBlank(),onClick={
            busy=true;error="";success=""
            scope.launch(Dispatchers.IO){
                runCatching{
                    val submission=marketplace.createWorkSubmission(j.id,RemoteWorkSubmissionCreate(summary.trim(),links.split(',').map{it.trim()}.filter{it.isNotBlank()},0))
                    marketplace.analyzeWorkSubmission(submission.id,RemoteWorkAnalysisCreate(completed.trim(),issues.trim(),solutions.trim(),results.trim(),improvements.trim()))
                }.onSuccess{withContext(Dispatchers.Main){busy=false;success="Work submitted and freelancer analysis recorded. Waiting for client review."}}
                 .onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Work submission failed."}}
            }
        },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Submitting…" else "Submit work + analysis")}
        TextButton(onClick=back){Text("Back")}
    }
}

@Composable fun RemoteWorkReviewScreen(j:RemoteJob,marketplace:RemoteMarketplaceRepository,back:()->Unit){
    var submissions by remember{mutableStateOf<List<RemoteWorkSubmission>>(emptyList())}
    var selected by remember{mutableStateOf<RemoteWorkSubmission?>(null)}
    var comment by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    
fun reload() {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                marketplace.listWorkSubmissions(j.id)
            }
        }.onSuccess {
            submissions = it
            error = ""
        }.onFailure { e ->
            error = e.message ?: "Unable to load submissions."
        }
    }
}

    LaunchedEffect(Unit){reload()}
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Client Work Review",style=MaterialTheme.typography.headlineSmall);Text(j.title,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(10.dp))
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(submissions.isEmpty())Text("No authorized work submissions are available yet.")
        submissions.forEach{s->
            ElevatedCard(onClick={selected=s},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)){
                Column(Modifier.padding(12.dp)){Text("Status: ${s.status}");Text(s.summary.take(180));Text("Check score: ${s.checkScore}%")}
            }
        }
        selected?.let{s->
            Spacer(Modifier.height(10.dp));Text("Selected submission",style=MaterialTheme.typography.titleMedium);Text(s.summary)
            if(s.fileLinks.isNotEmpty())Text("Files: ${s.fileLinks.joinToString()}")
            OutlinedTextField(comment,{comment=it},label={Text("Review / revision feedback")},minLines=3,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(enabled=!busy,onClick={busy=true;scope.launch(Dispatchers.IO){runCatching{marketplace.reviewWorkSubmission(s.id,RemoteWorkReviewCreate(WorkSubmissionStatus.APPROVED,comment.trim()))}.onSuccess{withContext(Dispatchers.Main){busy=false;comment="";reload()}}.onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Approval failed."}}}}){Text("Approve")}
                OutlinedButton(enabled=!busy,onClick={busy=true;scope.launch(Dispatchers.IO){runCatching{marketplace.reviewWorkSubmission(s.id,RemoteWorkReviewCreate(WorkSubmissionStatus.CHANGES_REQUESTED,comment.trim()))}.onSuccess{withContext(Dispatchers.Main){busy=false;comment="";reload()}}.onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Revision request failed."}}}}){Text("Request changes")}
            }
        }
        TextButton(onClick=back){Text("Back")}
    }
}

@Composable fun RemoteProposalScreen(j:RemoteJob,marketplace:RemoteMarketplaceRepository,back:()->Unit,onSubmitted:()->Unit){
    var letter by remember{mutableStateOf("")};var bid by remember{mutableStateOf("")};var days by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    val bidMinor=bid.trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExactOrNull();val daysValue=days.trim().toIntOrNull()
    val valid=letter.trim().length in 20..10000&&bidMinor!=null&&bidMinor>=0&&daysValue!=null&&daysValue in 1..3650
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Submit proposal",style=MaterialTheme.typography.headlineSmall);Text(j.title);Spacer(Modifier.height(10.dp))
        OutlinedTextField(letter,{letter=it},label={Text("Cover letter (20–10000 chars)")},minLines=6,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(bid,{bid=it},label={Text("Your bid (USD)")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(days,{days=it},label={Text("Delivery time (days)")},modifier=Modifier.fillMaxWidth())
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(enabled=valid&&!busy,onClick={busy=true;error="";scope.launch(Dispatchers.IO){runCatching{marketplace.createProposal(j.id,RemoteProposalCreate(letter.trim(),bidMinor!!,"USD",daysValue!!))}.onSuccess{withContext(Dispatchers.Main){onSubmitted()}}.onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Proposal submission failed."}}}},modifier=Modifier.fillMaxWidth()){Text(if(busy)"Submitting…" else "Send proposal")}
        TextButton(onClick=back){Text("Cancel")}
    }
}

@Composable fun JobsScreen(repo:AppRepository,role:UserRole,refreshKey:Int,open:(Job)->Unit,post:()->Unit){
    var query by remember{mutableStateOf("")}
    var favoritesOnly by remember{mutableStateOf(false)}
    var statusFilter by remember{mutableStateOf("All")}
    var favoriteVersion by remember{mutableStateOf(0)}
    val all=(DemoJobs.all+repo.savedJobs()).distinctBy{it.id}
    val favoriteIds=repo.favoriteJobIds()
    val filtered=all.filter{j->
        val matchesQuery=j.title.contains(query,true)||j.skills.contains(query,true)||j.client.contains(query,true)||j.description.contains(query,true)
        matchesQuery && (!favoritesOnly || favoriteIds.contains(j.id)) && (statusFilter=="All" || j.status==statusFilter)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Jobs",style=MaterialTheme.typography.headlineSmall)
            if(role==UserRole.CLIENT)Button(onClick=post){Text("Post job")}
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query,{query=it},label={Text("Search jobs, skills or clients")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Checkbox(checked=favoritesOnly,onCheckedChange={favoritesOnly=it})
            Text("Favorites only")
            Spacer(Modifier.weight(1f))
            Text("${favoriteIds.size} saved",style=MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            listOf("All","Open","Paused","Closed","Completed").forEach{status->
                FilterChip(selected=statusFilter==status,onClick={statusFilter=status},label={Text(status)})
            }
        }
        Spacer(Modifier.height(4.dp))
        if(filtered.isEmpty()){
            Text(if(favoritesOnly)"No favorite jobs yet." else "No jobs match your search.",style=MaterialTheme.typography.bodyMedium)
        }
        LazyColumn{items(filtered){j->
            val favorite=repo.isFavoriteJob(j.id)
            ElevatedCard(onClick={open(j)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){
                Column(Modifier.padding(16.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        Text(j.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        IconButton(onClick={repo.toggleFavoriteJob(j.id); favoriteVersion++}){
                            Text(if(favorite) "★" else "☆")
                        }
                    }
                    Text(j.client);Text("Budget: ${j.budget}");Text(j.skills);Text(if(j.remote)"Remote • Global" else "On-site");Text("Status: ${j.status}")
                }
            }
        }}
    }
}

@Composable fun PostJobScreen(ownerEmail:String,back:()->Unit,onSave:(Job)->Unit){
    var title by remember{mutableStateOf("")};var client by remember{mutableStateOf("")};var budget by remember{mutableStateOf("")};var skills by remember{mutableStateOf("")};var desc by remember{mutableStateOf("")};var deliverables by remember{mutableStateOf("")};var revisions by remember{mutableStateOf("")};var formats by remember{mutableStateOf("")};var reference by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    val valid=Validation.text(title,3,120)&&Validation.text(client,2,120)&&Validation.text(budget,1,120)&&Validation.text(skills,2,500)&&Validation.text(desc,20,5000)&&Validation.text(deliverables,2,2000)&&Validation.text(revisions,2,500)&&Validation.text(formats,2,1000)&&reference.length<=2000&&ownerEmail.isNotBlank()
    Column(Modifier.fillMaxSize().padding(20.dp)){Text("Post a job",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp));
    OutlinedTextField(title,{title=it},label={Text("Job title (3–120 chars)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(client,{client=it},label={Text("Client / company")},modifier=Modifier.fillMaxWidth());OutlinedTextField(budget,{budget=it},label={Text("Budget (USD)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(skills,{skills=it},label={Text("Skills")},modifier=Modifier.fillMaxWidth());
        OutlinedTextField(desc,{desc=it},label={Text("Description (min 20 chars)")},minLines=4,modifier=Modifier.fillMaxWidth());OutlinedTextField(deliverables,{deliverables=it},label={Text("Deliverables (required)")},minLines=2,modifier=Modifier.fillMaxWidth());OutlinedTextField(revisions,{revisions=it},label={Text("Revisions included (required)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(formats,{formats=it},label={Text("File formats / delivery method (required)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(reference,{reference=it},label={Text("Reference / example (optional)")},modifier=Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp));Button(onClick={if(valid){onSave(Job(id=(System.currentTimeMillis()%1000000).toInt(),title=title.trim(),client=client.trim(),budget=budget.trim(),skills=skills.trim(),description=desc.trim(),deliverables=deliverables.trim(),revisions=revisions.trim(),fileFormats=formats.trim(),reference=reference.trim(),ownerEmail=ownerEmail.trim()))}else error="Please complete the required fields and keep the text within the shown limits."},modifier=Modifier.fillMaxWidth()){Text("Publish job")};TextButton(onClick=back){Text("Cancel")}}
}

@Composable fun JobDetails(j:Job,role:UserRole,canManage:Boolean,canApply:Boolean,back:()->Unit,onStatusChange:(Job,String)->Unit,apply:()->Unit){
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text(j.title,style=MaterialTheme.typography.headlineSmall);Text("Client: ${j.client}");Text("Budget: ${j.budget}");Text("Skills: ${j.skills}");Text(j.description);if(j.deliverables.isNotBlank())Text("Deliverables: ${j.deliverables}");if(j.revisions.isNotBlank())Text("Revisions: ${j.revisions}");if(j.fileFormats.isNotBlank())Text("Files / delivery: ${j.fileFormats}");if(j.reference.isNotBlank())Text("Reference: ${j.reference}");Text(if(j.remote)"Remote • Global" else "On-site");Text("Status: ${j.status}")
        Spacer(Modifier.height(20.dp))
        if(role==UserRole.CLIENT && canManage){
            Text("Job controls",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(j.status==JobStatus.OPEN)OutlinedButton(onClick={onStatusChange(j,JobStatus.PAUSED)}){Text("Pause") }
                if(j.status==JobStatus.PAUSED)Button(onClick={onStatusChange(j,JobStatus.OPEN)}){Text("Reopen") }
                if(j.status!=JobStatus.CLOSED)TextButton(onClick={onStatusChange(j,JobStatus.CLOSED)}){Text("Close job") }
            }
        } else if(j.status==JobStatus.OPEN && role==UserRole.FREELANCER && canApply){
            Button(onClick=apply,modifier=Modifier.fillMaxWidth()){Text("Submit proposal")}
        } else if(j.status==JobStatus.OPEN && role==UserRole.FREELANCER){
            Text(if(j.ownerEmail.isBlank()) "This is a sample job. Live proposals will be enabled after backend connection." else "You already submitted a proposal for this job.")
        } else {
            Text(when {
                role==UserRole.CLIENT && !canManage -> "Only the Client who posted this job can manage it."
                j.status==JobStatus.PAUSED -> "This job is paused. New proposals are temporarily unavailable."
                j.status==JobStatus.COMPLETED -> "This job has been completed."
                else -> "This job is closed. New proposals are unavailable."
            })
        }
        TextButton(onClick=back){Text("Back")}
    }
}

@Composable fun ProposalScreen(j:Job,profile:UserProfile,back:()->Unit,onSubmit:(Proposal)->Unit){var letter by remember{mutableStateOf("")};var bid by remember{mutableStateOf("")};var delivery by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};val profileReady=profile.role==UserRole.FREELANCER&&profile.name.trim().length>=2&&profile.email.isNotBlank()&&profile.capabilities.isNotBlank()&&profile.skills.isNotBlank()&&profile.skillLevels.isNotBlank();val bidValue=bid.trim().toDoubleOrNull();val valid=profileReady&&letter.trim().length in 20..2000&&bidValue!=null&&bidValue>0&&bidValue<=100000000&&delivery.trim().length in 2..120;Column(Modifier.fillMaxSize().padding(20.dp)){Text("Proposal",style=MaterialTheme.typography.headlineSmall);Text(j.title);Text("Applying as: ${profile.name.ifBlank{profile.email}}",style=MaterialTheme.typography.bodySmall);if(!profileReady)Text("Complete your freelancer profile before submitting a proposal.",color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp));OutlinedTextField(letter,{letter=it},label={Text("Cover letter (20–2000 chars)")},minLines=5,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));OutlinedTextField(bid,{bid=it},label={Text("Your bid (USD)")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));OutlinedTextField(delivery,{delivery=it},label={Text("Delivery time (2–120 chars)")},modifier=Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp));Button(onClick={if(valid)onSubmit(Proposal(j.id,letter.trim(),bid.trim(),delivery.trim(),freelancerEmail=profile.email.trim(),freelancerName=profile.name.trim()))else error="Please check your profile, cover letter, bid amount and delivery time."},enabled=profileReady,modifier=Modifier.fillMaxWidth()){Text("Send proposal")};TextButton(onClick=back){Text("Cancel")}}}
@Composable fun WorkSubmissionScreen(repo:AppRepository,j:Job,back:()->Unit,onSubmit:(WorkSubmission)->Unit){
    val p=repo.profile(); var summary by remember{mutableStateOf("")};var links by remember{mutableStateOf("")};var result by remember{mutableStateOf<WorkCheckResult?>(null)};var error by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Submit Work",style=MaterialTheme.typography.headlineSmall);Text(j.title);Text("Use the client's requirements below to check your work before submission.");Spacer(Modifier.height(10.dp))
        Text("Requirements",style=MaterialTheme.typography.titleMedium);Text(listOf(j.description,j.deliverables,j.revisions,j.fileFormats,j.reference).filter{it.isNotBlank()}.joinToString("\n"));Spacer(Modifier.height(10.dp))
        OutlinedTextField(summary,{summary=it},label={Text("Work summary / completed deliverables")},minLines=5,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(links,{links=it},label={Text("File links / delivery location (optional)")},minLines=2,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp));Button(onClick={if(summary.isNotBlank()) result=analyzeWork(j.description+" "+j.deliverables+" "+j.fileFormats+" "+j.reference,summary) else error=true},enabled=summary.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Run work check") }
        result?.let{r->Spacer(Modifier.height(8.dp));Text("Work-check score: ${r.score}%");Text(if(r.passed)"✓ Ready to submit" else "⚠ Review missing items before submitting");if(r.missing.isNotEmpty())r.missing.take(4).forEach{Text("• $it")}}
        if(error)Text("Add a work summary before submitting.",color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp));Button(onClick={if(summary.isNotBlank() && result!=null)onSubmit(WorkSubmission(j.id,p.email,p.name,summary,links,result!!.score)) else error=true},enabled=summary.isNotBlank()&&result!=null,modifier=Modifier.fillMaxWidth()){Text("Submit for client review")};TextButton(onClick=back){Text("Cancel")};Text("AI work checking is guidance; final approval remains with the client.")
    }
}
@Composable
fun CoursesScreen(){
    val context=LocalContext.current
    val config=remember{RuntimeBackendConfig.current()}
    val tokenStore=remember{TokenStore(context.applicationContext)}
    val repo=remember(config){if(config.isConfigured()) RemoteCourseRepository(SecureApiClient(config)){tokenStore.readAccessToken()} else null}
    var courses by remember{mutableStateOf<List<RemoteCourse>>(emptyList())}
    var enrollments by remember{mutableStateOf<List<RemoteEnrollment>>(emptyList())}
    var selected by remember{mutableStateOf<RemoteCourse?>(null)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun reload(){
        val remote=repo ?: return
        loading=true; error=""
        scope.launch{runCatching{withContext(Dispatchers.IO){remote.listCourses() to remote.myCourses()}}
            .onSuccess{(items,enrolled)->courses=items;enrollments=enrolled}
            .onFailure{error=it.message?:"Unable to load courses."}
            .also{loading=false}}
    }
    LaunchedEffect(repo){reload()}
    if(selected!=null){
        CourseDetailsScreen(selected!!,enrollments.any{it.courseId==selected!!.id},repo,{selected=null}){enrollment->enrollments=enrollments+enrollment;selected=null}
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Text("Courses",style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if(repo==null){Text("Production learning backend is not configured yet. Courses remain disabled until the real backend is supplied.",color=MaterialTheme.colorScheme.error);return@Column}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(loading)CircularProgressIndicator()
        if(courses.isEmpty()&&!loading)Text("No published courses are available yet.")
        LazyColumn(Modifier.weight(1f)){items(courses){course->
            ElevatedCard(onClick={selected=course},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){
                Column(Modifier.padding(16.dp)){
                    Text(course.title,style=MaterialTheme.typography.titleMedium)
                    if(course.description.isNotBlank())Text(course.description,maxLines=3)
                    Text(if(course.priceMinor==0L)"Free" else "${course.priceMinor/100.0} ${course.currency}")
                    Text("${course.lessonCount} lessons${if(course.instructorName.isNotBlank())" • ${course.instructorName}" else ""}")
                    if(enrollments.any{it.courseId==course.id})Text("Enrolled",style=MaterialTheme.typography.labelLarge)
                }
            }
        }}
        TextButton(onClick={reload() },enabled=!loading){Text("Refresh courses")}
    }
}

@Composable
private fun CourseDetailsScreen(course:RemoteCourse,enrolled:Boolean,repo:RemoteCourseRepository?,back:()->Unit,onEnrolled:(RemoteEnrollment)->Unit){
    val context=LocalContext.current
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var checkout by remember{mutableStateOf<RemoteCheckoutSession?>(null)}
    var paymentStatus by remember{mutableStateOf<RemoteCheckoutStatus?>(null)}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text(course.title,style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if(course.description.isNotBlank())Text(course.description)
        Spacer(Modifier.height(8.dp))
        Text("Access: ${course.access}")
        Text("Price: ${if(course.priceMinor==0L)"Free" else "${course.priceMinor/100.0} ${course.currency}"}")
        Text("Lessons: ${course.lessonCount}")
        if(course.instructorName.isNotBlank())Text("Instructor: ${course.instructorName}")
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        if(enrolled) Text("You are enrolled in this course.") else Button(enabled=!busy&&repo!=null,onClick={
            busy=true;error=""
            scope.launch{
                if(course.priceMinor==0L){
                    runCatching{withContext(Dispatchers.IO){repo!!.enroll(course.id)}}
                        .onSuccess{onEnrolled(it)}.onFailure{error=it.message?:"Enrollment failed."}
                }else{
                    runCatching{withContext(Dispatchers.IO){repo!!.startCourseCheckout(course.id)}}
                        .onSuccess{session->
                            runCatching{if (!com.freelancehub.app.security.CheckoutSecurityRules.isSafeExternalCheckoutUrl(session.checkoutUrl)) {
                                error="Secure checkout returned an unsafe URL."
                            } else {
                                checkout=session
                                paymentStatus=null
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.checkoutUrl)))
                            }}
                                .onFailure{error="Secure checkout could not be opened."}
                        }.onFailure{error=it.message?:"Unable to start secure checkout."}
                }
                busy=false
            }
        },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Please wait…" else if(course.priceMinor==0L)"Enroll free" else "Continue to secure checkout")}
        checkout?.let { session ->
            Spacer(Modifier.height(10.dp))
            Text("Checkout session: ${session.checkoutId}",style=MaterialTheme.typography.bodySmall)
            
Button(
    enabled = !busy,
    onClick = {
        busy = true
        error = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo!!.checkoutStatus(session.checkoutId)
                }
            }.onSuccess { status ->
                paymentStatus = status
                if (status.paid) {
                    error = "Payment verified. Refreshing your enrollment…"
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repo!!.myCourses()
                        }
                    }.onSuccess { list ->
                        list.firstOrNull { it.courseId == course.id }
                            ?.let { onEnrolled(it) }
                    }.onFailure {
                        error = it.message ?: "Unable to refresh enrollment."
                    }
                } else {
                    error = "Payment status: ${status.status}. Access is granted only after backend verification."
                }
            }.onFailure {
                error = it.message ?: "Unable to verify payment status."
            }.also {
                busy = false
            }
        }
    },
    modifier = Modifier.fillMaxWidth()
) {
    Text(if (busy) "Checking payment…" else "Refresh payment status")
}

            paymentStatus?.let{Text("Payment status: ${it.status}",style=MaterialTheme.typography.bodySmall)}
        }
        Text("Paid-course access is granted only by the production backend after verified payment; the APK never unlocks paid content locally.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=back){Text("Back")}
    }
}

@Composable
fun RemoteCommunicationScreen(){
    val context = LocalContext.current
    val config = remember { RuntimeBackendConfig.current() }
    val tokenStore = remember { TokenStore(context.applicationContext) }
    val repo = remember(config) {
        if (config.isConfigured()) RemoteCommunicationRepository(
            SecureApiClient(config)
        ) { tokenStore.readAccessToken() } else null
    }
    var conversations by remember { mutableStateOf<List<RemoteConversation>>(emptyList()) }
    var notifications by remember { mutableStateOf<List<RemoteNotification>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteConversation?>(null) }
    var messages by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo) {
        if (repo == null) return@LaunchedEffect
        loading = true
        error = ""
        runCatching { withContext(Dispatchers.IO) { repo.listConversations() to repo.listNotifications() } }
            .onSuccess { (c, n) -> conversations = c; notifications = n }
            .onFailure { error = it.message ?: "Unable to load communication data." }
        loading = false
    }

    LaunchedEffect(selected?.id) {
        val conversation = selected ?: return@LaunchedEffect
        val remote = repo ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { remote.listMessages(conversation.id) } }
            .onSuccess { messages = it }
            .onFailure { error = it.message ?: "Unable to load messages." }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Messages & Notifications", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (repo == null) {
            Text("Production backend is not configured. Remote communication is disabled.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected == null) {
            Text(if (loading) "Loading…" else "Conversations", style = MaterialTheme.typography.titleLarge)
            if (conversations.isEmpty() && !loading) Text("No server conversations yet.")
            conversations.forEach { conversation ->
                ElevatedCard(
    onClick = { selected = conversation; error = "" },
    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
) {
                    Column(Modifier.padding(14.dp)) {
                        Text(conversation.participantName.ifBlank { conversation.participantId }, style = MaterialTheme.typography.titleMedium)
                        if (conversation.jobId.isNotBlank()) Text("Job: ${conversation.jobId}")
                        if (conversation.lastMessage.isNotBlank()) Text(conversation.lastMessage.take(120), style = MaterialTheme.typography.bodySmall)
                        if (conversation.unreadCount > 0) Text("${conversation.unreadCount} unread", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Notifications", style = MaterialTheme.typography.titleLarge)
            notifications.take(10).forEach { n ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        Text(n.message)
                        if (!n.read) TextButton(onClick = {
                            scope.launch { runCatching { withContext(Dispatchers.IO) { repo.markNotificationRead(n.id) } }
                                .onSuccess { notifications = notifications.map { x -> if (x.id == n.id) x.copy(read = true) else x } }
                                .onFailure { error = it.message ?: "Unable to mark notification as read." } }
                        }) { Text("Mark read") }
                    }
                }
            }
        } else {
            val conversation = selected!!
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(conversation.participantName.ifBlank { conversation.participantId }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { selected = null; messages = emptyList(); body = "" }) { Text("Back") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(messages) { message ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(message.senderName.ifBlank { message.senderId }, style = MaterialTheme.typography.titleSmall)
                            Text(message.body)
                            
                        }
                    }
                }
            }
            OutlinedTextField(body, { body = it }, label = { Text("Write a message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val text = body.trim()
                if (text.isBlank()) return@Button
                sending = true; error = ""
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { repo.sendMessage(conversation.id, text) } }
                        .onSuccess { sent -> messages = messages + sent; body = "" }
                        .onFailure { error = it.message ?: "Unable to send message." }
                    sending = false
                }
            }, enabled = !sending && body.trim().isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (sending) "Sending…" else "Send") }
        }
    }
}


@Composable
fun RemoteNotificationsScreen() {
    val context = LocalContext.current
    val config = remember { RuntimeBackendConfig.current() }
    val tokenStore = remember { TokenStore(context.applicationContext) }

    val repo = remember(config) {
        if (config.isConfigured()) {
            RemoteCommunicationRepository(
                SecureApiClient(config)
            ) { tokenStore.readAccessToken() }
        } else null
    }

    var notifications by remember {
        mutableStateOf<List<RemoteNotification>>(emptyList())
    }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun loadNotifications() {
        if (repo == null) {
            loading = false
            error = "Backend is not configured."
            return
        }

        loading = true
        error = ""

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.listNotifications()
                }
            }.onSuccess {
                notifications = it
            }.onFailure {
                error = it.message ?: "Unable to load notifications."
            }

            loading = false
        }
    }

    LaunchedEffect(repo) {
        loadNotifications()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.headlineSmall
            )

            IconButton(onClick = { loadNotifications() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh notifications"
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        }

        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!loading && error.isBlank() && notifications.isEmpty()) {
            Text("No notifications yet.")
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notifications) { notification ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            notification.title,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(notification.message)

                        Spacer(Modifier.height(8.dp))

                        if (!notification.read) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                repo?.markNotificationRead(
                                                    notification.id
                                                ) ?: error(
                                                    "Backend is not configured."
                                                )
                                            }
                                        }.onSuccess {
                                            notifications =
                                                notifications.map { item ->
                                                    if (item.id == notification.id) {
                                                        item.copy(read = true)
                                                    } else {
                                                        item
                                                    }
                                                }
                                        }.onFailure {
                                            error = it.message
                                                ?: "Unable to mark as read."
                                        }
                                    }
                                }
                            ) {
                                Text("Mark as read")
                            }
                        } else {
                            Text(
                                "Read",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable fun ReviewScreen(repo:AppRepository,j:Job,back:()->Unit,onSubmit:(Review)->Unit){
    val p=repo.profile();val accepted=repo.proposals().firstOrNull{it.jobId==j.id && it.status==com.freelancehub.app.data.ProposalStatus.ACCEPTED};
    val reviewee=if(p.email.equals(j.ownerEmail,true)) accepted?.freelancerEmail.orEmpty() else j.ownerEmail
    val revieweeName=if(p.email.equals(j.ownerEmail,true)) accepted?.freelancerName.orEmpty() else j.client
    var rating by remember{mutableStateOf(5)};var comment by remember{mutableStateOf("")};
    Column(Modifier.fillMaxSize().padding(20.dp)){Text("Review completed work",style=MaterialTheme.typography.headlineSmall);Text(j.title);Text("Reviewing: ${revieweeName.ifBlank{reviewee}}",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(12.dp));
        Text("Rating: $rating / 5",style=MaterialTheme.typography.titleMedium);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){(1..5).forEach{n->Button(onClick={rating=n},enabled=rating!=n){Text(n.toString())}}};Spacer(Modifier.height(10.dp));
        OutlinedTextField(comment,{comment=it},label={Text("Comment (optional)")},minLines=4,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(12.dp));
        Button(onClick={if(reviewee.isNotBlank())onSubmit(Review(j.id,p.email,p.name,reviewee,revieweeName,rating,comment))},enabled=reviewee.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Submit review")};Text("Reviews can be submitted once per participant per completed job.");TextButton(onClick=back){Text("Cancel")}}
}

@Composable
fun RemoteProfileScreen(
    account: RemoteAccountRepository,
    auth: com.freelancehub.app.auth.AuthService,
    edit: () -> Unit,
    logout: () -> Unit
) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<RemoteAccount?>(null) }
    var loading by remember { mutableStateOf(true) }
    var savingPhoto by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var profileBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var coverBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { account.currentUser() }
            withContext(Dispatchers.Main) {
                result.onSuccess { profile = it }
                    .onFailure { error = it.message ?: "Unable to load profile." }
                loading = false
            }
        }
    }

    fun uploadSelected(uri: Uri, isCover: Boolean) {
        if (savingPhoto) return
        savingPhoto = true
        error = ""
        notice = ""
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resolver = context.contentResolver
                val type = resolver.getType(uri)?.lowercase()
                    ?: throw IllegalArgumentException("Unable to identify this image type.")
                val contentType = when (type) {
                    "image/jpeg", "image/jpg" -> "image/jpeg"
                    "image/png" -> "image/png"
                    "image/webp" -> "image/webp"
                    else -> throw IllegalArgumentException("Choose a JPEG, PNG, or WebP image.")
                }
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 5 * 1024 * 1024) { "Image must be 5 MB or smaller." }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IllegalStateException("Unable to read the selected image.")
                require(bytes.isNotEmpty()) { "The selected image is empty." }
                if (isCover) account.updateCoverPhoto(bytes, contentType)
                else account.updateProfilePhoto(bytes, contentType)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    profile = it
                    notice = if (isCover) "Cover photo saved successfully." else "Profile photo saved successfully."
                }.onFailure { e -> error = e.message ?: "The photo could not be saved." }
                savingPhoto = false
            }
        }
    }

    val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadSelected(uri, false)
    }
    val coverPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadSelected(uri, true)
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(profile?.photoPath, profile?.coverPhotoUrl) {
        val current = profile
        profileBitmap = null
        coverBitmap = null
        if (current != null) {
            withContext(Dispatchers.IO) {
                val photo = current.photoPath?.takeIf { it.isNotBlank() }?.let { path ->
                    runCatching { account.downloadProfilePhoto(path).let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }.getOrNull()
                }
                val cover = current.coverPhotoUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    runCatching { java.net.URL(url).openConnection().apply {
                        connectTimeout = 10000
                        readTimeout = 10000
                    }.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    profileBitmap = photo
                    coverBitmap = cover
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF3F6FC))
            .verticalScroll(rememberScrollState())
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        if (notice.isNotBlank()) Text(notice, color = androidx.compose.ui.graphics.Color(0xFF168675), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (savingPhoto) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Uploading and saving photo securely…")
        }

        profile?.let { p ->
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().height(190.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF3157D5))
                ) {
                    if (coverBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = coverBitmap!!.asImageBitmap(),
                            contentDescription = "Cover photo",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
                            if (p.coverPhotoUrl.isNullOrBlank()) Text("Add a cover photo", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 18.dp))
                        }
                    }
                    IconButton(
                        onClick = { coverPhotoPicker.launch("image/*") },
                        enabled = !savingPhoto,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(10.dp).size(40.dp)
                            .background(androidx.compose.ui.graphics.Color(0xCC102A56), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Change cover photo", tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 142.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(104.dp),
                                shadowElevation = 5.dp
                            ) {
                                Box(
                                    Modifier.padding(4.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(androidx.compose.ui.graphics.Color(0xFFE7EDFA)),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    if (profileBitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = profileBitmap!!.asImageBitmap(),
                                            contentDescription = "Profile photo",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Filled.Person, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF3157D5), modifier = Modifier.size(48.dp))
                                    }
                                }
                            }
                            IconButton(
                                onClick = { profilePhotoPicker.launch("image/*") },
                                enabled = !savingPhoto,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
                                    .size(34.dp).background(androidx.compose.ui.graphics.Color(0xFF3157D5), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = "Change profile photo", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(p.displayName.ifBlank { "Your profile" }, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF102A56))
                            Text(p.role.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF65748B))
                        }
                    }
                }
            }

ElevatedCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF102A56))
                    if (p.bio.isNotBlank()) Text(p.bio)
                    else Text("Add a short introduction to your professional profile.", color = androidx.compose.ui.graphics.Color(0xFF65748B))
                    if (p.capabilities.isNotBlank()) Text("Services: ${p.capabilities}")
                    if (p.skills.isNotBlank()) Text("Skills: ${p.skills}")
                }
            }
        }
    }
}

@Composable
fun RemoteSettingsScreen(account: RemoteAccountRepository, auth: com.freelancehub.app.auth.AuthService, back: () -> Unit) {
    var profile by remember { mutableStateOf<RemoteAccount?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(false) }
    var editProfessional by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reloadProfile() {
        loading = true
        error = ""
        scope.launch(Dispatchers.IO) {
            val result = runCatching { account.currentUser() }
            withContext(Dispatchers.Main) {
                result.onSuccess { profile = it }
                    .onFailure { error = it.message ?: "Unable to load account settings." }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reloadProfile() }
    val current = profile

    if (current != null && editProfessional) {
        RemoteProfileEditor(
            profile = current,
            account = account,
            done = { editProfessional = false; reloadProfile() },
            cancel = { editProfessional = false },
            openSecurity = { security = true }
        )
        return
    }

    if (current != null && security) {
        RemoteAccountSecurityScreen(
            auth = auth,
            account = account,
            currentEmail = current.email,
            initialPhone = current.mobileNumber,
            done = { security = false; reloadProfile() },
            back = { security = false; reloadProfile() }
        )
        return
    }

    Column(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFF3F6FC))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            color = androidx.compose.ui.graphics.Color(0xFF102A56)
        ) {
            Column(
                Modifier.background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color(0xFF102A56),
                            androidx.compose.ui.graphics.Color(0xFF3157D5)
                        )
                    )
                ).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFDAE5FF),
                    modifier = Modifier.size(30.dp)
                )
                Text("ACCOUNT SETTINGS", color = androidx.compose.ui.graphics.Color(0xFFDAE5FF), style = MaterialTheme.typography.labelMedium)
                Text(
                    "Manage your account",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "Your profile page is for your profile and cover photos only. Manage sensitive account changes securely here.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (loading) CircularProgressIndicator()
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        current?.let { p ->
            ElevatedCard(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = androidx.compose.ui.graphics.Color(0xFFE8EEFF)
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFF3157D5),
                            modifier = Modifier.padding(12.dp).size(26.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Account security", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF102A56))
                        Text("Verified email, mobile number and password changes", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF65748B))
                        Text(p.email, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF3157D5))
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Open account security", tint = androidx.compose.ui.graphics.Color(0xFF3157D5))
                }
                Button(
                    onClick = { security = true },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).height(50.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Security Center")
                }
            }
        }

        ElevatedCard(
            Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Professional profile", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF102A56))
                Text("Edit your Bio, Services and Skills & Tools. These details appear on your public profile.", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF65748B))
                Button(
                    onClick = { editProfessional = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Edit professional profile")
                }
            }
        }

        OutlinedButton(
            onClick = back,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        ) { Text("Back to profile") }
    }
}

@Composable fun RemoteProfileEditor(profile:RemoteAccount,account:RemoteAccountRepository,done:()->Unit,cancel:()->Unit,openSecurity:(String)->Unit){
    var name by remember{mutableStateOf(profile.displayName)}
    var bio by remember{mutableStateOf(profile.bio)}
    var capabilities by remember{mutableStateOf(profile.capabilities)}
    var skills by remember{mutableStateOf(profile.skills)}
    val countries=listOf(
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
    val existingCountry= countries.filter{profile.mobileNumber.trim().startsWith(it.second)}.maxByOrNull{it.second.length}
    var countryCode by remember(profile.mobileNumber){mutableStateOf(existingCountry?.second ?: "+971")}
    var mobileNumber by remember(profile.mobileNumber){mutableStateOf(if(existingCountry!=null) profile.mobileNumber.trim().removePrefix(existingCountry.second).trim() else profile.mobileNumber.trim().removePrefix("+971").trim())}
    var showCountryPicker by remember{mutableStateOf(false)}
    var countrySearch by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val navy=androidx.compose.ui.graphics.Color(0xFF102A56)
    val blue=androidx.compose.ui.graphics.Color(0xFF3157D5)
    val muted=androidx.compose.ui.graphics.Color(0xFF65748B)
    val valid=Validation.text(name,2,120)&&Validation.text(bio,0,500)&&Validation.text(capabilities,0,2000)&&Validation.text(skills,0,1000)
    if(showCountryPicker){
        AlertDialog(
            onDismissRequest={showCountryPicker=false;countrySearch=""},
            title={
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("Select your country",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=navy,style=MaterialTheme.typography.titleLarge)
                    Text("Search by country name or calling code",color=muted,style=MaterialTheme.typography.bodySmall)
                }
            },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    OutlinedTextField(
                        value=countrySearch,
                        onValueChange={countrySearch=it},
                        label={Text("Search countries")},
                        placeholder={Text("e.g. Bangladesh or +880")},
                        leadingIcon={Icon(Icons.Filled.Search,contentDescription=null,tint=blue)},
                        trailingIcon={if(countrySearch.isNotEmpty()) IconButton(onClick={countrySearch=""}){Icon(Icons.Filled.Close,contentDescription="Clear search")} },
                        singleLine=true,
                        shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier=Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        Text("COUNTRY",style=MaterialTheme.typography.labelSmall,color=muted,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("CODE",style=MaterialTheme.typography.labelSmall,color=muted,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    val filteredCountries=countries.filter{it.first.contains(countrySearch,true)||it.second.contains(countrySearch)}
                    if(filteredCountries.isEmpty()){
                        Column(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Icon(Icons.Filled.SearchOff,contentDescription=null,tint=muted,modifier=Modifier.size(32.dp))
                            Text("No matching country found",color=muted,style=MaterialTheme.typography.bodyMedium)
                            Text("Try another country name or code.",color=muted,style=MaterialTheme.typography.bodySmall)
                        }
                    }else{
                        LazyColumn(Modifier.heightIn(max=340.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            items(filteredCountries){country->
                                val selected=country.second==countryCode
                                Row(
                                    Modifier.fillMaxWidth()
                                        .then(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)))
                                        .background(if(selected) androidx.compose.ui.graphics.Color(0xFFEAF0FF) else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable{countryCode=country.second;showCountryPicker=false;countrySearch=""}
                                        .padding(horizontal=12.dp,vertical=13.dp),
                                    horizontalArrangement=Arrangement.SpaceBetween,
                                    verticalAlignment=androidx.compose.ui.Alignment.CenterVertically
                                ){
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                                        Text(country.first,color=navy,fontWeight=if(selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium)
                                        if(selected) Text("Currently selected",color=blue,style=MaterialTheme.typography.labelSmall)
                                    }
                                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        Text(country.second,color=blue,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                                        if(selected) Icon(Icons.Filled.CheckCircle,contentDescription="Selected",tint=blue,modifier=Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={showCountryPicker=false;countrySearch=""}){Text("Done",color=blue,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)}},
            shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            containerColor=androidx.compose.ui.graphics.Color.White
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF4F7FC)).padding(horizontal=18.dp),
        contentPadding=PaddingValues(top=18.dp,bottom=28.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
        item{
            Surface(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=navy){
                Column(Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(navy,blue))).padding(22.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    Text("ACCOUNT SETTINGS",color=androidx.compose.ui.graphics.Color(0xFFCEDBFF),style=MaterialTheme.typography.labelMedium)
                    Text("Edit your profile",color=androidx.compose.ui.graphics.Color.White,style=MaterialTheme.typography.headlineSmall,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Keep your professional information accurate and up to date.",color=androidx.compose.ui.graphics.Color.White.copy(alpha=0.86f),style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=androidx.compose.ui.graphics.Color.White)){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("Personal information",style=MaterialTheme.typography.titleMedium,color=navy,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                    OutlinedTextField(name,{name=it},label={Text("Full name")},leadingIcon={Icon(Icons.Filled.Person,contentDescription=null)},singleLine=true,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(profile.email,{},label={Text("Account email")},leadingIcon={Icon(Icons.Filled.Email,contentDescription=null)},singleLine=true,readOnly=true,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                    Text("To change your email, use Account security settings.",style=MaterialTheme.typography.bodySmall,color=muted)
                    Text("Mobile number changes are managed in Account Security.",style=MaterialTheme.typography.bodySmall,color=muted)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=androidx.compose.ui.graphics.Color.White)){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("Professional profile",style=MaterialTheme.typography.titleMedium,color=navy,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                    OutlinedTextField(bio,{bio=it},label={Text("Professional bio")},placeholder={Text("Introduce yourself and your experience")},minLines=3,maxLines=5,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(capabilities,{capabilities=it},label={Text("Services you provide")},placeholder={Text("Describe the work you can deliver")},minLines=3,maxLines=5,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(skills,{skills=it},label={Text("Skills and tools")},placeholder={Text("Separate items with commas")},minLines=2,maxLines=4,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                    Text("Account type: ${profile.role.name.lowercase().replaceFirstChar{it.uppercase()}}",style=MaterialTheme.typography.bodySmall,color=muted)
                }
            }
        }
        item{
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            Button(enabled=valid&&!busy,onClick={
                busy=true;error=""
                scope.launch(Dispatchers.IO){
                    runCatching{account.updateProfile(RemoteProfileUpdate(name.trim(),bio.trim(),capabilities.trim(),skills.trim(),null))}
                        .onSuccess{withContext(Dispatchers.Main){done()}}
                        .onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Profile update failed."}}
                }
            },modifier=Modifier.fillMaxWidth().height(54.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp)){
                if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=androidx.compose.ui.graphics.Color.White) else Icon(Icons.Filled.CheckCircle,contentDescription=null)
                Spacer(Modifier.width(8.dp));Text(if(busy)"Saving changes…" else "Save profile",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            OutlinedButton(onClick=cancel,modifier=Modifier.fillMaxWidth().height(50.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp)){Text("Cancel")}
        }
    }
}


@Composable
fun RemoteAccountSecurityScreen(
    auth: com.freelancehub.app.auth.AuthService,
    account: RemoteAccountRepository,
    currentEmail: String,
    initialPhone: String = "",
    done: () -> Unit,
    back: () -> Unit
) {
    val securityCountries=listOf(
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

    val initialCountry = securityCountries.filter { initialPhone.startsWith(it.second) }.maxByOrNull { it.second.length }
    var countryCode by remember(initialPhone) { mutableStateOf(initialCountry?.second ?: "+971") }
    var phoneDigits by remember(initialPhone) {
        mutableStateOf(
            if (initialCountry != null) initialPhone.removePrefix(initialCountry.second).filter(Char::isDigit)
            else initialPhone.removePrefix("+971").filter(Char::isDigit)
        )
    }
    var showCountryPicker by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(if (initialPhone.isBlank()) "email" else "phone") }
    var target by remember { mutableStateOf(initialPhone) }
    var otp by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showCurrentPassword by remember { mutableStateOf(false) }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val navy = androidx.compose.ui.graphics.Color(0xFF102A56)
    val blue = androidx.compose.ui.graphics.Color(0xFF3157D5)
    val phoneTarget = countryCode + phoneDigits.filter(Char::isDigit)
    if (showCountryPicker) {
        val filteredCountries = securityCountries.filter {
            it.first.contains(countrySearch, true) || it.second.contains(countrySearch)
        }
        AlertDialog(
            onDismissRequest = { showCountryPicker = false; countrySearch = "" },
            title = { Text("Choose country code", color = navy, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = countrySearch,
                        onValueChange = { countrySearch = it },
                        label = { Text("Search country or dial code") },
                        placeholder = { Text("e.g. United Arab Emirates or +971") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(filteredCountries) { country ->
                            TextButton(
                                onClick = {
                                    countryCode = country.second
                                    target = country.second + phoneDigits.filter(Char::isDigit)
                                    showCountryPicker = false
                                    countrySearch = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(country.first, color = navy)
                                    Text(country.second, color = blue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (filteredCountries.isEmpty()) Text("No matching country found.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false; countrySearch = "" }) { Text("Done") }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            containerColor = androidx.compose.ui.graphics.Color.White
        )
    }
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF4F7FC)).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), color = navy) {
            Column(Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(navy, blue))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SECURITY CENTER", color = androidx.compose.ui.graphics.Color(0xFFCEDBFF), style = MaterialTheme.typography.labelMedium)
                Text("Verify account changes", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("A change is not complete until Supabase verifies the new contact.", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.86f))
            }
        }
        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose what to change", style = MaterialTheme.typography.titleMedium, color = navy, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = method == "phone", onClick = { method = "phone"; target = ""; otp = ""; verifying = false; error = ""; message = "" }, label = { Text("Mobile Number") }, modifier = Modifier.fillMaxWidth())
                    FilterChip(selected = method == "email", onClick = { method = "email"; target = ""; otp = ""; verifying = false; error = ""; message = "" }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                    FilterChip(selected = method == "password", onClick = { method = "password"; target = ""; otp = ""; verifying = false; error = ""; message = "" }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                }
                if (method != "password") {
                Text(if (method == "email") "Current email: $currentEmail" else "Select a country code, then enter your mobile number.", color = androidx.compose.ui.graphics.Color(0xFF65748B), style = MaterialTheme.typography.bodySmall)
                if (!verifying) {
                    if (method == "email") {
                        OutlinedTextField(value = target, onValueChange = { target = it.trimStart() }, label = { Text("New email address") }, leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedButton(onClick = { showCountryPicker = true }, modifier = Modifier.weight(0.85f).height(56.dp)) {
                                Text(countryCode, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                            OutlinedTextField(
                                value = phoneDigits,
                                onValueChange = { phoneDigits = it.filter(Char::isDigit).take(14); target = countryCode + phoneDigits },
                                label = { Text("Mobile number") },
                                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier.weight(1.65f)
                            )
                        }
                    }
                    Button(enabled = !busy && (if (method == "email") target.isNotBlank() && target.contains("@") else phoneTarget.matches(Regex("""^\+[1-9][0-9]{7,14}$"""))), onClick = {
                        busy = true; error = ""; message = ""
                        val completion: (Result<Unit>) -> Unit = { result ->
                            scope.launch(Dispatchers.Main) {
                                busy = false
                                result.onSuccess { verifying = true; message = "Verification requested. Check the new contact for the code sent by Supabase." }
                                    .onFailure { e -> error = e.message ?: "Could not request verification." }
                            }
                        }
                        if (method == "email") {
                            auth.requestEmailChange(target, completion)
                        } else {
                            scope.launch(Dispatchers.IO) {
                                val check = runCatching { account.ensureMobileChangeAllowed() }
                                withContext(Dispatchers.Main) {
                                    check.onSuccess {
                                            target = phoneTarget
                                            auth.requestPhoneChange(phoneTarget, completion)
                                        }
                                        .onFailure { e -> busy = false; error = e.message ?: "Mobile number change is not currently allowed." }
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Requesting verification…" else "Next: send verification") }
                } else {
                    Text("Enter the verification code delivered to the new ${if (method == "email") "email address" else "mobile number"}.", color = androidx.compose.ui.graphics.Color(0xFF65748B))
                    OutlinedTextField(value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(8) }, label = { Text("Verification code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && otp.length >= 6, onClick = {
                        busy = true; error = ""; message = ""
                        auth.verifyAccountChange(if (method == "email") "email_change" else "phone_change", target, otp) { result ->
                            scope.launch(Dispatchers.Main) {
                                busy = false
                                result.onSuccess {
                                    if (method == "phone") {
                                        scope.launch(Dispatchers.IO) {
                                            val saved = runCatching { account.syncVerifiedPhoneToProfile() }
                                            withContext(Dispatchers.Main) {
                                                saved.onSuccess { message = "Mobile number verified and saved to your profile."; done() }
                                                    .onFailure { e -> error = e.message ?: "The verified number could not be saved to the profile." }
                                            }
                                        }
                                    } else {
                                        message = "Email verification accepted. Refreshing account details…"
                                        done()
                                    }
                                }.onFailure { e -> error = e.message ?: "Verification failed." }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Verifying…" else "Verify and save") }
                    TextButton(onClick = { verifying = false; otp = ""; error = ""; message = "" }) { Text("Change contact") }
                }
                }
            }
        }

        if (method == "password") {
        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Change password", style = MaterialTheme.typography.titleMedium, color = navy, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Confirm your current password first. Your new password is saved only after current-password sign-in succeeds.", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF65748B))
                OutlinedTextField(
                    value = currentPassword, onValueChange = { currentPassword = it },
                    label = { Text("Current password") }, singleLine = true,
                    visualTransformation = if (showCurrentPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { showCurrentPassword = !showCurrentPassword }) { Icon(if (showCurrentPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showCurrentPassword) "Hide password" else "Show password") }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(currentPassword)) }, enabled = currentPassword.isNotEmpty()) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy current password") }
                    } }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    label = { Text("New password (at least 8 characters)") }, singleLine = true,
                    visualTransformation = if (showNewPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { showNewPassword = !showNewPassword }) { Icon(if (showNewPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showNewPassword) "Hide password" else "Show password") }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(newPassword)) }, enabled = newPassword.isNotEmpty()) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy new password") }
                    } }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword, onValueChange = { confirmPassword = it },
                    label = { Text("Confirm new password") }, singleLine = true,
                    visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) { Icon(if (showConfirmPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (showConfirmPassword) "Hide password" else "Show password") }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(confirmPassword)) }, enabled = confirmPassword.isNotEmpty()) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy confirmed password") }
                    } }, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && currentPassword.isNotBlank() && newPassword.length >= 8 && newPassword == confirmPassword,
                    onClick = {
                        busy = true; error = ""; message = ""
                        auth.authenticate(
                            signup = false, name = "", email = currentEmail,
                            password = currentPassword,
                            role = com.freelancehub.app.data.UserRole.FREELANCER
                        ) { signInResult ->
                            scope.launch(Dispatchers.Main) {
                                signInResult.onSuccess {
                                    auth.updatePassword(currentPassword, newPassword) { updateResult ->
                                        scope.launch(Dispatchers.Main) {
                                            busy = false
                                            updateResult.onSuccess {
                                                message = "Password updated successfully. Please keep it private."
                                                currentPassword = ""; newPassword = ""; confirmPassword = ""
                                            }.onFailure { e -> error = e.message ?: "Password update failed." }
                                        }
                                    }
                                }.onFailure { e ->
                                    busy = false
                                    error = e.message ?: "Current password verification failed."
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Verifying and updating…" else "Verify current password and update") }
                if (newPassword.isNotEmpty() && confirmPassword.isNotEmpty() && newPassword != confirmPassword) {
                    Text("Passwords do not match.", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        }

        if (message.isNotBlank()) Text(message, color = androidx.compose.ui.graphics.Color(0xFF16764A))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Back to profile") }
    }
}

@Composable fun LegacyProfileScreen(repo:AppRepository,edit:()->Unit,logout:()->Unit){
    val p=repo.profile();Column(Modifier.fillMaxSize().padding(20.dp)){Text("Profile",style=MaterialTheme.typography.headlineSmall);Text(p.name);Text(p.email);Text("Legacy local profile view disabled for active production flow.");Button(onClick=edit){Text("Open profile")};OutlinedButton(onClick=logout){Text("Sign out")}}
}

@Composable fun LegacyProfileEditor(repo:AppRepository,done:()->Unit){ Text("Legacy profile editor is retained only for historical compatibility.") }



data class FreelancerAnalysis(val readiness:Int,val strengths:List<String>,val gaps:List<String>,val suggestedJobs:List<String>)

@Composable fun FreelancerAnalysisScreen(repo:AppRepository,back:()->Unit){
    val p=repo.profile()
    val analysis=remember(p){analyzeFreelancerProfile(p)}
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{
            Text("AI Freelancer Analysis",style=MaterialTheme.typography.headlineSmall)
            Text("Review your declared capabilities, skills and skill levels before applying for jobs.")
            Spacer(Modifier.height(12.dp))
            Text("Profile readiness: ${analysis.readiness}%",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Strengths",style=MaterialTheme.typography.titleMedium)
            analysis.strengths.forEach{Text("✓ $it")}
            Spacer(Modifier.height(8.dp))
            Text("Gaps to improve",style=MaterialTheme.typography.titleMedium)
            analysis.gaps.forEach{Text("• $it")}
            Spacer(Modifier.height(8.dp))
            Text("Suggested work categories",style=MaterialTheme.typography.titleMedium)
            analysis.suggestedJobs.forEach{Text("→ $it")}
            Spacer(Modifier.height(12.dp))
            Text("This local analysis is development guidance; it is not a professional assessment or guarantee of hiring/earnings.")
            Spacer(Modifier.height(8.dp));TextButton(onClick=back){Text("Back")}
        }
    }
}

private fun analyzeFreelancerProfile(p:UserProfile):FreelancerAnalysis{
    if(p.role!=UserRole.FREELANCER) return FreelancerAnalysis(0,listOf("Client profile detected"),listOf("Create a separate Freelancer account to run freelancer analysis"),emptyList())
    val skills=parseTokens(p.skills)
    val levels=parseTokens(p.skillLevels)
    val caps=parseTokens(p.capabilities)
    val strengths=mutableListOf<String>()
    val gaps=mutableListOf<String>()
    if(caps.isNotEmpty()) strengths.add("Clear capability statement: ${caps.take(4).joinToString(", ")}") else gaps.add("Describe exactly what work you can deliver")
    if(skills.isNotEmpty()) strengths.add("Declared skills: ${skills.take(6).joinToString(", ")}") else gaps.add("Add at least one marketable skill")
    if(levels.isNotEmpty()) strengths.add("Skill-level information is provided") else gaps.add("Add a level for each major skill (Beginner/Intermediate/Advanced)")
    if(p.portfolioSummary.isNotBlank()) strengths.add("Portfolio summary is available") else gaps.add("Add 1–3 portfolio examples with the result or outcome")
    val missingLevels=skills.filter{skill->levels.none{it.contains(skill.take(5),true)}}
    if(missingLevels.isNotEmpty()) gaps.add("Add levels for: ${missingLevels.take(4).joinToString(", ")}")
    val suggested=linkedMapOf("Web Development" to listOf("website","web","wordpress","html","css","javascript"),"Graphic Design" to listOf("graphic","photoshop","illustrator","canva","logo"),"Digital Marketing" to listOf("marketing","facebook ads","google ads","social media","seo"),"Content Writing" to listOf("writing","content","copywriting"),"Video Editing" to listOf("video","premiere","capcut","after effects"),"Android/App Development" to listOf("android","kotlin","app","mobile"))
    val text=(p.capabilities+" "+p.skills).lowercase()
    val categories=suggested.filterValues{keys->keys.any{text.contains(it)}}.keys.toList().ifEmpty{listOf("Add more specific skills to unlock category suggestions")}
    val readiness=(100-(gaps.size*20)).coerceIn(0,100)
    return FreelancerAnalysis(readiness,strengths.ifEmpty{listOf("No strengths detected yet")},gaps.ifEmpty{listOf("No major profile gaps detected")},categories)
}

private fun parseTokens(value:String):List<String> = value.split(Regex("[,;\\n]" )).map{it.trim().lowercase()}.filter{it.length>=2}.distinct()

data class ProjectAnalysisResult(val completeness:Int,val strengths:List<String>,val gaps:List<String>,val suggestedSkills:List<String>)

@Composable fun ProjectAnalysisScreen(back:()->Unit){var brief by remember{mutableStateOf("")};var budget by remember{mutableStateOf("")};var deadline by remember{mutableStateOf("")};var analysis by remember{mutableStateOf<ProjectAnalysisResult?>(null)};Column(Modifier.fillMaxSize().padding(20.dp)){Text("Project Analysis",style=MaterialTheme.typography.headlineSmall);Text("Turn your idea into a clearer project brief before hiring.");Spacer(Modifier.height(12.dp));OutlinedTextField(brief,{brief=it},label={Text("Project description / requirements")},minLines=5,modifier=Modifier.fillMaxWidth());OutlinedTextField(budget,{budget=it},label={Text("Budget guidance (optional)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(deadline,{deadline=it},label={Text("Deadline (optional)")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));Button(onClick={analysis=analyzeProject(brief,budget,deadline)},enabled=brief.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Analyze project")};analysis?.let{r->Spacer(Modifier.height(12.dp));Text("Brief completeness: ${r.completeness}%",style=MaterialTheme.typography.titleLarge);if(r.strengths.isNotEmpty()){Text("Detected strengths");r.strengths.forEach{Text("✓ $it")}};if(r.gaps.isNotEmpty()){Text("Needs clarification");r.gaps.forEach{Text("• $it")}};Text("Suggested freelancer skills");r.suggestedSkills.forEach{Text("• $it")}};Spacer(Modifier.height(10.dp));Text("Prototype analysis is deterministic guidance, not a guarantee of cost, timeline or outcome.");TextButton(onClick=back){Text("Back")}}}

private fun analyzeProject(brief:String,budget:String,deadline:String):ProjectAnalysisResult{val gaps=mutableListOf<String>();val strengths=mutableListOf<String>();val lower=brief.lowercase();if(brief.length<80)gaps.add("Requirements are short; add deliverables, acceptance criteria and important constraints.") else strengths.add("Project description contains enough text for an initial analysis.");if(!lower.contains("deadline")&&deadline.isBlank())gaps.add("Define a clear deadline and milestone dates.") else strengths.add("Timeline information is present.");if(!lower.contains("revision"))gaps.add("State the number of revisions included.") else strengths.add("Revision expectations are mentioned.");if(!lower.contains("file")&&!lower.contains("format"))gaps.add("Specify required file formats and delivery method.") else strengths.add("File/format expectations are mentioned.");if(!lower.contains("reference")&&!lower.contains("example"))gaps.add("Add a reference/sample when visual or style expectations matter.");if(budget.isBlank())gaps.add("Add a budget or budget range so freelancers can assess fit.") else strengths.add("Budget guidance is provided.");val completeness=(100-(gaps.size*15)).coerceIn(0,100);return ProjectAnalysisResult(completeness,strengths,gaps,inferSkills(lower))}

private fun inferSkills(text:String):List<String>{val map=linkedMapOf("website" to "Web Development","seo" to "SEO","logo" to "Graphic Design","video" to "Video Editing","social media" to "Social Media","content" to "Content Writing","app" to "Android/App Development","marketing" to "Digital Marketing");return map.filter{ text.contains(it.key) }.values.toList().ifEmpty{listOf("Define the required skill set")}} private fun words(text: String): List<String> {
    return text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }
}
@Composable fun WorkCheckerScreen(back:()->Unit){var requirements by remember{mutableStateOf("")};var work by remember{mutableStateOf("")};var result by remember{mutableStateOf<WorkCheckResult?>(null)};Column(Modifier.fillMaxSize().padding(20.dp)){Text("AI Work Checker",style=MaterialTheme.typography.headlineSmall);Text("Compare your finished work against the client's requirements before submission.");Spacer(Modifier.height(12.dp));OutlinedTextField(requirements,{requirements=it},label={Text("Client requirements")},minLines=4,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));OutlinedTextField(work,{work=it},label={Text("Paste work summary / deliverables")},minLines=5,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));Button(onClick={result=analyzeWork(requirements,work)},enabled=requirements.isNotBlank()&&work.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Check my work")};result?.let{r->Spacer(Modifier.height(16.dp));Text(if(r.passed)"✓ Ready for client review" else "⚠ Needs improvement",style=MaterialTheme.typography.titleLarge);Text("Requirement match: ${r.score}%");if(r.matched.isNotEmpty()){Text("Matched");r.matched.forEach{Text("✓ $it")}};if(r.missing.isNotEmpty()){Text("Missing / unclear");r.missing.forEach{Text("• $it")}};if(r.suggestions.isNotEmpty()){Text("Suggestions");r.suggestions.forEach{Text("→ $it")}};Text("AI assistance is guidance; final acceptance remains with the client.")};Spacer(Modifier.height(12.dp));TextButton(onClick=back){Text("Back")}}}

private fun analyzeWork(requirements:String,work:String):WorkCheckResult{
    val req=requirements.split(Regex("[,;\n.]" )).map{it.trim()}.filter{it.length>=3}.distinct().take(20)
    val matched=req.filter{requirement->
        val phrase=words(requirement)
        val workWords=words(work)
        requirement.length>=8 && work.contains(requirement,true) || (phrase.isNotEmpty() && phrase.count{workWords.contains(it)} >= maxOf(1,phrase.size/2))
    }
    val missing=req.filterNot{matched.contains(it)}
    val score=if(req.isEmpty())0 else (matched.size*100/req.size)
    val suggestions=missing.map{"Review requirement: $it"}.toMutableList()
    if(score<100) suggestions.add("Use the client's exact deliverable names where possible, then re-check.")
    if(work.length<40) suggestions.add("Add a clearer work/delivery summary so the checker has enough evidence.")
    return WorkCheckResult(score,score>=80&&missing.isEmpty(),matched,missing,suggestions.distinct().take(8))
}


data class JobMatch(val job:Job,val score:Int,val reasons:List<String>)

@Composable fun RemoteJobMatchingScreen(
    marketplace: RemoteMarketplaceRepository,
    account: RemoteAccountRepository,
    back:()->Unit
){
    var profile by remember{mutableStateOf<RemoteAccount?>(null)}
    var jobs by remember{mutableStateOf<List<RemoteJob>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){
        scope.launch(Dispatchers.IO){
            val p=runCatching{account.currentUser()}
            val j=runCatching{marketplace.listJobs(50)}
            withContext(Dispatchers.Main){
                p.onSuccess{profile=it}.onFailure{error=it.message?:"Unable to load profile."}
                j.onSuccess{jobs=it}.onFailure{error=it.message?:"Unable to load jobs."}
                loading=false
            }
        }
    }
    val matches=remember(profile,jobs){if(profile?.role==UserRole.FREELANCER)rankRemoteJobs(profile!!,jobs) else emptyList()}
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{
            Text("AI Job Matching",style=MaterialTheme.typography.headlineSmall)
            Text("Matches live marketplace jobs against your server-backed skills and capabilities.")
            Spacer(Modifier.height(12.dp))
            if(loading) CircularProgressIndicator()
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(!loading && profile?.role!=UserRole.FREELANCER) Text("Use a Freelancer account to use skill-aware matching.")
            if(!loading && profile?.role==UserRole.FREELANCER && matches.isEmpty()) Text("No direct matches found. Review live jobs manually.")
        }
        items(matches){m->ElevatedCard(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(16.dp)){Text(m.job.title,style=MaterialTheme.typography.titleMedium);Text("Match: ${m.score}%");if(m.job.skills.isNotEmpty())Text("Skills: ${m.job.skills.joinToString(", ")}");m.reasons.forEach{Text("• $it")}}}}
        item{Spacer(Modifier.height(8.dp));Text("Matching is guidance, not a guarantee of hiring or earnings.");TextButton(onClick=back){Text("Back")}}
    }
}

data class RemoteJobMatch(val job:RemoteJob,val score:Int,val reasons:List<String>)

private fun rankRemoteJobs(profile:RemoteAccount, jobs:List<RemoteJob>):List<RemoteJobMatch>{
    val skills=parseTokens(profile.skills)
    val caps=parseTokens(profile.capabilities)
    return jobs.filter{it.status.equals(JobStatus.OPEN,true) && it.ownerId!=profile.id}.map{job->
        val text=(job.title+" "+job.description+" "+job.skills.joinToString(" ")).lowercase()
        val skillHits=skills.filter{token->text.contains(token,true)}
        val capHits=caps.filter{token->token.length>=4 && text.contains(token,true)}
        val base=skillHits.size*18+capHits.size*12
        val score=(base).coerceAtMost(95)
        val reasons=mutableListOf<String>()
        if(skillHits.isNotEmpty())reasons.add("Skill overlap: ${skillHits.take(5).joinToString(", ")}")
        if(capHits.isNotEmpty())reasons.add("Capability overlap: ${capHits.take(3).joinToString(", ")}")
        if(reasons.isEmpty())reasons.add("No direct overlap detected; review requirements manually")
        RemoteJobMatch(job,score,reasons.distinct())
    }.sortedWith(compareByDescending<RemoteJobMatch>{it.score}.thenBy{it.job.title}).take(10)
}

@Composable fun RemoteFreelancerAnalysisScreen(account:RemoteAccountRepository,back:()->Unit){
    var profile by remember{mutableStateOf<RemoteAccount?>(null)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){
        scope.launch(Dispatchers.IO){
            val result=runCatching{account.currentUser()}
            withContext(Dispatchers.Main){
                result.onSuccess{profile=it}.onFailure{error=it.message?:"Unable to load profile."}
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{Text("Freelancer Profile Analysis",style=MaterialTheme.typography.headlineSmall);if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error);val p=profile;if(p==null){CircularProgressIndicator()}else{val skills=parseTokens(p.skills);val caps=parseTokens(p.capabilities);Text("Account: ${p.displayName}");Text("Skills: ${if(skills.isEmpty())"Not configured" else skills.joinToString(", ")}");Text("Capabilities: ${if(caps.isEmpty())"Not configured" else caps.joinToString(", ")}");if(skills.isEmpty()||caps.isEmpty())Text("Complete your profile with specific skills and capabilities.",color=MaterialTheme.colorScheme.error) else Text("Profile has ${skills.size} declared skill(s) and ${caps.size} capability item(s). Keep these current for better matching.")};Spacer(Modifier.height(12.dp));TextButton(onClick=back){Text("Back")}}
    }
}


