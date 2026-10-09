package com.freelancehub.app.data

object PrivacyOperationalAssuranceRules {
    private val evidenceTypes = setOf("IDENTITY_VERIFIED", "SCOPE_REVIEWED", "EXPORT_GENERATED", "DELETION_VERIFIED", "RECTIFICATION_VERIFIED", "ACCESS_FULFILLED")
    private val sensitivityLevels = setOf("LOW", "MODERATE", "HIGH", "RESTRICTED")
    private val deletionStates = setOf("PENDING", "VERIFIED", "FAILED")
    fun isEvidenceTypeValid(value: String) = value in evidenceTypes
    fun isSensitivityLevelValid(value: String) = value in sensitivityLevels
    fun isItemCountValid(value: Int) = value >= 0
    fun isDeletionVerificationStateValid(value: String) = value in deletionStates
    fun isScopeValid(value: String) = value.isNotBlank()
}
