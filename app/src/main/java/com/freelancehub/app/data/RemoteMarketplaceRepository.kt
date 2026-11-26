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

    val path = "/rest/v1/jobs?select=*&order=created_at.desc&limit=$limit"
    val response = client.readJsonResponse(
        client.openGet(path, accessTokenProvider())
    )

    val items = JSONArray(String(response, StandardCharsets.UTF_8))
    return (0 until items.length()).map { index ->
        parseJob(items.getJSONObject(index))
    }
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
            .put("category", input.category)
            .put("experience_level", input.experienceLevel)
            .put("duration_days", input.durationDays)
            .put("freelancers_needed", input.freelancersNeeded)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/jobs", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseJob(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun createProposal(jobId: String, input: RemoteProposalCreate): RemoteProposal {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Invalid job id" }
        require(input.coverLetter.isNotBlank()) { "Write your cover letter before submitting." }
        require(input.bidMinor > 0) { "Enter a bid greater than zero" }
        require(input.currency.matches(Regex("^[A-Z]{3}$"))) { "Invalid currency" }
        require(input.deliveryDays in 1..3650) { "Delivery time must be between 1 and 3650 days" }

        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Please sign in again before submitting a proposal.")
        val authUser = JSONObject(String(
            client.readJsonResponse(client.openGet("/auth/v1/user", token)),
            StandardCharsets.UTF_8
        ))
        val freelancerId = authUser.optString("id").takeIf {
            it.matches(Regex("^[0-9a-fA-F-]{36}$"))
        } ?: throw IllegalStateException("Your signed-in account could not be verified.")

        val existingPath = "/rest/v1/proposals?select=id&job_id=eq.$jobId&freelancer_id=eq.$freelancerId&limit=1"
        val existing = JSONArray(String(
            client.readJsonResponse(client.openGet(existingPath, token)),
            StandardCharsets.UTF_8
        ))
        if (existing.length() > 0) {
            throw IllegalStateException("You have already submitted a proposal for this job.")
        }

        val payload = JSONObject()
            .put("job_id", jobId)
            .put("freelancer_id", freelancerId)
            .put("cover_letter", input.coverLetter.trim())
            .put("cover_letter_language", input.coverLetterLanguage.ifBlank { "auto" })
            .put("cover_letter_english", input.englishCoverLetter.trim().takeIf { it.isNotBlank() })
            .put("experience_level", input.experienceLevel)
            .put("proposed_budget_minor", input.bidMinor)
            .put("currency", input.currency)
            .put("delivery_days", input.deliveryDays)
            .toString().toByteArray(StandardCharsets.UTF_8)

        val response = client.readJsonResponse(
            client.openJsonPost(
                "/rest/v1/proposals?select=id,job_id,status",
                payload,
                token,
                SecureApiClient.newIdempotencyKey(),
                preferRepresentation = true
            )
        )
        val rows = JSONArray(String(response, StandardCharsets.UTF_8))
        if (rows.length() != 1) {
            throw IllegalStateException("The server did not confirm that your proposal was saved.")
        }
        return parseProposal(rows.getJSONObject(0))
    }

    fun translateCoverLetter(text: String, sourceLanguage: String): String {
        require(text.isNotBlank()) { "Write your cover letter before translating." }
        val language = sourceLanguage.ifBlank { "auto" }
        val prompt = "Translate the following freelance job proposal cover letter into natural, professional English. " +
            "The source language code is $language (auto means detect automatically). Preserve the original meaning, names, URLs, numbers, formatting and professional tone. " +
            "Return only the English translation, with no explanation or quotation marks.\n\n" + text.trim()
        val payload = JSONObject().put("prompt", prompt).toString().toByteArray(StandardCharsets.UTF_8)
        val response = JSONObject(String(
            client.readJsonResponse(
                client.openJsonPost("/functions/v1/bright-handler", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
            ),
            StandardCharsets.UTF_8
        ))
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(response.optString("error").ifBlank { "Translation is temporarily unavailable." })
        }
        return response.optString("text").trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The translation service returned an empty result.")
    }


    fun acceptProposal(proposalId: String): RemoteContract {
        require(proposalId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val response = client.readJsonResponse(
            client.openJsonPatch("/proposals/$proposalId", JSONObject().put("status", ProposalStatus.ACCEPTED).toString().toByteArray(StandardCharsets.UTF_8), accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseContract(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun getContractForJob(jobId: String): RemoteContract? {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val payload = JSONObject().put("p_job_id", jobId).toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost(
                "/rest/v1/rpc/get_my_contract_for_job",
                payload,
                accessTokenProvider(),
                SecureApiClient.newIdempotencyKey()
            )
        )
        val body = String(response, StandardCharsets.UTF_8).trim()
        return if (body.isBlank() || body == "null") null else parseContract(JSONObject(body))
    }

    fun listMyContracts(): List<RemoteContract> {
        val root = JSONObject(String(client.readJsonResponse(client.openGet("/contracts", accessTokenProvider())), StandardCharsets.UTF_8))
        val items = root.optJSONArray("items") ?: root.optJSONArray("contracts") ?: JSONArray()
        return (0 until items.length()).map { parseContract(items.getJSONObject(it)) }
    }

    fun listFreelancerContracts(freelancerId: String): List<RemoteContract> {
        require(freelancerId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val pageSize = 500
        val path = "/rest/v1/contracts?select=id,job_id,proposal_id,client_id,freelancer_id,agreed_budget_minor,currency,status&freelancer_id=eq.$freelancerId&order=id.asc&limit=$pageSize"
        val token = accessTokenProvider()
        val contracts = mutableListOf<RemoteContract>()
        var offset = 0

        while (true) {
            val pagePath = "$path&offset=$offset"
            val page = JSONArray(
                String(client.readJsonResponse(client.openGet(pagePath, token)), StandardCharsets.UTF_8)
            )
            for (index in 0 until page.length()) {
                contracts += parseContract(page.getJSONObject(index))
            }
            if (page.length() < pageSize) break
            offset += page.length()
        }

        return contracts
    }

    fun listFreelancerReviews(freelancerId: String, limit: Int = 20): List<RemoteFreelancerReview> {
        require(freelancerId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(limit in 1..50)
        val payload = JSONObject().put("p_freelancer_id", freelancerId).toString().toByteArray(StandardCharsets.UTF_8)
        val rows = JSONArray(String(
            client.readJsonResponse(client.openJsonPost("/rest/v1/rpc/get_public_freelancer_reviews", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())),
            StandardCharsets.UTF_8
        ))
        return (0 until minOf(rows.length(), limit)).map { parseFreelancerReview(rows.getJSONObject(it)) }
    }

    fun getFreelancerReviewForContract(contractId: String): RemoteFreelancerReview? {
        require(contractId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val payload = JSONObject().put("p_contract_id", contractId).toString().toByteArray(StandardCharsets.UTF_8)
        val value = JSONObject(String(
            client.readJsonResponse(client.openJsonPost("/rest/v1/rpc/get_my_freelancer_review", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())),
            StandardCharsets.UTF_8
        ))
        return if (!value.has("id") || value.optString("id").isBlank()) null else parseFreelancerReview(value)
    }

    fun completeContract(contractId: String) {
        require(contractId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val payload = JSONObject().put("p_contract_id", contractId).toString().toByteArray(StandardCharsets.UTF_8)
        client.readJsonResponse(
            client.openJsonPost("/rest/v1/rpc/complete_contract", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
    }

    fun createFreelancerReview(contractId: String, freelancerId: String, rating: Int, reviewText: String): RemoteFreelancerReview {
        require(contractId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(freelancerId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(rating in 1..5)
        require(reviewText.length <= 2000)
        val payload = JSONObject()
            .put("contract_id", contractId)
            .put("freelancer_id", freelancerId)
            .put("rating", rating)
            .put("review_text", reviewText.trim())
            .toString().toByteArray(StandardCharsets.UTF_8)
        client.readJsonResponse(
            client.openJsonPost("/rest/v1/freelancer_reviews", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return getFreelancerReviewForContract(contractId)
            ?: throw IllegalStateException("The review was not confirmed by the server.")
    }

    fun createWorkSubmission(jobId: String, input: RemoteWorkSubmissionCreate): RemoteWorkSubmission {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.summary.length in 10..10_000)
        require(input.checkScore in 0..100)
        val payload = JSONObject()
            .put("p_job_id", jobId)
            .put("p_summary", input.summary.trim())
            .put("p_file_links", JSONArray(input.fileLinks.map { it.trim() }.filter { it.isNotBlank() }))
            .put("p_check_score", input.checkScore)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/rest/v1/rpc/create_my_work_submission", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
        )
        return parseWorkSubmission(JSONObject(String(response, StandardCharsets.UTF_8)))
    }

    fun listWorkSubmissions(jobId: String): List<RemoteWorkSubmission> {
        require(jobId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        val payload = JSONObject().put("p_job_id", jobId).toString().toByteArray(StandardCharsets.UTF_8)
        val items = JSONArray(String(
            client.readJsonResponse(client.openJsonPost("/rest/v1/rpc/list_my_work_submissions", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())),
            StandardCharsets.UTF_8
        ))
        return (0 until items.length()).map { parseWorkSubmission(items.getJSONObject(it)) }
    }

    fun analyzeWorkSubmission(submissionId: String, input: RemoteWorkAnalysisCreate): RemoteWorkAnalysis {
        require(submissionId.matches(Regex("^[0-9a-fA-F-]{36}$")))
        require(input.completedRequirements.length in 1..10_000)
        require(input.issues.length <= 10_000)
        require(input.solutions.length <= 10_000)
        require(input.results.length <= 10_000)
                val payload = JSONObject()
            .put("action", "analyze_submission")
            .put("submission_id", submissionId)
            .put("completed_requirements", input.completedRequirements.trim())
            .put("issues", input.issues.trim())
            .put("solutions", input.solutions.trim())
            .put("results", input.results.trim())
            .put("future_improvements", input.futureImprovements.trim())
            .toString().toByteArray(StandardCharsets.UTF_8)
        val response = client.readJsonResponse(
            client.openJsonPost("/functions/v1/bright-handler", payload, accessTokenProvider(), SecureApiClient.newIdempotencyKey())
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

    private fun parseFreelancerReview(x: JSONObject) = RemoteFreelancerReview(
        id = x.optString("id"),
        contractId = x.optString("contract_id"),
        clientId = x.optString("client_id"),
        freelancerId = x.optString("freelancer_id"),
        rating = x.optInt("rating", 0),
        reviewText = x.optString("review_text"),
        createdAt = x.optString("created_at")
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
        status = x.optString("status", "active")
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
    val skills: List<String> = emptyList(),
    val category: String = "Other",
    val experienceLevel: String = "Beginner",
    val durationDays: Int = 7,
    val freelancersNeeded: Int = 1
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
    val deliveryDays: Int,
    val coverLetterLanguage: String = "auto",
    val englishCoverLetter: String = "",
    val experienceLevel: String = "Beginner"
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

data class RemoteFreelancerReview(
    val id: String,
    val contractId: String,
    val clientId: String,
    val freelancerId: String,
    val rating: Int,
    val reviewText: String,
    val createdAt: String
)

data class RemoteWorkRevision(
    val id: String, val submissionId: String, val version: Int, val summary: String,
    val fileLinks: List<String>, val createdAt: String
)
