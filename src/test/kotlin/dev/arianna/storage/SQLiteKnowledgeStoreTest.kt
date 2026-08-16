package dev.arianna.storage

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.SnapshotKind
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.sql.DriverManager
import dev.arianna.core.error.StorageException

class SQLiteKnowledgeStoreTest {
    @Test
    fun `publishes a snapshot and reads entities and relations`() {
        val database = createTempDirectory("arianna-db-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val evidence = Evidence("repo", "abc", "src/Main.kt", 4, 4, "test")
            store.replaceSnapshot(
                repository = "repo",
                revision = "abc",
                entities = sequenceOf(
                    KnowledgeEntity(EntityId("class:Main"), "class", "Main", evidence),
                    KnowledgeEntity(EntityId("method:Main.run"), "method", "Main.run", evidence, "fun run()")
                ),
                relations = sequenceOf(
                    KnowledgeRelation(
                        EntityId("class:Main"), "defines", EntityId("method:Main.run"),
                        Origin.STATIC, Confidence.HIGH, evidence
                    )
                )
            )

            assertEquals(2, store.findEntities("Main").size)
            assertEquals("fun run()", store.findEntities("fun run").single().content)
            assertEquals(1, store.findRelations("class:Main").size)
            assertTrue(database.toFile().exists())
        }
    }

    @Test
    fun `matches every query token across searchable entity fields`() {
        val database = createTempDirectory("arianna-token-search-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val evidence = Evidence("repo", "HEAD", "src/Payment.kt", analyzerVersion = "test")
            store.replaceSnapshot(
                "repo",
                "HEAD",
                sequenceOf(
                    KnowledgeEntity(EntityId("method:PaymentService.process"), "method", "PaymentService.process", evidence, "process payment"),
                    KnowledgeEntity(EntityId("class:PaymentController"), "class", "PaymentController", evidence, "payment endpoint")
                ),
                emptySequence()
            )

            assertEquals(listOf("method:PaymentService.process"), store.findEntities("payment PROCESS").map { it.id.value })
            assertEquals(listOf("class:PaymentController"), store.findEntities("PAYMENT endpoint").map { it.id.value })
        }
    }

    @Test
    fun `orders relation pages deterministically`() {
        val database = createTempDirectory("arianna-relation-order-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val evidence = Evidence("repo", "HEAD", "src/Main.kt", analyzerVersion = "test")
            store.replaceSnapshot(
                "repo",
                "HEAD",
                sequenceOf(KnowledgeEntity(EntityId("class:Main"), "class", "Main", evidence)),
                sequenceOf(
                    KnowledgeRelation(EntityId("class:Main"), "references", EntityId("class:Z"), Origin.STATIC, Confidence.MEDIUM, evidence),
                    KnowledgeRelation(EntityId("class:Main"), "defines", EntityId("method:Main.run"), Origin.STATIC, Confidence.HIGH, evidence),
                    KnowledgeRelation(EntityId("class:Main"), "calls", EntityId("method:Main.helper"), Origin.STATIC, Confidence.MEDIUM, evidence)
                )
            )

            val first = store.findRelationsPage("class:Main", 0, 2)
            val second = store.findRelationsPage("class:Main", 2, 2)

            assertEquals(listOf("calls", "defines"), first.items.map { it.type })
            assertEquals(listOf("references"), second.items.map { it.type })
            assertEquals(3, first.total)
        }
    }

    @Test
    fun `orders entities with equal names by kind and id`() {
        val database = createTempDirectory("arianna-entity-order-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val evidence = Evidence("repo", "HEAD", "src/Main.kt", analyzerVersion = "test")
            store.replaceSnapshot(
                "repo",
                "HEAD",
                sequenceOf(
                    KnowledgeEntity(EntityId("class:Main"), "class", "Main", evidence),
                    KnowledgeEntity(EntityId("method:Main"), "method", "Main", evidence),
                    KnowledgeEntity(EntityId("file:Main"), "file", "Main", evidence)
                ),
                emptySequence()
            )

            val page = store.findEntitiesPage("Main", 0, 3)

            assertEquals(listOf("class:Main", "file:Main", "method:Main"), page.items.map { it.id.value })
        }
    }

    @Test
    fun `reopens an existing schema without creating a second version`() {
        val database = createTempDirectory("arianna-schema-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).close()
        SQLiteKnowledgeStore(database).use { store ->
            assertEquals(null, store.getCurrentSnapshot("missing"))
        }
    }

    @Test
    fun `migrates an empty schema version table to v1`() {
        val database = createTempDirectory("arianna-migration-").resolve("knowledge.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)") }
        }

        SQLiteKnowledgeStore(database).use { store ->
            assertEquals(null, store.getCurrentSnapshot("repo"))
        }
    }

    @Test
    fun `migrates schema v1 entities by adding document content`() {
        val database = createTempDirectory("arianna-schema-v1-").resolve("knowledge.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                statement.execute("INSERT INTO schema_version(version) VALUES (1)")
                statement.execute("CREATE TABLE snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, repository TEXT NOT NULL, revision TEXT NOT NULL, current INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
                statement.execute("CREATE TABLE entities (snapshot_id INTEGER NOT NULL, entity_id TEXT NOT NULL, kind TEXT NOT NULL, qualified_name TEXT NOT NULL, repository TEXT, revision TEXT, file TEXT, start_line INTEGER, end_line INTEGER, analyzer_version TEXT, PRIMARY KEY(snapshot_id, entity_id))")
                statement.execute("CREATE TABLE relations (snapshot_id INTEGER NOT NULL, source_id TEXT NOT NULL, relation_type TEXT NOT NULL, target_id TEXT NOT NULL, origin TEXT NOT NULL, confidence TEXT NOT NULL, repository TEXT, revision TEXT, file TEXT, start_line INTEGER, end_line INTEGER, analyzer_version TEXT)")
            }
        }

        SQLiteKnowledgeStore(database).use { store ->
            store.replaceSnapshot("repo", "HEAD", sequenceOf(KnowledgeEntity(EntityId("document:README.md"), "document", "README.md", Evidence("repo", "HEAD", "README.md", analyzerVersion = "test"), "hello")), emptySequence())
            assertEquals("hello", store.findEntities("hello").single().content)
        }
    }

    @Test
    fun `rejects a schema newer than the supported version`() {
        val database = createTempDirectory("arianna-future-schema-").resolve("knowledge.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                it.execute("INSERT INTO schema_version(version) VALUES (999)")
            }
        }

        assertFailsWith<StorageException> { SQLiteKnowledgeStore(database) }
    }

    @Test
    fun `stores working tree overlay without replacing baseline`() {
        val database = createTempDirectory("arianna-overlay-").resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            val baselineEvidence = Evidence("repo", "base", "README.md", analyzerVersion = "test")
            store.replaceSnapshot(
                "repo", "base",
                sequenceOf(KnowledgeEntity(EntityId("document:README.md"), "document", "README.md", baselineEvidence, "baseline")),
                emptySequence()
            )
            store.replaceOverlaySnapshot(
                "repo", "WORKING_TREE:base",
                sequenceOf(KnowledgeEntity(EntityId("document:README.md"), "document", "README.md", baselineEvidence.copy(revision = "WORKING_TREE:base"), "overlay")),
                emptySequence()
            )

            assertEquals("base", store.currentRevision("repo"))
            assertEquals(SnapshotKind.BASELINE, store.getCurrentSnapshot("repo")?.kind)
            assertEquals("WORKING_TREE:base", store.getLatestSnapshot("repo", SnapshotKind.WORKING_TREE)?.revision)
            assertEquals("baseline", store.findEntities("baseline").single().content)
        }
    }
}
