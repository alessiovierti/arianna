package dev.arianna.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class KnowledgeModelTest {
    @Test
    fun `relation retains provenance and evidence`() {
        val evidence = Evidence(
            repository = "payments",
            revision = "abc123",
            file = "src/PaymentService.kt",
            startLine = 12,
            endLine = 12,
            analyzerVersion = "test"
        )

        val relation = KnowledgeRelation(
            source = EntityId("method:PaymentController.process"),
            type = "calls",
            target = EntityId("method:PaymentService.process"),
            origin = Origin.STATIC,
            confidence = Confidence.HIGH,
            evidence = evidence
        )

        assertEquals(Origin.STATIC, relation.origin)
        assertEquals(Confidence.HIGH, relation.confidence)
        assertEquals("src/PaymentService.kt", relation.evidence?.file)
    }
}
