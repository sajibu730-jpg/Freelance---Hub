package com.freelancehub.app.data

/** Server contract primitives. These are intentionally free of provider-specific SDKs. */
data class ApiError(val code: String, val message: String, val requestId: String? = null)
data class ApiEnvelope<T>(val data: T? = null, val error: ApiError? = null)
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

data class CreateJobRequest(
    val title: String,
    val description: String,
    val budgetMinor: Long,
    val currency: String,
    val skills: List<String>
)

data class SubmitProposalRequest(
    val jobId: String,
    val coverLetter: String,
    val bidMinor: Long,
    val currency: String,
    val deliveryDays: Int
)

object SecurityHeaders {
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val REQUEST_ID = "X-Request-ID"
}
