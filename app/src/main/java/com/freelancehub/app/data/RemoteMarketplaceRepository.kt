package com.freelancehub.app.data

import com.freelancehub.app.network.SecureApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Server-backed marketplace data source.
 *
 * This class deliberately does not fall back to SharedPreferences. A successful
 * response from the API is the source of truth for jobs/proposals. Local data
 * remains cache/UI state only until a real backend is deployed.
 */
class RemoteMarketplaceRepository(
    private val client: SecureApiClient,
    private val accessTokenProvider: () -> String?
) {
    fun listJobs(limit: Int = 20, cursor: String? = null): List<RemoteJob> {
        require(limit in 1..50) { "limit must be 1..50" }
        val query = buildString {
            append("?limit=").append(limit)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        }
        val response = client.readJsonResponse(client.openGet("/jobs$query", accessTokenProvider()))
        val root = JSONObject(String(response, StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("jobs") ?: JSONArray()
        return (0 until items.length()).map { parseJob(items.getJSONObject(it)) }
    }

    fun createJob(input: RemoteJobCreate): RemoteJob {
        require(input.title.length in 3..160)
        require(input.description.length in 20..10_000)
        require(input.budgetMinor >= 0)
        require(input.currency.matches(Regex("^[A-Z]{3}$")))
        val payload = JSONObject()
            .put("title", input.title.trim())
            .put("description", input.description.trim())
            .put("budget_minor", input.budgetMinor)
            .put("currency", input.currency)
            .put("skills", JSONArray(input.skills.map { it.trim() }.filter { it.isNotBlank() }))
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/jobs", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseJob(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun createProposal(jobId: String, input: RemoteProposalCreate): RemoteProposal {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.coverLetter.length in 20..10_000)
        require(input.bidMinor >= 0)
        require(input.currency.matches(Regex("^[A-Z]{3}$")))
        require(input.deliveryDays in 1..3650)
        val payload = JSONObject()
            .put("cover_letter", input.coverLetter.trim())
            .put("bid_minor", input.bidMinor)
            .put("currency", input.currency)
            .put("delivery_days", input.deliveryDays)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/jobs/$jobId/proposals", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseProposal(JSONObject(String(response, StandardCharsets.UTF_8)))
    }


    fun acceptProposal(proposalId: String): RemoteContract {
        require(proposalId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val response = client.readJsonResponse(
            client.openJsonPatch("/proposals/$proposalId", JSONObject().put("status", ProposalStatus.ACCEPTED).toString().toByteArray(StandardCharsets.UTF_8), accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseContract(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun getContractForJob(jobId: String): RemoteContract {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val response = client.readJsonResponse(client.openGet("/jobs/$jobId/contract", accessTokenProvider()))
        return parseContract(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun listMyContracts(): List<RemoteContract> {
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/contracts", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("contracts") ?: JSONArray()
        return (0 until items.length()).map { parseContract(items.getJSONObject(it)) }
    }

    fun createWorkSubmission(jobId: String, input: RemoteWorkSubmissionCreate): RemoteWorkSubmission {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.summary.length in 10..10_000)
        require(input.checkScore in 0..100)
        val payload = JSONObject()
            .put("summary", input.summary.trim())
            .put("file_links", JSONArray(input.fileLinks.map { it.trim() }.filter { it.isNotBlank() }))
            .put("check_score", input.checkScore)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/jobs/$jobId/work-submissions", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseWorkSubmission(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun listWorkSubmissions(jobId: String): List<RemoteWorkSubmission> {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/jobs/$jobId/work-submissions", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("submissions") ?: JSONArray()
        return (0 until items.length()).map { parseWorkSubmission(items.getJSONObject(it)) }
    }

    fun analyzeWorkSubmission(submissionId: String, input: RemoteWorkAnalysisCreate): RemoteWorkAnalysis {
        require(submissionId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.completedRequirements.length in 1..10_000)
        require(input.issues.length <= 10_000)
        require(input.solutions.length <= 10_000)
        require(input.results.length <= 10_000)
        val payload = JSONObject()
            .put("completed_requirements", input.completedRequirements.trim())
            .put("issues", input.issues.trim())
            .put("solutions", input.solutions.trim())
            .put("results", input.results.trim())
            .put("future_improvements", input.futureImprovements.trim())
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/work-submissions/$submissionId/analysis", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseWorkAnalysis(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun reviewWorkSubmission(submissionId: String, input: RemoteWorkReviewCreate): RemoteWorkReview {
        require(submissionId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.status == WorkSubmissionStatus.APPROVED || input.status == WorkSubmissionStatus.CHANGES_REQUESTED)
        require(input.comment.length <= 10_000)
        val payload = JSONObject()
            .put("status", input.status)
            .put("comment", input.comment.trim())
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPatch("/work-submissions/$submissionId/review", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseWorkReview(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun listWorkSubmissionRevisions(submissionId: String): List<RemoteWorkRevision> {
        require(submissionId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/work-submissions/$submissionId/revisions", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("revisions") ?: JSONArray()
        return (0 until items.length()).map { parseWorkRevision(items.getJSONObject(it)) }
    }

    private fun parseWorkSubmission(x: JSONObject) = RemoteWorkSubmission(
        id = x.getString("id"), jobId = x.getString("job_id"), summary = x.optString("summary"),
        fileLinks = parseStringList(x.optJSONArray("file_links")), checkScore = x.optInt("check_score", 0),
        status = x.optString("status", WorkSubmissionStatus.SUBMITTED)
    )

    private fun parseWorkAnalysis(x: JSONObject) = RemoteWorkAnalysis(
        id = x.getString("id"), submissionId = x.getString("submission_id"),
        completedRequirements = x.optString("completed_requirements"), issues = x.optString("issues"),
        solutions = x.optString("solutions"), results = x.optString("results"), futureImprovements = x.optString("future_improvements")
    )

    private fun parseWorkReview(x: JSONObject) = RemoteWorkReview(
        submissionId = x.getString("submission_id"), status = x.optString("status"), comment = x.optString("comment")
    )

    private fun parseWorkRevision(x: JSONObject) = RemoteWorkRevision(
        id = x.getString("id"), submissionId = x.getString("submission_id"), version = x.optInt("version", 1),
        summary = x.optString("summary"), fileLinks = parseStringList(x.optJSONArray("file_links")),
        createdAt = x.optString("created_at")
    )

    private fun parseContract(x: JSONObject) = RemoteContract(
        id = x.getString("id"), jobId = x.getString("job_id"), proposalId = x.getString("proposal_id"),
        clientId = x.getString("client_id"), freelancerId = x.getString("freelancer_id"),
        agreedBudgetMinor = x.optLong("agreed_budget_minor", 0), currency = x.optString("currency", "USD"),
        status = x.optString("status", "ACTIVE")
    )

    private fun parseJob(x: JSONObject) = RemoteJob(
        id = x.getString("id"),
        title = x.getString("title"),
        description = x.optString("description"),
        budgetMinor = x.optLong("budget_minor", 0),
        currency = x.optString("currency", "USD"),
        status = x.optString("status", JobStatus.OPEN),
        ownerId = x.optString("owner_id", ""),
        skills = parseStringList(x.optJSONArray("skills"))
    )

    private fun parseStringList(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it, null)?.trim() }.filter { it.isNotBlank() }

    private fun parseProposal(x: JSONObject) = RemoteProposal(
        id = x.getString("id"),
        jobId = x.getString("job_id"),
        status = x.optString("status", ProposalStatus.SUBMITTED)
    )
}

data class RemoteJob(
    val id: String,
    val title: String,
    val description: String,
    val budgetMinor: Long,
    val currency: String,
    val status: String,
    val ownerId: String,
    val skills: List<String> = emptyList()
)

data class RemoteJobCreate(
    val title: String,
    val description: String,
    val budgetMinor: Long,
    val currency: String,
    val skills: List<String> = emptyList()
)

data class RemoteProposal(
    val id: String,
    val jobId: String,
    val status: String
)

data class RemoteContract(
    val id: String, val jobId: String, val proposalId: String, val clientId: String, val freelancerId: String,
    val agreedBudgetMinor: Long, val currency: String, val status: String
)

data class RemoteProposalCreate(
    val coverLetter: String,
    val bidMinor: Long,
    val currency: String,
    val deliveryDays: Int
)


data class RemoteWorkSubmissionCreate(
    val summary: String,
    val fileLinks: List<String> = emptyList(),
    val checkScore: Int = 0
)

data class RemoteWorkSubmission(
    val id: String, val jobId: String, val summary: String, val fileLinks: List<String>,
    val checkScore: Int, val status: String
)

data class RemoteWorkAnalysisCreate(
    val completedRequirements: String, val issues: String = "", val solutions: String = "",
    val results: String = "", val futureImprovements: String = ""
)

data class RemoteWorkAnalysis(
    val id: String, val submissionId: String, val completedRequirements: String, val issues: String,
    val solutions: String, val results: String, val futureImprovements: String
)

data class RemoteWorkReviewCreate(val status: String, val comment: String = "")
data class RemoteWorkReview(val submissionId: String, val status: String, val comment: String)

data class RemoteWorkRevision(
    val id: String, val submissionId: String, val version: Int, val summary: String,
    val fileLinks: List<String>, val createdAt: String
)
