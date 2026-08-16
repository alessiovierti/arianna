package dev.arianna.core.indexing

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeArchitectureAdapterTest {
    @Test
    fun `indexes compose services dependencies and backend build context`() {
        val root = createTempDirectory("arianna-compose-")
        root.resolve("docker-compose.yml").writeText(
            """
            services:
              db:
                image: postgres:17
              api:
                build:
                  context: ./backend
                depends_on:
                  db:
                    condition: service_healthy
              scraper:
                build:
                  context: ./backend
                depends_on:
                  - db
            """.trimIndent()
        )
        root.resolve("backend").createDirectories()

        val analysis = ComposeArchitectureAdapter.analyze(root, "repo", "HEAD")

        assertEquals(setOf("db", "api", "scraper"), analysis.entities.map { it.qualifiedName }.toSet())
        assertTrue(analysis.relations.any { it.source.value == "runtime:api" && it.type == "depends_on" && it.target.value == "runtime:db" })
        assertTrue(analysis.relations.any { it.source.value == "runtime:api" && it.type == "implemented_by" && it.target.value == "module:backend" })
    }
}
