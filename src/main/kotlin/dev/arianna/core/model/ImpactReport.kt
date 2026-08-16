package dev.arianna.core.model

enum class ImpactSeverity {
    BREAKING,
    POSSIBLE,
    INFORMATIONAL
}

enum class ImpactCertainty {
    CONFIRMED,
    LIKELY,
    POSSIBLE,
    UNRESOLVED
}

data class ImpactFinding(
    val severity: ImpactSeverity,
    val certainty: ImpactCertainty,
    val category: String,
    val message: String,
    val entityId: EntityId? = null,
    val relation: KnowledgeRelation? = null,
    val evidence: Evidence? = relation?.evidence
)

data class ImpactReport(
    val baseRevision: String,
    val overlayRevision: String,
    val changedEntities: List<EntityChange>,
    val findings: List<ImpactFinding>
) {
    val breakingCount: Int get() = findings.count { it.severity == ImpactSeverity.BREAKING }
    val possibleCount: Int get() = findings.count { it.severity == ImpactSeverity.POSSIBLE }
}
