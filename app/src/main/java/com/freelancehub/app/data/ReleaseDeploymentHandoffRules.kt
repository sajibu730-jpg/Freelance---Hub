package com.freelancehub.app.data

/** Pure rules for deciding whether a release may be described as externally executed. */
object ReleaseDeploymentHandoffRules {
    fun mayClaimExternalExecution(
        productionExecutionEvidenced: Boolean,
        independentEvidencePresent: Boolean
    ): Boolean = productionExecutionEvidenced && independentEvidencePresent

    fun requiresExternalExecutionDisclosure(
        productionExecutionEvidenced: Boolean,
        independentEvidencePresent: Boolean
    ): Boolean = !mayClaimExternalExecution(productionExecutionEvidenced, independentEvidencePresent)
}
