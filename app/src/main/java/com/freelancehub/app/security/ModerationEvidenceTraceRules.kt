package com.freelancehub.app.security

/** Guards the link between protection evidence and the moderation event that consumed it. */
object ModerationEvidenceTraceRules {
    const val MAX_EVIDENCE_ID_LENGTH = 128

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun validEvidenceId(id: String): Boolean =
        id.trim().length in 8..MAX_EVIDENCE_ID_LENGTH && ID_PATTERN.matches(id.trim())

    fun evidenceSetValid(ids: List<String>): Boolean =
        ids.isNotEmpty() && ids.size <= ProtectionAppealRules.MAX_EVIDENCE_ITEMS &&
            ids.distinct().size == ids.size && ids.all(::validEvidenceId)

    fun immutableEvidenceLink(existing: Set<String>, incoming: List<String>): Boolean =
        evidenceSetValid(incoming) && existing.containsAll(incoming) ||
            (existing.isEmpty() && evidenceSetValid(incoming))
}
