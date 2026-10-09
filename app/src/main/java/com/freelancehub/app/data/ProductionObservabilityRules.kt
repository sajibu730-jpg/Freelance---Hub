package com.freelancehub.app.data

object ProductionObservabilityRules {
    enum class Severity { INFO, WARNING, CRITICAL }
    enum class Health { HEALTHY, DEGRADED, UNAVAILABLE, RECOVERING }

    fun validMetricName(name: String) = name.matches(Regex("[a-z][a-z0-9_.-]{2,63}"))
    fun validHealth(health: Health) = health in Health.entries
    fun validSeverity(severity: Severity) = severity in Severity.entries
    fun validSample(value: Double) = value.isFinite() && value >= 0.0
    fun validAlertThreshold(threshold: Double) = threshold.isFinite() && threshold >= 0.0
}
