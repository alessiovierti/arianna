package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityChange
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.ImpactCertainty
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.SnapshotChangeKind
import dev.arianna.core.model.SnapshotDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeVerifierTest {
    @Test
    fun `flags a removed symbol still referenced by the overlay`() {
        val evidence = Evidence("repo", "HEAD", "src/Caller.kt", 8, 8, "test")
        val removed = KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", evidence)
        val caller = KnowledgeEntity(EntityId("method:Caller.run"), "method", "Caller.run", evidence)
        val residual = KnowledgeRelation(caller.id, "calls", removed.id, Origin.STATIC, Confidence.HIGH, evidence)
        val diff = SnapshotDiff("base", "head", listOf(EntityChange(SnapshotChangeKind.REMOVED, removed.id, before = removed)), emptyList())
        val impact = ImpactReport("base", "head", diff.entities, emptyList())

        val report = ChangeVerifier.verify(diff, impact, listOf(removed, caller), listOf(caller), emptyList(), listOf(residual))

        assertEquals(1, report.confirmedIssueCount)
        assertEquals("residual_reference", report.issues.single().category)
        assertEquals(ImpactCertainty.CONFIRMED, report.issues.single().certainty)
        assertTrue(report.externalVerificationRequired)
    }

    @Test
    fun `flags a removed definition represented as external placeholder`() {
        val evidence = Evidence("repo", "HEAD", "src/Caller.kt", 8, 8, "test")
        val removed = KnowledgeEntity(EntityId("scip:Payment.process"), "method", "Payment.process", evidence)
        val placeholder = removed.copy(kind = "external_symbol", evidence = evidence.copy(revision = "overlay"))
        val caller = KnowledgeEntity(EntityId("scip:Caller.run"), "method", "Caller.run", evidence)
        val residual = KnowledgeRelation(caller.id, "references", removed.id, Origin.STATIC, Confidence.HIGH, evidence)
        val diff = SnapshotDiff(
            "base",
            "overlay",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, removed.id, before = removed, after = placeholder)),
            emptyList()
        )
        val impact = ImpactReport("base", "overlay", diff.entities, emptyList())

        val report = ChangeVerifier.verify(diff, impact, listOf(removed, caller), listOf(placeholder, caller), emptyList(), listOf(residual))

        assertEquals("residual_reference", report.issues.single().category)
        assertEquals(ImpactCertainty.CONFIRMED, report.issues.single().certainty)
    }
}
