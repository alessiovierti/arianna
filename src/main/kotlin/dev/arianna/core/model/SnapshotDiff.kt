package dev.arianna.core.model

enum class SnapshotChangeKind {
    ADDED,
    REMOVED,
    MODIFIED
}

data class EntityChange(
    val kind: SnapshotChangeKind,
    val entityId: EntityId,
    val before: KnowledgeEntity? = null,
    val after: KnowledgeEntity? = null
)

data class RelationChange(
    val kind: SnapshotChangeKind,
    val key: String,
    val before: KnowledgeRelation? = null,
    val after: KnowledgeRelation? = null
)

data class SnapshotDiff(
    val baseRevision: String,
    val overlayRevision: String,
    val entities: List<EntityChange>,
    val relations: List<RelationChange>
) {
    val changedEntityCount: Int get() = entities.size
    val changedRelationCount: Int get() = relations.size
}
