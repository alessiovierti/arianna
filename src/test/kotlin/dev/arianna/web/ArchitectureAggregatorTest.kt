package dev.arianna.web

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureAggregatorTest {
    @Test
    fun `groups package entities and aggregates cross package relations`() {
        val webPackage = entity("package:dev.arianna.web", "package", "dev.arianna.web", "src/main/kotlin/dev/arianna/web/Web.kt")
        val corePackage = entity("package:dev.arianna.core", "package", "dev.arianna.core", "src/main/kotlin/dev/arianna/core/Core.kt")
        val controller = entity("class:Controller", "controller", "dev.arianna.web.Controller", "src/main/kotlin/dev/arianna/web/Controller.kt")
        val service = entity("class:Service", "service", "dev.arianna.core.Service", "src/main/kotlin/dev/arianna/core/Service.kt")
        val relation = KnowledgeRelation(controller.id, "injects", service.id, Origin.FRAMEWORK, Confidence.HIGH, controller.evidence!!)

        val architecture = ArchitectureAggregator.build(listOf(webPackage, corePackage, controller, service), listOf(relation))

        assertEquals(setOf("web", "core"), architecture.nodes.map { it.label }.toSet())
        assertEquals(1, architecture.relations.single().relationCount)
        assertEquals("framework", architecture.relations.single().origin)
    }

    @Test
    fun `prefers explicit modules over inferred package groups`() {
        val module = entity("module:api", "module", "api", "settings.gradle.kts")
        val file = entity("file:api/src/main/kotlin/Api.kt", "file", "api/src/main/kotlin/Api.kt", "api/src/main/kotlin/Api.kt")
        val apiClass = entity("class:Api", "class", "Api", "api/src/main/kotlin/Api.kt")
        val contains = KnowledgeRelation(module.id, "contains", file.id, Origin.DECLARED, Confidence.HIGH, module.evidence!!)

        val architecture = ArchitectureAggregator.build(listOf(module, file, apiClass), listOf(contains))

        assertTrue(architecture.nodes.any { it.label == "api" && it.kind == "module" && it.origin == "declared" })
        assertEquals(0, architecture.relations.size)
    }

    @Test
    fun `groups frontend and backend into expandable component nodes`() {
        val frontend = entity("class:FrontendApp", "class", "FrontendApp", "frontend/src/App.tsx")
        val backendModule = entity("module:backend:api", "module", "backend:api", "backend/settings.gradle")
        val backendFile = entity("file:backend/api/src/main/kotlin/Api.kt", "file", "backend/api/src/main/kotlin/Api.kt", "backend/api/src/main/kotlin/Api.kt")
        val backendClass = entity("class:Api", "class", "Api", "backend/api/src/main/kotlin/Api.kt")
        val contains = KnowledgeRelation(backendModule.id, "contains", backendFile.id, Origin.DECLARED, Confidence.HIGH, backendModule.evidence!!)

        val architecture = ArchitectureAggregator.build(listOf(frontend, backendModule, backendFile, backendClass), listOf(contains))

        val roots = architecture.nodes.filter { it.parentId == null }
        assertEquals(setOf("frontend", "backend"), roots.map { it.label }.toSet())
        assertTrue(architecture.nodes.any { it.label == "api" && it.parentId == roots.first { root -> root.label == "backend" }.id })
        assertTrue(architecture.relations.any { it.source == "macro:root:frontend" && it.target == "macro:root:backend" && it.confidence == "low" })
    }

    @Test
    fun `separates runtime topology from backend code structure`() {
        val backend = entity("module:backend", "module", "backend", "settings.gradle")
        val apiModule = entity("module:backend:api", "module", "backend:api", "backend/settings.gradle")
        val apiService = entity("runtime:api", "runtime_service", "api", "docker-compose.yml")
        val dbService = entity("runtime:db", "runtime_service", "db", "docker-compose.yml")
        val frontend = entity("class:Frontend", "class", "Frontend", "frontend/src/App.tsx")
        val relations = listOf(
            KnowledgeRelation(apiService.id, "implemented_by", backend.id, Origin.DECLARED, Confidence.HIGH, apiService.evidence!!),
            KnowledgeRelation(apiService.id, "depends_on", dbService.id, Origin.DECLARED, Confidence.HIGH, apiService.evidence!!),
            KnowledgeRelation(apiModule.id, "depends_on", backend.id, Origin.DECLARED, Confidence.HIGH, apiModule.evidence!!)
        )

        val architecture = ArchitectureAggregator.build(listOf(backend, apiModule, apiService, dbService, frontend), relations)
        val roots = architecture.nodes.filter { it.parentId == null }

        assertEquals(1, roots.count { it.label == "backend" })
        assertTrue(roots.any { it.label == "Runtime topology" && it.kind == "architecture-group" })
        assertTrue(roots.any { it.label == "Infrastructure" && it.kind == "architecture-group" })
        assertTrue(architecture.nodes.any { it.label == "api" && it.parentId == "macro:root:backend" })
        assertTrue(architecture.relations.any { it.source == "macro:group:runtime" && it.target == "macro:root:backend" && it.label.contains("implemented_by") })
    }

    private fun entity(id: String, kind: String, name: String, file: String) =
        KnowledgeEntity(EntityId(id), kind, name, Evidence("repo", "HEAD", file, 1, 1, "test"))
}
