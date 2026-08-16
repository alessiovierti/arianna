package dev.arianna.core.model

data class RefactoringStep(
    val order: Int,
    val category: String,
    val title: String,
    val actions: List<String>,
    val entityIds: List<EntityId>,
    val findingCount: Int,
    val evidence: List<Evidence> = emptyList()
)

data class RefactoringPlan(
    val baseRevision: String,
    val overlayRevision: String,
    val steps: List<RefactoringStep>,
    val externalVerificationRequired: Boolean = true,
    val note: String = "The plan does not replace compilation and external tests."
)

data class VerificationIssue(
    val severity: ImpactSeverity,
    val certainty: ImpactCertainty,
    val category: String,
    val message: String,
    val entityId: EntityId? = null,
    val evidence: Evidence? = null
)

data class VerificationReport(
    val baseRevision: String,
    val overlayRevision: String,
    val issues: List<VerificationIssue>,
    val externalVerificationRequired: Boolean = true,
    val note: String = "Inconclusive result without compilation and external tests."
) {
    val confirmedIssueCount: Int get() = issues.count { it.certainty == ImpactCertainty.CONFIRMED }
}
