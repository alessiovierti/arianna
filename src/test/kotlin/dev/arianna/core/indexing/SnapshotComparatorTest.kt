package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.SnapshotChangeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotComparatorTest {
    @Test
    fun `detects added removed modified entities and relations without treating evidence revision as change`() {
        val baseEvidence = Evidence("repo", "base", "src/Payment.kt", 1, 1, "scip")
        val overlayEvidence = baseEvidence.copy(revision = "WORKING_TREE:base", startLine = 2, endLine = 2)
        val baseEntities = listOf(
            KnowledgeEntity(EntityId("class:Payment"), "class", "Payment", baseEvidence),
            KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", baseEvidence)
        )
        val overlayEntities = listOf(
            KnowledgeEntity(EntityId("class:Payment"), "class", "Payment", overlayEvidence),
            KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", overlayEvidence, "changed signature"),
            KnowledgeEntity(EntityId("class:Receipt"), "class", "Receipt", overlayEvidence)
        )
        val baseRelations = listOf(
            KnowledgeRelation(EntityId("class:Payment"), "defines", EntityId("method:Payment.process"), Origin.STATIC, Confidence.HIGH, baseEvidence)
        )
        val overlayRelations = listOf(
            KnowledgeRelation(EntityId("class:Payment"), "defines", EntityId("method:Payment.process"), Origin.STATIC, Confidence.MEDIUM, overlayEvidence)
        )

        val diff = SnapshotComparator.compare("base", "WORKING_TREE:base", baseEntities, overlayEntities, baseRelations, overlayRelations)

        assertEquals(setOf(SnapshotChangeKind.MODIFIED, SnapshotChangeKind.ADDED), diff.entities.map { it.kind }.toSet())
        assertEquals(SnapshotChangeKind.MODIFIED, diff.relations.single().kind)
        assertEquals(setOf("method:Payment.process", "class:Receipt"), diff.entities.map { it.entityId.value }.toSet())
    }
}
