package dev.arianna.core.indexing

import dev.arianna.core.model.EntityChange
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.ImpactCertainty
import dev.arianna.core.model.ImpactFinding
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.ImpactSeverity
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.SnapshotChangeKind

object ImpactAnalyzer {
    private val callerRelations = setOf("calls", "references", "tested_by")
    private val inheritanceRelations = setOf("implements", "extends", "overrides")
    private val springRelations = setOf("injects", "qualified_by", "defines_bean", "provides_bean_type", "component_managed_by_spring", "exposes_endpoint", "handles_event", "unresolved", "ambiguous_injection")
    private val structuralRelations = setOf("defines", "contains")
    private val testKinds = setOf("test", "mock")

    fun analyze(
        diff: dev.arianna.core.model.SnapshotDiff,
        baseEntities: List<KnowledgeEntity>,
        overlayEntities: List<KnowledgeEntity>,
        baseRelations: List<KnowledgeRelation>,
        overlayRelations: List<KnowledgeRelation>
    ): ImpactReport {
        val changedIds = diff.entities.map { it.entityId }.toSet()
        val entityById = (baseEntities + overlayEntities).associateBy { it.id }
        val findings = mutableListOf<ImpactFinding>()

        diff.entities.forEach { change ->
            val entity = change.after ?: change.before
            when (change.kind) {
                SnapshotChangeKind.REMOVED -> findings += ImpactFinding(
                    ImpactSeverity.BREAKING,
                    ImpactCertainty.CONFIRMED,
                    "removed_entity",
                    "Removed entity: ${entity?.qualifiedName ?: change.entityId.value}",
                    change.entityId,
                    evidence = entity?.evidence
                )
                SnapshotChangeKind.MODIFIED -> findings += ImpactFinding(
                    if (entity?.kind in setOf("method", "interface", "endpoint", "event")) ImpactSeverity.POSSIBLE else ImpactSeverity.INFORMATIONAL,
                    ImpactCertainty.CONFIRMED,
                    "changed_entity",
                    "Changed entity: ${entity?.qualifiedName ?: change.entityId.value}",
                    change.entityId,
                    evidence = entity?.evidence
                )
                SnapshotChangeKind.ADDED -> findings += ImpactFinding(
                    ImpactSeverity.INFORMATIONAL,
                    ImpactCertainty.CONFIRMED,
                    "added_entity",
                    "Added entity: ${entity?.qualifiedName ?: change.entityId.value}",
                    change.entityId,
                    evidence = entity?.evidence
                )
            }
        }

        baseRelations.filter { it.type !in structuralRelations && (it.target in changedIds || it.source in changedIds) }.forEach { relation ->
            val targetChange = diff.entities.firstOrNull { it.entityId == relation.target }
            val sourceEntity = entityById[relation.source]
            val category = when {
                relation.type == "tested_by" -> "tests_and_mocks"
                relation.type in callerRelations -> if (sourceEntity?.kind in testKinds || relation.evidence?.file?.contains("/test") == true) "tests_and_mocks" else "direct_callers"
                relation.type in inheritanceRelations -> "implementations_and_overrides"
                relation.type in springRelations -> "spring_wiring"
                relation.type == "documented_by" -> "documents"
                else -> "related_usage"
            }
            val severity = when {
                targetChange?.kind == SnapshotChangeKind.REMOVED && relation.type in callerRelations -> ImpactSeverity.BREAKING
                relation.origin.name == "INFERRED" -> ImpactSeverity.POSSIBLE
                else -> ImpactSeverity.POSSIBLE
            }
            val certainty = when {
                relation.origin.name == "STATIC" && relation.confidence.name == "HIGH" -> ImpactCertainty.CONFIRMED
                relation.origin.name == "FRAMEWORK" -> ImpactCertainty.LIKELY
                relation.origin.name == "INFERRED" -> ImpactCertainty.POSSIBLE
                else -> ImpactCertainty.UNRESOLVED
            }
            findings += ImpactFinding(
                severity,
                certainty,
                category,
                "${relation.source.value} is connected to ${relation.target.value} through ${relation.type}",
                relation.source,
                relation
            )
        }

        val baseRelationKeys = baseRelations.map(::relationKey).toSet()
        overlayRelations.filter { it.type !in structuralRelations && (it.target in changedIds || it.source in changedIds) }
            .filter { relationKey(it) !in baseRelationKeys }
            .forEach { relation ->
                findings += ImpactFinding(
                    ImpactSeverity.INFORMATIONAL,
                    if (relation.origin.name == "STATIC") ImpactCertainty.CONFIRMED else ImpactCertainty.LIKELY,
                    "new_relation",
                    "New relation in the overlay: ${relation.source.value} --${relation.type}--> ${relation.target.value}",
                    relation.source,
                    relation
                )
            }

        val changedFiles = diff.entities.mapNotNull { change ->
            (change.after ?: change.before)?.evidence?.file?.takeIf { it.isNotBlank() }
        }.toSet()
        val affectedModules = baseRelations
            .filter { it.type == "contains" && it.target.value.startsWith("file:") }
            .filter { it.target.value.removePrefix("file:") in changedFiles }
            .map { it.source }
            .toSet()
        baseRelations.filter { it.type == "depends_on" && it.target in affectedModules }.forEach { relation ->
            findings += ImpactFinding(
                ImpactSeverity.POSSIBLE,
                ImpactCertainty.LIKELY,
                "module_consumers",
                "Module ${relation.source.value} depends on changed module ${relation.target.value}",
                relation.source,
                relation
            )
        }

        return ImpactReport(diff.baseRevision, diff.overlayRevision, diff.entities, findings.distinctBy { Triple(it.category, it.entityId, it.relation?.source) })
    }

    private fun relationKey(relation: KnowledgeRelation): String =
        "${relation.source.value}|${relation.type}|${relation.target.value}"
}
