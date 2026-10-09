package com.freelancehub.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.freelancehub.app.security.AuthorizationRules
import com.freelancehub.app.security.CommunicationRules
import com.freelancehub.app.security.AccountRoleRules
import com.freelancehub.app.security.TokenStore

class AppRepository(context: Context) {
    private val tokenStore = TokenStore(context.applicationContext)
    private val prefs = context.getSharedPreferences("freelance_hub", Context.MODE_PRIVATE)

    /** Authentication authority is the secure token, never a local boolean preference. */
    fun isLoggedIn(): Boolean = !tokenStore.readAccessToken().isNullOrBlank()

    fun clearSession() {
        tokenStore.clear()
        prefs.edit().remove("logged_in").apply()
    }

    /** Local profile is a cache only; backend authentication remains authoritative. */
    fun cacheAuthenticatedProfile(name: String, email: String, role: UserRole) {
        if (!Validation.email(email) || !Validation.text(name, 2, 80)) return
        prefs.edit()
            .putString("name", name.trim())
            .putString("email", email.trim())
            .putString("role", role.name)
            .putBoolean("role_initialized", true)
            .remove("logged_in")
            .apply()
    }

    fun profile(): UserProfile = UserProfile(
        prefs.getString("name", "") ?: "",
        prefs.getString("email", "") ?: "",
        prefs.getString("bio", "") ?: "",
        prefs.getString("capabilities", "") ?: "",
        prefs.getString("skills", "") ?: "",
        runCatching { UserRole.valueOf(prefs.getString("role", UserRole.FREELANCER.name) ?: UserRole.FREELANCER.name) }.getOrDefault(UserRole.FREELANCER),
        prefs.getString("skill_levels", "") ?: "",
        prefs.getString("portfolio_summary", "") ?: "",
        prefs.getString("portfolio_links", "") ?: ""
    )

    fun saveProfile(p:UserProfile) {
        val initialized = prefs.getBoolean("role_initialized", false)
        val storedRole = profile().role
        val effectiveRole = AccountRoleRules.roleForSave(storedRole, p.role, initialized)
        if (!Validation.text(p.name, 2, 80) || !Validation.email(p.email) ||
            !Validation.text(p.bio, 0, 500) || !Validation.text(p.capabilities, 0, 2000) ||
            !Validation.text(p.skills, 0, 1000) || !Validation.text(p.skillLevels, 0, 1000) ||
            !Validation.text(p.portfolioSummary, 0, 3000) || !Validation.text(p.portfolioLinks, 0, 4000) ||
            (p.portfolioLinks.isNotBlank() && !Validation.httpsUrlList(p.portfolioLinks))) return
        if (effectiveRole == UserRole.FREELANCER &&
            (p.capabilities.isBlank() || p.skills.isBlank() || p.skillLevels.isBlank())) return
        prefs.edit()
            .putString("name",p.name).putString("email",p.email).putString("bio",p.bio)
            .putString("capabilities",p.capabilities).putString("skills",p.skills).putString("role",effectiveRole.name).putString("skill_levels",p.skillLevels)
            .putString("portfolio_summary",p.portfolioSummary).putString("portfolio_links",p.portfolioLinks)
            .apply()
    }

    fun initializeAccountRole(role: UserRole) {
        if (!AccountRoleRules.canInitializeRole(prefs.getBoolean("role_initialized", false))) return
        if (!AccountRoleRules.validRole(role)) return
        prefs.edit().putString("role", role.name).putBoolean("role_initialized", true).apply()
    }

    fun proposals(): List<Proposal> {
        val a=JSONArray(prefs.getString("proposals","[]"))
        return (0 until a.length()).map { o ->
            val x=a.getJSONObject(o)
            Proposal(x.getInt("jobId"),x.getString("coverLetter"),x.getString("bid"),x.getString("delivery"),x.optString("status","Submitted"),x.optLong("createdAt",0L),x.optString("freelancerEmail",""),x.optString("freelancerName",""))
        }.sortedByDescending { it.createdAt }
    }

    fun isFreelancerProfileReady(profile:UserProfile = profile()):Boolean =
        ProfileReadiness.isReady(profile)

    fun profileReadinessGaps(profile:UserProfile = profile()):List<String> =
        ProfileReadiness.gaps(profile)

    fun hasProposal(jobId:Int, freelancerEmail:String = profile().email):Boolean =
        proposals().any { it.jobId==jobId && it.freelancerEmail.equals(freelancerEmail,true) }

    fun addProposal(p:Proposal):Boolean {
        val job=savedJobs().firstOrNull { it.id==p.jobId } ?: return false
        val bid=p.bid.trim().toDoubleOrNull()
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.SUBMIT_PROPOSAL)) return false
        if(job.status!=JobStatus.OPEN) return false
        if(p.coverLetter.trim().length !in 20..2000) return false
        if(bid==null || bid<=0.0 || bid>100000000.0) return false
        if(p.delivery.trim().length !in 2..120) return false
        if(p.freelancerEmail.isBlank() || hasProposal(p.jobId,p.freelancerEmail)) return false
        if(!isFreelancerProfileReady() || !p.freelancerEmail.equals(profile().email,true)) return false
        val all=proposals()+p
        val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("coverLetter",x.coverLetter);put("bid",x.bid);put("delivery",x.delivery);put("status",x.status);put("createdAt",x.createdAt);put("freelancerEmail",x.freelancerEmail);put("freelancerName",x.freelancerName)
        })}; prefs.edit().putString("proposals",a.toString()).apply()
        addNotification("Proposal submitted", "Your proposal for job #${p.jobId} was recorded.", p.freelancerEmail)
        return true
    }

    fun updateProposalStatus(jobId:Int,status:String, freelancerEmail:String = "", actorEmail:String = profile().email) {
        val job=savedJobs().firstOrNull { it.id==jobId } ?: return
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.MANAGE_PROPOSAL)) return
        if(!AuthorizationRules.ownsResource(actorEmail, job.ownerEmail)) return
        if(job.status==JobStatus.CLOSED || job.status==JobStatus.COMPLETED) return
        if (proposals().any { it.jobId==jobId && (freelancerEmail.isBlank() || it.freelancerEmail.equals(freelancerEmail,true)) && !StateTransitionRules.proposalAllowed(it.status,status) }) return
        val a=JSONArray(); proposals().forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("coverLetter",x.coverLetter);put("bid",x.bid);put("delivery",x.delivery);put("status",if(x.jobId==jobId && (freelancerEmail.isBlank() || x.freelancerEmail.equals(freelancerEmail,true)))status else x.status);put("createdAt",x.createdAt);put("freelancerEmail",x.freelancerEmail);put("freelancerName",x.freelancerName)
        })}; prefs.edit().putString("proposals",a.toString()).apply()
        addNotification("Proposal status updated", "Job #${jobId} proposal is now $status.", freelancerEmail)
    }

    fun acceptProposal(jobId:Int, freelancerEmail:String, actorEmail:String = profile().email) {
        val job=savedJobs().firstOrNull { it.id==jobId } ?: return
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.MANAGE_PROPOSAL)) return
        if(!AuthorizationRules.ownsResource(actorEmail, job.ownerEmail)) return
        if(job.status==JobStatus.CLOSED || job.status==JobStatus.COMPLETED) return
        val current=proposals()
        if(current.none { it.jobId==jobId && it.freelancerEmail.equals(freelancerEmail,true) && (it.status==ProposalStatus.SUBMITTED || it.status==ProposalStatus.SHORTLISTED) }) return
        val a=JSONArray(); current.forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("coverLetter",x.coverLetter);put("bid",x.bid);put("delivery",x.delivery)
            val same=x.jobId==jobId && x.freelancerEmail.equals(freelancerEmail,true)
            val next=if(same) ProposalStatus.ACCEPTED else if(x.jobId==jobId && (x.status==ProposalStatus.SUBMITTED || x.status==ProposalStatus.SHORTLISTED)) ProposalStatus.REJECTED else x.status
            put("status",next);put("createdAt",x.createdAt);put("freelancerEmail",x.freelancerEmail);put("freelancerName",x.freelancerName)
        }) }; prefs.edit().putString("proposals",a.toString()).apply()
        updateJobStatus(jobId,JobStatus.CLOSED,actorEmail)
        addNotification("Proposal accepted", "Proposal from ${freelancerEmail.ifBlank{"freelancer"}} for job #${jobId} was accepted and the job was closed.", freelancerEmail)
    }

    fun workSubmissions(): List<WorkSubmission> {
        val a=JSONArray(prefs.getString("work_submissions","[]"))
        return (0 until a.length()).map { i ->
            val x=a.getJSONObject(i)
            WorkSubmission(x.getInt("jobId"),x.optString("freelancerEmail",""),x.optString("freelancerName",""),x.optString("summary",""),x.optString("fileLinks",""),x.optInt("checkScore",0),x.optString("status",WorkSubmissionStatus.SUBMITTED),x.optLong("createdAt",0L))
        }.sortedByDescending { it.createdAt }
    }

    fun saveWorkSubmission(s:WorkSubmission) {
        val job=savedJobs().firstOrNull { it.id==s.jobId } ?: return
        val accepted=proposals().firstOrNull { it.jobId==s.jobId && it.status==ProposalStatus.ACCEPTED }
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.SUBMIT_WORK)) return
        if(accepted==null || !s.freelancerEmail.equals(profile().email,true)) return
        if(s.summary.trim().length !in 20..5000 || s.fileLinks.length>4000) return
        if(s.checkScore !in 0..100) return
        val all=workSubmissions().filterNot { it.jobId==s.jobId && it.freelancerEmail.equals(s.freelancerEmail,true) } + s
        val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("freelancerEmail",x.freelancerEmail);put("freelancerName",x.freelancerName);put("summary",x.summary);put("fileLinks",x.fileLinks);put("checkScore",x.checkScore);put("status",x.status);put("createdAt",x.createdAt)
        }) }; prefs.edit().putString("work_submissions",a.toString()).apply()
        addNotification("Work submitted", "Work for job #${s.jobId} was submitted for client review.", job.ownerEmail)
    }

    fun updateWorkSubmissionStatus(jobId:Int, status:String, actorEmail:String = profile().email) {
        val job=savedJobs().firstOrNull { it.id==jobId } ?: return
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.MANAGE_PROPOSAL)) return
        if(!AuthorizationRules.ownsResource(actorEmail, job.ownerEmail)) return
        if(job.status==JobStatus.COMPLETED) return
        if(status!=WorkSubmissionStatus.APPROVED && status!=WorkSubmissionStatus.CHANGES_REQUESTED) return
        val target=workSubmissions().firstOrNull { it.jobId==jobId } ?: return
        if(!StateTransitionRules.workAllowed(target.status,status)) return
        val a=JSONArray(); workSubmissions().forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("freelancerEmail",x.freelancerEmail);put("freelancerName",x.freelancerName);put("summary",x.summary);put("fileLinks",x.fileLinks);put("checkScore",x.checkScore);put("status",if(x.jobId==jobId) status else x.status);put("createdAt",x.createdAt)
        }) }; prefs.edit().putString("work_submissions",a.toString()).apply()
        if(status==WorkSubmissionStatus.APPROVED) updateJobStatus(jobId,JobStatus.COMPLETED,actorEmail)
        addNotification(if(status==WorkSubmissionStatus.APPROVED) "Work approved" else "Changes requested", "Job #${jobId} work status: $status.", target.freelancerEmail)
    }

    fun reviews(): List<Review> {
        val a=JSONArray(prefs.getString("reviews","[]"))
        return (0 until a.length()).map { i ->
            val x=a.getJSONObject(i)
            Review(x.getInt("jobId"),x.optString("reviewerEmail",""),x.optString("reviewerName",""),x.optString("revieweeEmail",""),x.optString("revieweeName",""),x.optInt("rating",0),x.optString("comment",""),x.optLong("createdAt",0L))
        }.sortedByDescending { it.createdAt }
    }

    fun addReview(r:Review):Boolean {
        val actor=profile().email
        val job=savedJobs().firstOrNull { it.id==r.jobId } ?: return false
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.REVIEW_COMPLETED_JOB)) return false
        if(job.status!=JobStatus.COMPLETED || actor.isBlank() || !r.reviewerEmail.equals(actor,true)) return false
        if(r.rating !in 1..5 || r.revieweeEmail.isBlank()) return false
        val accepted=proposals().firstOrNull { it.jobId==r.jobId && it.status==ProposalStatus.ACCEPTED } ?: return false
        val clientEmail=job.ownerEmail
        val freelancerEmail=accepted.freelancerEmail
        val validPair=(actor.equals(clientEmail,true) && r.revieweeEmail.equals(freelancerEmail,true)) ||
            (actor.equals(freelancerEmail,true) && r.revieweeEmail.equals(clientEmail,true))
        if(!validPair || reviews().any { it.jobId==r.jobId && it.reviewerEmail.equals(actor,true) }) return false
        val all=reviews()+r; val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply {
            put("jobId",x.jobId);put("reviewerEmail",x.reviewerEmail);put("reviewerName",x.reviewerName);put("revieweeEmail",x.revieweeEmail);put("revieweeName",x.revieweeName);put("rating",x.rating);put("comment",x.comment);put("createdAt",x.createdAt)
        }) }; prefs.edit().putString("reviews",a.toString()).apply()
        addNotification("Review submitted","Your ${r.rating}-star review for job #${r.jobId} was recorded.", r.revieweeEmail)
        return true
    }

    fun reviewsFor(email:String=profile().email):List<Review> = reviews().filter { it.revieweeEmail.equals(email,true) }

    fun messages(): List<ChatMessage> {
        val a=JSONArray(prefs.getString("messages","[]"))
        return (0 until a.length()).map { i ->
            val x=a.getJSONObject(i)
            ChatMessage(x.optLong("id",0L),x.getInt("jobId"),x.optString("senderEmail",""),x.optString("senderName",""),x.optString("recipientEmail",""),x.optString("recipientName",""),x.optString("body",""),x.optLong("createdAt",0L),x.optBoolean("read",false))
        }.sortedByDescending { it.createdAt }
    }

    fun conversation(jobId:Int, otherEmail:String, actorEmail:String=profile().email):List<ChatMessage> =
        messages().filter { it.jobId==jobId && ((it.senderEmail.equals(actorEmail,true) && it.recipientEmail.equals(otherEmail,true)) || (it.senderEmail.equals(otherEmail,true) && it.recipientEmail.equals(actorEmail,true))) }.sortedBy { it.createdAt }

    fun unreadMessages(actorEmail:String=profile().email):Int = if(actorEmail.isBlank()) 0 else messages().count { it.recipientEmail.equals(actorEmail,true) && !it.read }

    fun markMessagesRead(jobId:Int, otherEmail:String, actorEmail:String=profile().email) {
        if(actorEmail.isBlank() || otherEmail.isBlank()) return
        val job=savedJobs().firstOrNull { it.id==jobId } ?: return
        val accepted=proposals().firstOrNull { it.jobId==jobId && it.status==ProposalStatus.ACCEPTED } ?: return
        if(!CommunicationRules.canReadConversation(actorEmail, otherEmail, job.ownerEmail, accepted.freelancerEmail)) return
        val a=JSONArray(); messages().forEach { x ->
            val mark=x.jobId==jobId && x.senderEmail.equals(otherEmail,true) && x.recipientEmail.equals(actorEmail,true)
            a.put(JSONObject().apply { put("id",x.id);put("jobId",x.jobId);put("senderEmail",x.senderEmail);put("senderName",x.senderName);put("recipientEmail",x.recipientEmail);put("recipientName",x.recipientName);put("body",x.body);put("createdAt",x.createdAt);put("read",if(mark) true else x.read) })
        }; prefs.edit().putString("messages",a.toString()).apply()
    }

    fun sendMessage(jobId:Int, recipientEmail:String, recipientName:String, body:String):Boolean {
        val actor=profile()
        if(actor.email.isBlank() || recipientEmail.isBlank() || body.isBlank() || actor.email.equals(recipientEmail,true)) return false
        val job=savedJobs().firstOrNull { it.id==jobId }
        val accepted=proposals().firstOrNull { it.jobId==jobId && it.status==ProposalStatus.ACCEPTED }
        if(job==null || accepted==null) return false
        val isClient = job.ownerEmail.equals(actor.email,true) && recipientEmail.equals(accepted.freelancerEmail,true)
        val isFreelancer = accepted.freelancerEmail.equals(actor.email,true) && recipientEmail.equals(job.ownerEmail,true)
        if(!AuthorizationRules.isAllowed(actor.role, AuthorizationRules.Action.SEND_JOB_MESSAGE)) return false
        if(!isClient && !isFreelancer) return false
        val m=ChatMessage(System.currentTimeMillis(),jobId,actor.email,actor.name,recipientEmail,recipientName,body.trim(),System.currentTimeMillis(),false)
        val all=(messages()+m).takeLast(200)
        val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply { put("id",x.id);put("jobId",x.jobId);put("senderEmail",x.senderEmail);put("senderName",x.senderName);put("recipientEmail",x.recipientEmail);put("recipientName",x.recipientName);put("body",x.body);put("createdAt",x.createdAt);put("read",x.read) }) }
        prefs.edit().putString("messages",a.toString()).apply()
        addNotification("New message", "Message sent for job #${jobId}.", recipientEmail)
        return true
    }

    private fun notificationReadIds(): Set<Long> =
        prefs.getString("notification_read_ids", "").orEmpty()
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    fun notifications(actorEmail:String = profile().email): List<Notification> {
        val readIds = notificationReadIds()
        val a=JSONArray(prefs.getString("notifications","[]"))
        return (0 until a.length()).map { i ->
            val x=a.getJSONObject(i)
            val id=x.optLong("id",0L)
            Notification(id,x.optString("title","Activity"),x.optString("message",""),x.optLong("createdAt",0L),x.optBoolean("read",false) || readIds.contains(id),x.optString("audienceEmail",""))
        }.filter { it.audienceEmail.isBlank() || it.audienceEmail.equals(actorEmail,true) }.sortedByDescending { it.createdAt }
    }

    fun addNotification(title:String,message:String,audienceEmail:String="") {
        val all=(listOf(Notification(System.currentTimeMillis(),title,message,audienceEmail=audienceEmail)) + notifications("")).take(50)
        val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply { put("id",x.id);put("title",x.title);put("message",x.message);put("createdAt",x.createdAt);put("read",x.read);put("audienceEmail",x.audienceEmail) }) }
        prefs.edit().putString("notifications",a.toString()).apply()
    }

    fun markNotificationsRead(actorEmail:String = profile().email) {
        if(actorEmail.isBlank()) return
        val readIds = notificationReadIds().toMutableSet()
        notifications("").forEach { x ->
            if (CommunicationRules.canMarkNotificationRead(actorEmail, x.audienceEmail)) readIds += x.id
        }
        prefs.edit().putString("notification_read_ids", readIds.sorted().joinToString(",")).apply()
    }

    fun favoriteJobIds(): Set<Int> =
        prefs.getString("favorite_job_ids", "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    fun isFavoriteJob(jobId:Int):Boolean = favoriteJobIds().contains(jobId)

    fun toggleFavoriteJob(jobId:Int):Boolean {
        if(jobId<=0) return false
        val ids=favoriteJobIds().toMutableSet()
        val added=if(ids.contains(jobId)){ ids.remove(jobId); false } else { ids.add(jobId); true }
        prefs.edit().putString("favorite_job_ids", ids.sorted().joinToString(",")).apply()
        return added
    }

    fun savedJobs(): List<Job> {
        val a=JSONArray(prefs.getString("jobs","[]")); return (0 until a.length()).map { i ->
            val x=a.getJSONObject(i); Job(id=x.getInt("id"),title=x.getString("title"),client=x.getString("client"),budget=x.getString("budget"),skills=x.getString("skills"),description=x.optString("description",""),remote=x.optBoolean("remote",true),status=x.optString("status","Open"),createdAt=x.optLong("createdAt",0L),deliverables=x.optString("deliverables",""),revisions=x.optString("revisions",""),fileFormats=x.optString("fileFormats",""),reference=x.optString("reference",""),ownerEmail=x.optString("ownerEmail",""))
        }
    }

    fun updateJobStatus(jobId:Int,status:String, actorEmail:String = profile().email) {
        val existing=savedJobs().firstOrNull { it.id==jobId } ?: return
        if(!AuthorizationRules.isAllowed(profile().role, AuthorizationRules.Action.MANAGE_OWN_JOB)) return
        if(!AuthorizationRules.ownsResource(actorEmail, existing.ownerEmail)) return
        if (!StateTransitionRules.jobAllowed(existing.status,status)) return
        val a=JSONArray(); savedJobs().forEach { x -> a.put(JSONObject().apply {
            put("id",x.id);put("title",x.title);put("client",x.client);put("budget",x.budget);put("skills",x.skills);put("description",x.description);put("deliverables",x.deliverables);put("revisions",x.revisions);put("fileFormats",x.fileFormats);put("reference",x.reference);put("remote",x.remote);put("status",if(x.id==jobId)status else x.status);put("createdAt",x.createdAt);put("ownerEmail",x.ownerEmail)
        })}; prefs.edit().putString("jobs",a.toString()).apply()
        addNotification("Job status updated", "Job #${jobId} is now $status.", existing.ownerEmail)
    }

    fun saveJob(job:Job) {
        val actor=profile()
        if(!AuthorizationRules.isAllowed(actor.role, AuthorizationRules.Action.CREATE_JOB) || actor.email.isBlank() || !AuthorizationRules.ownsResource(actor.email, job.ownerEmail)) return
        if(job.id<=0 || job.ownerEmail.isBlank()) return
        if(!Validation.text(job.title,3,120) || !Validation.text(job.client,2,120) ||
            !Validation.text(job.budget,1,120) || !Validation.text(job.skills,2,500) ||
            !Validation.text(job.description,20,5000) || !Validation.text(job.deliverables,2,2000) ||
            !Validation.text(job.revisions,2,500) || !Validation.text(job.fileFormats,2,1000) ||
            job.reference.length>2000) return
        val all=savedJobs().filterNot { it.id==job.id } + job; val a=JSONArray(); all.forEach { x -> a.put(JSONObject().apply {
            put("id",x.id);put("title",x.title);put("client",x.client);put("budget",x.budget);put("skills",x.skills);put("description",x.description);put("deliverables",x.deliverables);put("revisions",x.revisions);put("fileFormats",x.fileFormats);put("reference",x.reference);put("remote",x.remote);put("status",x.status);put("createdAt",x.createdAt);put("ownerEmail",x.ownerEmail)
        })}; prefs.edit().putString("jobs",a.toString()).apply()
        addNotification("Job published", "${job.title} is now available in the job list.", job.ownerEmail)
    }
}
