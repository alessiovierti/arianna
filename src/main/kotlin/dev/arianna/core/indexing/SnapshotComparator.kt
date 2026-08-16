package dev.arianna.core.indexing

import dev.arianna.core.model.EntityId
import dev.arianna.core.model.EntityChange
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.RelationChange
import dev.arianna.core.model.SnapshotChangeKind
import dev.arianna.core.model.SnapshotDiff

object SnapshotComparator {
    fun compare(
        baseRevision: String,
        overlayRevision: String,
        baseEntities: List<KnowledgeEntity>,
        overlayEntities: List<KnowledgeEntity>,
        baseRelations: List<KnowledgeRelation>,
        overlayRelations: List<KnowledgeRelation>
    ): SnapshotDiff {
        val entityChanges = compareEntities(baseEntities, overlayEntities)
        val relationChanges = compareRelations(baseRelations, overlayRelations)
        return SnapshotDiff(baseRevision, overlayRevision, entityChanges, relationChanges)
    }

    private fun compareEntities(base: List<KnowledgeEntity>, overlay: List<KnowledgeEntity>): List<EntityChange> {
        val before = base.associateBy { it.id }
        val after = overlay.associateBy { it.id }
        return (before.keys + after.keys).distinct().mapNotNull { id ->
            val old = before[id]
            val new = after[id]
            when {
                old == null && new != null -> EntityChange(SnapshotChangeKind.ADDED, id, after = new)
                old != null && new == null -> EntityChange(SnapshotChangeKind.REMOVED, id, before = old)
                old != null && new != null && semanticEntityChanged(old, new) -> EntityChange(SnapshotChangeKind.MODIFIED, id, old, new)
                else -> null
            }
        }.sortedWith(compareBy({ it.kind.name }, { it.entityId.value }))
    }

    private fun compareRelations(base: List<KnowledgeRelation>, overlay: List<KnowledgeRelation>): List<RelationChange> {
        val before = base.associateBy(::relationKey)
        val after = overlay.associateBy(::relationKey)
        return (before.keys + after.keys).distinct().mapNotNull { key ->
            val old = before[key]
            val new = after[key]
            when {
                old == null && new != null -> RelationChange(SnapshotChangeKind.ADDED, key, after = new)
                old != null && new == null -> RelationChange(SnapshotChangeKind.REMOVED, key, before = old)
                old != null && new != null && semanticRelationChanged(old, new) -> RelationChange(SnapshotChangeKind.MODIFIED, key, old, new)
                else -> null
            }
        }.sortedWith(compareBy({ it.kind.name }, { it.key }))
    }

    private fun semanticEntityChanged(before: KnowledgeEntity, after: KnowledgeEntity): Boolean =
        before.kind != after.kind || before.qualifiedName != after.qualifiedName || before.content != after.content

    private fun semanticRelationChanged(before: KnowledgeRelation, after: KnowledgeRelation): Boolean =
        before.origin != after.origin || before.confidence != after.confidence

    private fun relationKey(relation: KnowledgeRelation): String =
        "${relation.source.value}|${relation.type}|${relation.target.value}"
}
