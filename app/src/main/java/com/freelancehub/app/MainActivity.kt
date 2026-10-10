package com.freelancehub.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freelancehub.app.data.*
import com.freelancehub.app.update.PlayUpdateManager
import com.freelancehub.app.network.SecureApiClient
import com.freelancehub.app.security.TokenStore
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
    } else MainShell(repo){
        auth.logout { android.os.Handler(android.os.Looper.getMainLooper()).post {
            repo.clearSession()
            loggedIn=false
        } }
    }
}

@Composable fun LoginScreen(authConfigured:Boolean,onLogin:(String,String,String,UserRole,Boolean,(String)->Unit)->Unit){
    var signup by remember{mutableStateOf(false)};var name by remember{mutableStateOf("")};var email by remember{mutableStateOf("")};var pass by remember{mutableStateOf("")};var role by remember{mutableStateOf(UserRole.FREELANCER)};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val valid=authConfigured && Validation.email(email)&&Validation.password(pass)&&( !signup || Validation.text(name,2,80))
    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center){
        Text("Freelance Hub",style=MaterialTheme.typography.headlineLarge);Text("Global freelance marketplace",style=MaterialTheme.typography.bodyLarge);Spacer(Modifier.height(24.dp))
        if(!authConfigured){Text("Production backend is not configured. Local/demo authentication is disabled.",color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp))}
        if(signup){OutlinedTextField(name,{name=it},label={Text("Full name")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));Text("Account type",style=MaterialTheme.typography.titleMedium);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=role==UserRole.FREELANCER,onClick={role=UserRole.FREELANCER},label={Text("Freelancer")});FilterChip(selected=role==UserRole.CLIENT,onClick={role=UserRole.CLIENT},label={Text("Client")})};Spacer(Modifier.height(10.dp))}
        OutlinedTextField(email,{email=it},label={Text("Email")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));OutlinedTextField(pass,{pass=it},label={Text("Password (8+ chars, letter + number)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(18.dp));
        Button(onClick={if(valid){busy=true;error="";onLogin(email.trim(),name.trim(),pass,role,signup){msg->busy=false;error=msg}}},enabled=valid&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"Authenticating…" else if(signup)"Create account" else "Sign in")}
        Text("Authentication is performed by the configured production backend; credentials are never stored as local account data.",style=MaterialTheme.typography.bodySmall);TextButton(onClick={signup=!signup;error=""}){Text(if(signup)"Already have an account? Sign in" else "Create a new account")}
    }
}
@Composable fun MainShell(repo:AppRepository,logout:()->Unit){
    val context=LocalContext.current
    val backend=remember{RuntimeBackendConfig.current()}
    val remoteMarketplace=remember{RemoteMarketplaceRepository(SecureApiClient(backend),TokenStore(context)::readAccessToken)}
    val remoteAccount=remember{RemoteAccountRepository(SecureApiClient(backend),TokenStore(context)::readAccessToken)}
    var tab by remember{mutableStateOf(0)}
    var editing by remember{mutableStateOf(false)}
    var role by remember{mutableStateOf(repo.profile().role)}
    var showPostJob by remember{mutableStateOf(false)}
    var showWorkChecker by remember{mutableStateOf(false)}
    var showProjectAnalysis by remember{mutableStateOf(false)}
    var showMatching by remember{mutableStateOf(false)}
    var showFreelancerAnalysis by remember{mutableStateOf(false)}
    var activityVersion by remember{mutableStateOf(0)}
        Scaffold(bottomBar={NavigationBar{listOf("Home","Jobs","Courses","Messages","Profile").forEachIndexed{i,n->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("⌂","▣","▤","✉","●")[i])},label={Text(n)})}}}){p->
        Box(Modifier.padding(p)){when{
            showPostJob->RemotePostJobScreen(role,remoteMarketplace,{showPostJob=false}){activityVersion++;showPostJob=false}
            showWorkChecker->WorkCheckerScreen({showWorkChecker=false})
            showProjectAnalysis->ProjectAnalysisScreen({showProjectAnalysis=false})
            showMatching->RemoteJobMatchingScreen(remoteMarketplace,remoteAccount,{showMatching=false})
            showFreelancerAnalysis->RemoteFreelancerAnalysisScreen(remoteAccount,{showFreelancerAnalysis=false})
            editing->RemoteProfileScreen(remoteAccount,{editing=false},{logout()})
            tab==0->RemoteHomeScreen(remoteMarketplace,remoteAccount,role,{showWorkChecker=true},{showProjectAnalysis=true},{showMatching=true},{showFreelancerAnalysis=true}){if(role==UserRole.CLIENT)showPostJob=true}
            tab==1->RemoteJobsScreen(remoteMarketplace,remoteAccount,role,{if(role==UserRole.CLIENT)showPostJob=true})
            tab==2->CoursesScreen()
            tab==3->RemoteCommunicationScreen()
            else->RemoteProfileScreen(remoteAccount,{editing=true},{logout()})
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
    onPostJob: () -> Unit
){
    var me by remember { mutableStateOf<RemoteAccount?>(null) }
    var jobs by remember { mutableStateOf<List<RemoteJob>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun reload(){
        loading = true
        error = ""
        scope.launch(Dispatchers.IO){
            val accountResult = runCatching { account.currentUser() }
            val jobsResult = runCatching { marketplace.listJobs(50) }
            withContext(Dispatchers.Main){
                accountResult.onSuccess { me = it }.onFailure { error = it.message ?: "Unable to load account." }
                jobsResult.onSuccess { jobs = it }.onFailure { error = it.message ?: "Unable to load jobs." }
                loading = false
            }
        }
    }
    LaunchedEffect(Unit){ reload() }
    val mine = me
    val myJobs = if(mine == null) 0 else jobs.count { it.ownerId == mine.id }
    val openJobs = jobs.count { it.status.equals(JobStatus.OPEN, true) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{
            Text("Welcome to Freelance Hub",style=MaterialTheme.typography.headlineMedium)
            Text("Work globally. Learn skills. Build your career.")
            Spacer(Modifier.height(16.dp))
            Text("Live Dashboard",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if(loading) CircularProgressIndicator()
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(role==UserRole.CLIENT){
                Text("My posted jobs: $myJobs")
                Text("Open jobs currently loaded: $openJobs")
            }else{
                Text("Open jobs available: $openJobs")
                Text("Your account: ${mine?.displayName ?: "Loading…"}")
            }
            Spacer(Modifier.height(8.dp))
            Text("Messages & notifications: open Messages")
            Spacer(Modifier.height(20.dp))
            Text("Quick actions",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if(role==UserRole.CLIENT){
                Button(onClick=onPostJob,modifier=Modifier.fillMaxWidth()){Text("Post a job")}
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick=onProjectAnalysis,modifier=Modifier.fillMaxWidth()){Text("Analyze My Project")}
            }else{
                Button(onClick=onWorkCheck,modifier=Modifier.fillMaxWidth()){Text("Check My Work")}
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick=onFreelancerAnalysis,modifier=Modifier.fillMaxWidth()){Text("Analyze My Freelancer Profile")}
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=onMatching,modifier=Modifier.fillMaxWidth()){Text("Find Jobs For Me")}
            Spacer(Modifier.height(8.dp))
            TextButton(onClick={reload()}){Text("Refresh live data")}
        }
        items(jobs.take(5)){ j ->
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical=5.dp)){
                Column(Modifier.padding(16.dp)){
                    Text(j.title,style=MaterialTheme.typography.titleMedium)
                    Text("Budget: ${j.budgetMinor/100.0} ${j.currency}")
                    Text("Status: ${j.status}")
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
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp), onClick = { selected = conversation; error = "" }) {
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

@Composable fun RemoteProfileScreen(account:RemoteAccountRepository,edit:()->Unit,logout:()->Unit){
    var profile by remember{mutableStateOf<RemoteAccount?>(null)}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    var editing by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    fun load(){
        loading=true; error=""
        scope.launch(Dispatchers.IO){
            runCatching{account.currentUser()}.onSuccess{value->withContext(Dispatchers.Main){profile=value;loading=false}}
                .onFailure{e->withContext(Dispatchers.Main){error=e.message?:"Unable to load profile.";loading=false}}
        }
    }
    LaunchedEffect(Unit){load()}
    if(editing && profile!=null){
        RemoteProfileEditor(profile!!,account,{editing=false;load()},{editing=false})
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp)){
        Text("Profile",style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        if(loading) CircularProgressIndicator()
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        profile?.let{p->
            Text(if(p.displayName.isBlank())"Your profile is not completed yet." else p.displayName,style=MaterialTheme.typography.titleLarge)
            Text(p.email)
            Text("Account type: ${p.role.name.lowercase().replaceFirstChar{it.uppercase()}}")
            Spacer(Modifier.height(8.dp))
            if(p.bio.isNotBlank()) Text(p.bio)
            Text(if(p.capabilities.isBlank())"Capabilities: not added" else "Capabilities: ${p.capabilities}")
            Text(if(p.skills.isBlank())"Skills: not added" else "Skills: ${p.skills}")
            Spacer(Modifier.height(20.dp))
            Button(onClick={editing=true},modifier=Modifier.fillMaxWidth()){Text("Edit profile")}
            OutlinedButton(onClick=logout,modifier=Modifier.fillMaxWidth()){Text("Sign out")}
        }
    }
}

@Composable fun RemoteProfileEditor(profile:RemoteAccount,account:RemoteAccountRepository,done:()->Unit,cancel:()->Unit){
    var name by remember{mutableStateOf(profile.displayName)}
    var bio by remember{mutableStateOf(profile.bio)}
    var capabilities by remember{mutableStateOf(profile.capabilities)}
    var skills by remember{mutableStateOf(profile.skills)}
    var error by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val valid=Validation.text(name,2,120)&&Validation.text(bio,0,500)&&Validation.text(capabilities,0,2000)&&Validation.text(skills,0,1000)
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)){
        item{
            Text("Edit profile",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp))
            OutlinedTextField(name,{name=it},label={Text("Name")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(profile.email,{},label={Text("Email (account identity)")},modifier=Modifier.fillMaxWidth(),readOnly=true)
            OutlinedTextField(bio,{bio=it},label={Text("Bio")},minLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(capabilities,{capabilities=it},label={Text("What work can you do?")},minLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(skills,{skills=it},label={Text("Skills")},modifier=Modifier.fillMaxWidth())
            Text("Account type: ${profile.role.name.lowercase().replaceFirstChar{it.uppercase()}} (cannot be changed after account creation)",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(enabled=valid&&!busy,onClick={
                busy=true;error=""
                scope.launch(Dispatchers.IO){
                    runCatching{account.updateProfile(RemoteProfileUpdate(name.trim(),bio.trim(),capabilities.trim(),skills.trim()))}
                        .onSuccess{withContext(Dispatchers.Main){done()}}
                        .onFailure{e->withContext(Dispatchers.Main){busy=false;error=e.message?:"Profile update failed."}}
                }
            },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Saving…" else "Save profile")}
            TextButton(onClick=cancel){Text("Cancel")}
        }
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

private fun inferSkills(text:String):List<String>{val map=linkedMapOf("website" to "Web Development","seo" to "SEO","logo" to "Graphic Design","video" to "Video Editing","social media" to "Social Media","content" to "Content Writing","app" to "Android/App Development","marketing" to "Digital Marketing");return map.filter{ text.contains(it.key) }.values.toList().ifEmpty{listOf("Define the required skill set")}}
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
        items(matches){m->ElevatedCard(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(16.dp)){Text(m.title,style=MaterialTheme.typography.titleMedium);Text("Match: ${m.score}%");if(m.skills.isNotEmpty())Text("Skills: ${m.skills.joinToString(", ")}");m.reasons.forEach{Text("• $it")}}}}
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


