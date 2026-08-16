package dev.arianna.web

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.storage.SQLiteKnowledgeStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebServerTest {
    @Test
    fun `snapshot export contains only the portable html and database`() {
        val root = Files.createTempDirectory("arianna-snapshot-export-")
        val database = root.resolve(".arianna/knowledge.db")
        database.parent.createDirectories()
        SQLiteKnowledgeStore(database).use { store -> store.replaceSnapshot(root.toString(), "HEAD", emptySequence(), emptySequence()) }
        val archive = root.resolve("snapshot.zip")

        SnapshotExporter.export(root, archive)

        ZipFile(archive.toFile()).use { zip ->
            assertEquals(setOf("index.html", "knowledge.db"), zip.entries().asSequence().map { it.name }.toSet())
            val html = zip.getInputStream(zip.getEntry("index.html")).bufferedReader().readText()
            assertContains(html, "Arianna Explorer")
            assertContains(html, "__ARIANNA_SNAPSHOT_DATA__")
            assertContains(html, "Read-only snapshot")
            assertContains(html, "action-disabled")
            assertTrue(!html.contains("href=\"/style.css\""))
            assertTrue(!html.contains("src=\"/app.js"))
            assertTrue(zip.getInputStream(zip.getEntry("knowledge.db")).readBytes().isNotEmpty())
        }
    }

    @Test
    fun `snapshot mode keeps navigation read only and independent of repository files`() = testApplication {
        val root = Files.createTempDirectory("arianna-snapshot-web-")
        root.resolve("index.html").writeText("<!doctype html><title>snapshot</title>")
        val database = root.resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            store.replaceSnapshot("original-repository", "HEAD", sequenceOf(KnowledgeEntity(EntityId("class:Snapshot"), "class", "Snapshot", null)), emptySequence())
        }
        application { ariannaWebModule(root, snapshot = true) }

        val overview = client.get("/api/overview")
        val search = client.get("/api/search?q=Snapshot")
        val index = client.post("/api/index?mode=baseline")
        val source = client.get("/api/source?path=Main.java")

        assertEquals(HttpStatusCode.OK, overview.status)
        assertContains(overview.bodyAsText(), "\"snapshot\":true")
        assertContains(search.bodyAsText(), "Snapshot")
        assertEquals(HttpStatusCode.Conflict, index.status)
        assertEquals(HttpStatusCode.Conflict, source.status)
    }

    @Test
    fun `health endpoint is available without an index`() = testApplication {
        val root = Files.createTempDirectory("arianna-web-health-")
        application { ariannaWebModule(root) }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"status\":\"ok\"")
    }

    @Test
    fun `overview is available without an index and exposes indexing job endpoint`() = testApplication {
        val root = Files.createTempDirectory("arianna-web-onboarding-")
        application { ariannaWebModule(root) }

        val overview = client.get("/api/overview")
        assertEquals(HttpStatusCode.OK, overview.status)
        assertContains(overview.bodyAsText(), "\"indexPresent\":false")

        val invalid = client.post("/api/index?mode=invalid")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertContains(invalid.bodyAsText(), "mode must be baseline or working-tree")
    }

    @Test
    fun `overview and entity endpoints expose evidence and relations`() = testApplication {
        val root = Files.createTempDirectory("arianna-web-overview-")
        root.resolve("src/main/java/fixture").createDirectories()
        root.resolve(".arianna").createDirectories()
        root.resolve("src/main/java/fixture/PaymentService.java").writeText("class PaymentService {}\n")
        val evidence = Evidence(root.toString(), "HEAD", "src/main/java/fixture/PaymentService.java", 1, 1, "test")
        val service = KnowledgeEntity(EntityId("class:PaymentService"), "class", "fixture.PaymentService", evidence)
        val controller = KnowledgeEntity(EntityId("class:PaymentController"), "class", "fixture.PaymentController", evidence)
        val relation = KnowledgeRelation(EntityId("class:PaymentController"), "calls", EntityId("class:PaymentService"), Origin.STATIC, Confidence.HIGH, evidence)
        SQLiteKnowledgeStore(root.resolve(".arianna/knowledge.db")).use { store ->
            store.replaceSnapshot(root.toString(), "HEAD", sequenceOf(service, controller), sequenceOf(relation))
        }
        application { ariannaWebModule(root) }

        val overview = client.get("/api/overview")
        val search = client.get("/api/search?q=PaymentService")
        val detail = client.get("/api/entities?id=class%3APaymentService")
        val relationships = client.get("/api/entities/relationships?entityId=class%3APaymentService")
        val source = client.get("/api/source?path=src/main/java/fixture/PaymentService.java&line=1")
        val documents = client.get("/api/documents/index")

        assertEquals(HttpStatusCode.OK, overview.status)
        assertContains(overview.bodyAsText(), "\"class\":2")
        assertContains(overview.bodyAsText(), "\"architecture\":")
        assertContains(overview.bodyAsText(), "inferred-package")
        assertContains(search.bodyAsText(), "fixture.PaymentService")
        assertContains(detail.bodyAsText(), "src/main/java/fixture/PaymentService.java")
        assertContains(relationships.bodyAsText(), "\"type\":\"calls\"")
        assertEquals(HttpStatusCode.OK, source.status)
        assertContains(source.bodyAsText(), "PaymentService.java")
        assertContains(source.bodyAsText(), "\"focused\":true")
        assertEquals(HttpStatusCode.OK, documents.status)
        assertContains(documents.bodyAsText(), "\"items\":[]")
    }

    @Test
    fun `overview exposes layered architecture nodes and scoped relations`() = testApplication {
        val root = Files.createTempDirectory("arianna-web-architecture-")
        root.resolve("backend/settings.gradle").parent.createDirectories()
        root.resolve("frontend/src/App.tsx").parent.createDirectories()
        root.resolve("backend/api/src/main/kotlin/Api.kt").parent.createDirectories()
        val backendEvidence = Evidence(root.toString(), "HEAD", "settings.gradle", 1, 1, "test")
        val nestedEvidence = Evidence(root.toString(), "HEAD", "backend/settings.gradle", 1, 1, "test")
        val runtimeEvidence = Evidence(root.toString(), "HEAD", "docker-compose.yml", 1, 1, "test")
        val entities = listOf(
            KnowledgeEntity(EntityId("module:backend"), "module", "backend", backendEvidence),
            KnowledgeEntity(EntityId("module:backend:api"), "module", "backend:api", nestedEvidence),
            KnowledgeEntity(EntityId("runtime:api"), "runtime_service", "api", runtimeEvidence),
            KnowledgeEntity(EntityId("runtime:db"), "runtime_service", "db", runtimeEvidence),
            KnowledgeEntity(EntityId("class:Frontend"), "class", "Frontend", Evidence(root.toString(), "HEAD", "frontend/src/App.tsx", 1, 1, "test"))
        )
        val relations = listOf(
            KnowledgeRelation(EntityId("runtime:api"), "implemented_by", EntityId("module:backend"), Origin.DECLARED, Confidence.HIGH, runtimeEvidence),
            KnowledgeRelation(EntityId("runtime:api"), "depends_on", EntityId("runtime:db"), Origin.DECLARED, Confidence.HIGH, runtimeEvidence)
        )
        root.resolve(".arianna").createDirectories()
        SQLiteKnowledgeStore(root.resolve(".arianna/knowledge.db")).use { store ->
            store.replaceSnapshot(root.toString(), "HEAD", entities.asSequence(), relations.asSequence())
        }
        application { ariannaWebModule(root) }

        val overview = client.get("/api/overview")
        val body = overview.bodyAsText()
        assertEquals(HttpStatusCode.OK, overview.status)
        assertContains(body, "\"label\":\"backend\"")
        assertContains(body, "\"label\":\"Runtime topology\"")
        assertContains(body, "\"source\":\"macro:group:runtime\"")
        assertContains(body, "\"target\":\"macro:group:infrastructure\"")
        assertTrue(!body.contains("backend:common' include"))
    }

    @Test
    fun `missing entity returns structured not found`() = testApplication {
        val root = Files.createTempDirectory("arianna-web-missing-")
        root.resolve(".arianna").createDirectories()
        SQLiteKnowledgeStore(root.resolve(".arianna/knowledge.db")).use { store ->
            store.replaceSnapshot(root.toString(), "HEAD", emptySequence(), emptySequence())
        }
        application { ariannaWebModule(root) }

        val response = client.get("/api/entities?id=missing")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "not_found")
        assertTrue(response.bodyAsText().contains("Entity not found"))
    }
}
