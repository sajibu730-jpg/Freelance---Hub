package com.freelancehub.app.data

/** Pure, provider-neutral checks used before enabling a production release. */
object LaunchReadinessRules {
    private val hashPattern = Regex("^[A-Fa-f0-9]{64}$")
    private val environmentPattern = Regex("^(staging|production)$")
    private val releasePattern = Regex("^\\d+\\.\\d+\\.\\d+$")

    fun isEnvironmentValid(value: String): Boolean = environmentPattern.matches(value.trim())
    fun isReleaseVersionValid(value: String): Boolean = releasePattern.matches(value.trim())
    fun isSha256Valid(value: String): Boolean = hashPattern.matches(value.trim())
    fun isSignoffReasonValid(value: String): Boolean = value.trim().length in 8..1000
    fun isRolloutPercentValid(value: Int): Boolean = value in 0..100
    fun isCheckStatusValid(value: String): Boolean = value.trim() in setOf("PASS", "FAIL", "PENDING", "WAIVED")
}
