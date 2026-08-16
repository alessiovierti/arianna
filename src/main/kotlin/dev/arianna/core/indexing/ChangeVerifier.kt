package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.ImpactCertainty
import dev.arianna.core.model.ImpactFinding
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.ImpactSeverity
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.SnapshotChangeKind
import dev.arianna.core.model.VerificationIssue
import dev.arianna.core.model.VerificationReport

object ChangeVerifier {
    fun verify(
        diff: dev.arianna.core.model.SnapshotDiff,
        impact: ImpactReport,
        baseEntities: List<KnowledgeEntity>,
        overlayEntities: List<KnowledgeEntity>,
        baseRelations: List<KnowledgeRelation>,
        overlayRelations: List<KnowledgeRelation>
    ): VerificationReport {
        val issues = mutableListOf<VerificationIssue>()
        val removedDefinitions = diff.entities
            .filter { change ->
                change.kind == SnapshotChangeKind.REMOVED ||
                    (change.kind == SnapshotChangeKind.MODIFIED &&
                        change.before?.kind != "external_symbol" &&
                        change.after?.kind == "external_symbol")
            }
            .map { it.entityId }
            .toSet()

        overlayRelations.filter { it.source in removedDefinitions || it.target in removedDefinitions }.forEach { relation ->
            issues += VerificationIssue(
                ImpactSeverity.BREAKING,
                ImpactCertainty.CONFIRMED,
                "residual_reference",
                "Relation ${relation.type} still uses a removed or undefined entity: ${relation.source.value} -> ${relation.target.value}",
                relation.source,
                relation.evidence
            )
        }

        val overlayRelationKeys = overlayRelations.map { relationKey(it) }.toSet()
        baseRelations.filter { it.type in setOf("implements", "extends", "overrides") && it.target in diff.entities.map { change -> change.entityId } }
            .filter { relationKey(it) !in overlayRelationKeys }
            .forEach { relation ->
                issues += VerificationIssue(
                    ImpactSeverity.POSSIBLE,
                    ImpactCertainty.LIKELY,
                    "missing_implementation",
                    "Implementation/override relation missing from the overlay: ${relation.source.value} --${relation.type}--> ${relation.target.value}",
                    relation.source,
                    relation.evidence
                )
            }

        impact.findings.filter { it.certainty == ImpactCertainty.UNRESOLVED }.forEach { finding ->
            issues += VerificationIssue(
                ImpactSeverity.POSSIBLE,
                ImpactCertainty.UNRESOLVED,
                "unresolved_reference",
                finding.message,
                finding.entityId,
                finding.evidence
            )
        }

        return VerificationReport(diff.baseRevision, diff.overlayRevision, issues.distinctBy { Triple(it.category, it.entityId, it.message) })
    }

    private fun relationKey(relation: KnowledgeRelation): String =
        "${relation.source.value}|${relation.type}|${relation.target.value}"
}
