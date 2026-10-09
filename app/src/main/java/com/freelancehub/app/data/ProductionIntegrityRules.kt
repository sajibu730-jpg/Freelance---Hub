package com.freelancehub.app.data

/** v48.401-v48.900 integrity contracts. Server-side enforcement remains authoritative. */
object ProductionIntegrityRules {
    enum class JobState { OPEN, HIRED, IN_PROGRESS, SUBMITTED, COMPLETED, CANCELLED }
    enum class ProposalState { SUBMITTED, WITHDRAWN, ACCEPTED, REJECTED }
    enum class PaymentState { PENDING, AUTHORIZED, CAPTURED, REFUND_PENDING, REFUNDED, FAILED }

    fun jobSkillMatchesVerifiedSkill(jobSkill: String, verifiedSkills: Set<String>): Boolean =
        MarketplaceIntegrityRules.jobMatchesFreelancerSkill(jobSkill, verifiedSkills)

    fun normalHiringWindowHours(): Int = MarketplaceIntegrityRules.NORMAL_HIRING_WINDOW_HOURS
    fun emergencyPhaseCount(): Int = 2
    fun emergencyPhaseHours(): Int = MarketplaceIntegrityRules.EMERGENCY_PHASE_HOURS
    fun emergencyFreelancersPerPhase(): Int = MarketplaceIntegrityRules.EMERGENCY_FREELANCERS_PER_PHASE

    fun canTransitionJob(from: JobState, to: JobState): Boolean = when (from to to) {
        JobState.OPEN to JobState.HIRED,
        JobState.OPEN to JobState.CANCELLED,
        JobState.HIRED to JobState.IN_PROGRESS,
        JobState.HIRED to JobState.CANCELLED,
        JobState.IN_PROGRESS to JobState.SUBMITTED,
        JobState.IN_PROGRESS to JobState.CANCELLED,
        JobState.SUBMITTED to JobState.COMPLETED,
        JobState.SUBMITTED to JobState.IN_PROGRESS -> true
        else -> false
    }

    fun canTransitionProposal(from: ProposalState, to: ProposalState): Boolean = when (from to to) {
        ProposalState.SUBMITTED to ProposalState.WITHDRAWN,
        ProposalState.SUBMITTED to ProposalState.ACCEPTED,
        ProposalState.SUBMITTED to ProposalState.REJECTED -> true
        else -> false
    }

    fun canTransitionPayment(from: PaymentState, to: PaymentState): Boolean = when (from to to) {
        PaymentState.PENDING to PaymentState.AUTHORIZED,
        PaymentState.PENDING to PaymentState.FAILED,
        PaymentState.AUTHORIZED to PaymentState.CAPTURED,
        PaymentState.AUTHORIZED to PaymentState.REFUND_PENDING,
        PaymentState.CAPTURED to PaymentState.REFUND_PENDING,
        PaymentState.REFUND_PENDING to PaymentState.REFUNDED -> true
        else -> false
    }

    fun webhookMayApplyDuplicateEvent(alreadyApplied: Boolean): Boolean = !alreadyApplied
    fun destructiveOfflineRecoveryAllowed(): Boolean = false
    fun aiMayApproveWorkOutcome(): Boolean = false
    fun aiMayApprovePaymentOrRefund(): Boolean = false
    fun clientUiMayBypassServerAuthorization(): Boolean = false
    fun antiBypassMonitoringMustBeDisclosed(): Boolean = true
}
