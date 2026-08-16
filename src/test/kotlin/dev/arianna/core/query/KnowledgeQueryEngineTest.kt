package dev.arianna.core.query

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.storage.SQLiteKnowledgeStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeQueryEngineTest {
    @Test
    fun `finds references and implementations through matching target symbols`() {
        val database = createTempDirectory("arianna-query-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val evidence = Evidence("repo", "abc", "src/Main.kt", 1, 1, "test")
            store.replaceSnapshot(
                "repo",
                "abc",
                sequenceOf(
                    KnowledgeEntity(EntityId("interface:Payment"), "interface", "Payment", evidence),
                    KnowledgeEntity(EntityId("class:PaymentImpl"), "class", "PaymentImpl", evidence),
                    KnowledgeEntity(EntityId("method:Caller.run"), "method", "Caller.run", evidence),
                    KnowledgeEntity(EntityId("method:Caller.first"), "method", "Caller.first", evidence),
                    KnowledgeEntity(EntityId("method:Caller.second"), "method", "Caller.second", evidence),
                    KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", evidence),
                    KnowledgeEntity(EntityId("method:PaymentImpl.process"), "method", "PaymentImpl.process", evidence)
                ),
                sequenceOf(
                    KnowledgeRelation(EntityId("class:PaymentImpl"), "implements", EntityId("interface:Payment"), Origin.STATIC, Confidence.HIGH, evidence),
                    KnowledgeRelation(EntityId("method:Caller.run"), "calls", EntityId("method:Payment.process"), Origin.STATIC, Confidence.HIGH, evidence),
                    KnowledgeRelation(EntityId("method:Caller.first"), "calls", EntityId("method:Payment.process"), Origin.STATIC, Confidence.HIGH, evidence),
                    KnowledgeRelation(EntityId("method:Caller.second"), "calls", EntityId("method:Payment.process"), Origin.STATIC, Confidence.HIGH, evidence),
                    KnowledgeRelation(EntityId("method:PaymentImpl.process"), "overrides", EntityId("method:Payment.process"), Origin.STATIC, Confidence.MEDIUM, evidence)
                )
            )

            val engine = KnowledgeQueryEngine(store)
            val symbolPage = engine.findSymbolsPage("Payment.process", limit = 10, revision = "abc")
            assertEquals("method:Payment.process", symbolPage.items.first().id.value)
            assertTrue(symbolPage.items.none { it.kind == "file" || it.kind == "document" })
            assertTrue(engine.findImplementations("Payment").any { it.source.value == "class:PaymentImpl" })
            assertEquals("method:PaymentImpl.process", engine.findImplementations("Payment.process").single().source.value)
            assertTrue(engine.findReferences("Payment.process").any { it.source.value == "method:Caller.run" })
            val firstPage = engine.findReferencesPage("Payment.process", offset = 0, limit = 2)
            val secondPage = engine.findReferencesPage("Payment.process", offset = 2, limit = 2)
            assertEquals(3, firstPage.total)
            assertEquals(2, firstPage.items.size)
            assertEquals(1, secondPage.items.size)
            assertEquals("method:Caller.second", secondPage.items.single().source.value)
            assertEquals(3, engine.findReferencesPage("Payment.process", limit = 10, revision = "abc", confidence = "high").total)
            assertEquals(0, engine.findReferencesPage("Payment.process", limit = 10, revision = "abc", confidence = "low").total)
            assertEquals(1, engine.findImplementationsPage("Payment.process", limit = 10, revision = "abc", confidence = "medium").total)
            assertEquals(1, engine.findRelationshipsPage("method:Caller.run", limit = 10, revision = "abc", confidence = "high").total)
            assertEquals(0, engine.findRelationshipsPage("method:Caller.run", limit = 10, revision = "abc", confidence = "low").total)
        }
    }

    @Test
    fun `searches document content and returns a document by path`() {
        val database = createTempDirectory("arianna-query-docs-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            store.replaceSnapshot(
                "repo",
                "abc",
                sequenceOf(
                    KnowledgeEntity(
                        EntityId("document:README.md"),
                        "document",
                        "README.md",
                        Evidence("repo", "abc", "README.md", 1, 2, "document-indexer-0.1"),
                        "Payment flow documentation"
                    )
                ),
                emptySequence()
            )

            val engine = KnowledgeQueryEngine(store)
            assertEquals("README.md", engine.searchKnowledge("payment flow").items.single().qualifiedName)
            assertEquals("README.md", engine.searchKnowledge("payment", kind = "document", file = "README").items.single().qualifiedName)
            assertNotNull(engine.getDocument("README.md")).also { document ->
                assertEquals("Payment flow documentation", document.content)
            }
        }
    }
}
