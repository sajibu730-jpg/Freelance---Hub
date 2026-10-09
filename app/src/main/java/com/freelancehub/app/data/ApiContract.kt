package com.freelancehub.app.data

/** Provider-neutral contract; the Android client must never contain admin/service secrets. */
object ApiContract {
    const val API_VERSION = "v1"
    const val LOGIN = "/$API_VERSION/auth/login"
    const val SIGNUP = "/$API_VERSION/auth/signup"
    const val REFRESH = "/$API_VERSION/auth/refresh"
    const val LOGOUT = "/$API_VERSION/auth/logout"
    const val JOBS = "/$API_VERSION/jobs"
    const val JOB_BY_ID = "/$API_VERSION/jobs/{jobId}"
    const val PROPOSALS = "/$API_VERSION/proposals"
    const val PROPOSAL_STATUS = "/$API_VERSION/proposals/{jobId}/status"
    const val CONTRACTS = "/$API_VERSION/contracts"
    const val CONTRACT_BY_ID = "/$API_VERSION/contracts/{contractId}"
    const val CONTRACT_BY_JOB = "/$API_VERSION/jobs/{jobId}/contract"
    const val MESSAGES = "/$API_VERSION/messages"
    const val PROFILE = "/$API_VERSION/profile"
    const val NOTIFICATIONS = "/$API_VERSION/notifications"
    const val PAYMENTS = "/$API_VERSION/payments"
    const val WALLET = "/$API_VERSION/wallet"
    const val LEDGER = "/$API_VERSION/wallet/ledger"
    const val PAYOUTS = "/$API_VERSION/payouts"
    const val PAYOUT_BY_ID = "/$API_VERSION/payouts/{payoutId}"
    const val ESCROW = "/$API_VERSION/escrow"
    const val ESCROW_BY_JOB = "/$API_VERSION/escrow/{jobId}"
    const val PAYMENT_WEBHOOKS = "/$API_VERSION/payment-webhooks"
    const val DISPUTES = "/$API_VERSION/disputes"
    const val DISPUTE_BY_ID = "/$API_VERSION/disputes/{disputeId}"
    const val IDEMPOTENCY = "/$API_VERSION/idempotency"
    const val FINANCIAL_CONTROLS = "/$API_VERSION/financial-controls"
    const val FINANCIAL_CONTROL_FINDINGS = "/$API_VERSION/financial-controls/{runId}/findings"
    const val WEBHOOK_SECURITY_EVENTS = "/$API_VERSION/payment-webhooks/security-events"
    const val AUDIT_EVENTS = "/$API_VERSION/audit-events"
    const val OUTBOX = "/$API_VERSION/ops/outbox"
    const val INCIDENTS = "/$API_VERSION/ops/incidents"
    const val RETENTION = "/$API_VERSION/ops/retention"
    const val FEATURE_CONTROLS = "/$API_VERSION/ops/feature-controls"
    const val BACKUP_RUNS = "/$API_VERSION/ops/backups"
    const val RESTORE_DRILLS = "/$API_VERSION/ops/restore-drills"
    const val SERVICE_HEALTH = "/$API_VERSION/ops/health"
    const val DEPLOYMENTS = "/$API_VERSION/ops/deployments"
    const val RELEASE_READINESS = "/$API_VERSION/ops/release-readiness"
    const val ARTIFACT_PROVENANCE = "/$API_VERSION/ops/artifact-provenance"
    const val CHANGE_APPROVALS = "/$API_VERSION/ops/change-approvals"
    const val SLO_OBSERVATIONS = "/$API_VERSION/ops/slo-observations"
    const val LAUNCH_READINESS = "/$API_VERSION/ops/launch-readiness"
    const val RELEASE_SIGNOFFS = "/$API_VERSION/ops/release-signoffs"
    const val ENVIRONMENT_BINDINGS = "/$API_VERSION/ops/environment-bindings"
    const val ROLLOUT_PLANS = "/$API_VERSION/ops/rollout-plans"
    const val PROTECTION_REPORTS = "/$API_VERSION/protection-reports"
    const val PROTECTION_APPEALS = "/$API_VERSION/protection-appeals"
    const val PROTECTION_ENFORCEMENT = "/$API_VERSION/protection-enforcement/{enforcementId}"
    const val ACCOUNT_IDENTITY_STATUS = "/$API_VERSION/account/identity-status"
    const val PROTECTION_APPEAL_STATUS = "/$API_VERSION/protection-appeals/{appealId}"
}

data class AuthRequest(val email:String,val password:String,val role:String="FREELANCER",val name:String?=null)
data class AuthResponse(
    val accessToken: String,
    val userId: String,
    val role: String,
    val expiresAtEpochSeconds: Long? = null,
    val refreshToken: String? = null
)
