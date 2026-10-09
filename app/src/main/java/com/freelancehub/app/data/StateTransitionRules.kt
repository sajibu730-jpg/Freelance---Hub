package com.freelancehub.app.data

/** Pure allow-list for marketplace lifecycle transitions. */
object StateTransitionRules {
    fun jobAllowed(from: String, to: String): Boolean = when (from) {
        JobStatus.OPEN -> to == JobStatus.PAUSED || to == JobStatus.CLOSED
        JobStatus.PAUSED -> to == JobStatus.OPEN || to == JobStatus.CLOSED
        JobStatus.CLOSED -> to == JobStatus.COMPLETED
        JobStatus.COMPLETED -> false
        else -> false
    }

    fun proposalAllowed(from: String, to: String): Boolean = when (from) {
        ProposalStatus.SUBMITTED -> to == ProposalStatus.SHORTLISTED || to == ProposalStatus.ACCEPTED || to == ProposalStatus.REJECTED
        ProposalStatus.SHORTLISTED -> to == ProposalStatus.ACCEPTED || to == ProposalStatus.REJECTED
        ProposalStatus.ACCEPTED, ProposalStatus.REJECTED -> false
        else -> false
    }

    fun workAllowed(from: String, to: String): Boolean = when (from) {
        WorkSubmissionStatus.SUBMITTED -> to == WorkSubmissionStatus.CHANGES_REQUESTED || to == WorkSubmissionStatus.APPROVED
        WorkSubmissionStatus.CHANGES_REQUESTED -> to == WorkSubmissionStatus.SUBMITTED || to == WorkSubmissionStatus.APPROVED
        WorkSubmissionStatus.APPROVED -> false
        else -> false
    }
}
