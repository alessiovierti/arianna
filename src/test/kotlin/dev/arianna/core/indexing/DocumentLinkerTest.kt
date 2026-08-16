package dev.arianna.core.indexing

import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentLinkerTest {
    @Test
    fun `links textual mentions with low inferred confidence and line evidence`() {
        val document = KnowledgeEntity(
            EntityId("document:README.md"),
            "document",
            "README.md",
            Evidence("repo", "HEAD", "README.md", analyzerVersion = "document-indexer-0.1"),
            "# Payments\nPaymentService handles the payment flow.\n"
        )
        val service = KnowledgeEntity(
            EntityId("class:PaymentService"),
            "class",
            "PaymentService",
            Evidence("repo", "HEAD", "src/PaymentService.kt", 1, 1, "scip")
        )

        val relations = DocumentLinker.link(listOf(document), listOf(document, service))

        assertEquals(1, relations.size)
        assertEquals("class:PaymentService", relations.single().source.value)
        assertEquals("documented_by", relations.single().type)
        assertEquals(Origin.INFERRED, relations.single().origin)
        assertEquals("document-linker-0.1", relations.single().evidence?.analyzerVersion)
        assertEquals(2, relations.single().evidence?.startLine)
        assertTrue(relations.single().confidence.name == "LOW")
    }

    @Test
    fun `does not create a structural certainty from an unrelated word`() {
        val document = KnowledgeEntity(
            EntityId("document:README.md"), "document", "README.md",
            Evidence("repo", "HEAD", "README.md", analyzerVersion = "document-indexer-0.1"),
            "The payment service is described in prose."
        )
        val entity = KnowledgeEntity(
            EntityId("class:PaymentService"), "class", "PaymentService",
            Evidence("repo", "HEAD", "src/PaymentService.kt", analyzerVersion = "scip")
        )

        assertTrue(DocumentLinker.link(listOf(document), listOf(document, entity)).isEmpty())
    }

    @Test
    fun `matches qualified mentions without scanning every candidate for every line`() {
        val document = KnowledgeEntity(
            EntityId("document:README.md"), "document", "README.md",
            Evidence("repo", "HEAD", "README.md", analyzerVersion = "document-indexer-0.1"),
            "See com.example.PaymentService#process for the request flow."
        )
        val entities = (1..5_000).map { index ->
            KnowledgeEntity(
                EntityId("class:Service$index"), "class", "com.example.Service$index",
                Evidence("repo", "HEAD", "src/Service$index.kt", analyzerVersion = "scip")
            )
        } + KnowledgeEntity(
            EntityId("class:PaymentService"), "class", "com.example.PaymentService",
            Evidence("repo", "HEAD", "src/PaymentService.kt", analyzerVersion = "scip")
        )

        val relations = DocumentLinker.link(listOf(document), entities)

        assertEquals("class:PaymentService", relations.single().source.value)
    }
}
