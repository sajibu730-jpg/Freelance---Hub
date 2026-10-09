package com.freelancehub.app.data

enum class UserRole { FREELANCER, CLIENT }

object JobStatus { const val OPEN = "Open"; const val PAUSED = "Paused"; const val CLOSED = "Closed"; const val COMPLETED = "Completed" }
object ProposalStatus { const val SUBMITTED = "Submitted"; const val SHORTLISTED = "Shortlisted"; const val ACCEPTED = "Accepted"; const val REJECTED = "Rejected" }
object WorkSubmissionStatus { const val SUBMITTED = "Submitted"; const val CHANGES_REQUESTED = "Changes requested"; const val APPROVED = "Approved" }

data class Job(
    val id:Int,
    val title:String,
    val client:String,
    val budget:String,
    val skills:String,
    val description:String="",
    val remote:Boolean=true,
    val status:String=JobStatus.OPEN,
    val createdAt:Long=System.currentTimeMillis(),
    val deliverables:String="",
    val revisions:String="",
    val fileFormats:String="",
    val reference:String="",
    val ownerEmail:String=""
)

data class Proposal(
    val jobId:Int,
    val coverLetter:String,
    val bid:String,
    val delivery:String,
    val status:String=ProposalStatus.SUBMITTED,
    val createdAt:Long=System.currentTimeMillis(),
    val freelancerEmail:String="",
    val freelancerName:String=""
)

data class UserProfile(
    val name:String="",
    val email:String="",
    val bio:String="",
    val capabilities:String="",
    val skills:String="",
    val role:UserRole=UserRole.FREELANCER,
    val skillLevels:String="",
    val portfolioSummary:String="",
    val portfolioLinks:String=""
)

data class WorkSubmission(
    val jobId:Int,
    val freelancerEmail:String,
    val freelancerName:String,
    val summary:String,
    val fileLinks:String="",
    val checkScore:Int=0,
    val status:String=WorkSubmissionStatus.SUBMITTED,
    val createdAt:Long=System.currentTimeMillis()
)

data class Review(
    val jobId:Int,
    val reviewerEmail:String,
    val reviewerName:String,
    val revieweeEmail:String,
    val revieweeName:String,
    val rating:Int,
    val comment:String="",
    val createdAt:Long=System.currentTimeMillis()
)

data class ChatMessage(
    val id:Long,
    val jobId:Int,
    val senderEmail:String,
    val senderName:String,
    val recipientEmail:String,
    val recipientName:String,
    val body:String,
    val createdAt:Long=System.currentTimeMillis(),
    val read:Boolean=false
)

data class Notification(
    val id:Long,
    val title:String,
    val message:String,
    val createdAt:Long=System.currentTimeMillis(),
    val read:Boolean=false,
    val audienceEmail:String=""
)

data class WorkCheckResult(
    val score:Int,
    val passed:Boolean,
    val matched:List<String>,
    val missing:List<String>,
    val suggestions:List<String>
)
