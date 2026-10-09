package com.freelancehub.app.data

/** Interfaces keep the UI independent from the eventual cloud provider. */
interface AuthRepository {
    suspend fun signIn(request: AuthRequest): Result<AuthResponse>
    suspend fun signUp(request: AuthRequest): Result<AuthResponse>
    suspend fun signOut(): Result<Unit>
}

interface JobsRepository {
    suspend fun list(query: String = ""): Result<List<Job>>
    suspend fun create(job: Job): Result<Job>
    suspend fun get(jobId: Int): Result<Job>
}

interface ProposalsRepository {
    suspend fun submit(proposal: Proposal): Result<Proposal>
    suspend fun updateStatus(jobId: Int, status: String): Result<Unit>
}

interface MessagesRepository {
    suspend fun conversations(): Result<List<String>>
}
