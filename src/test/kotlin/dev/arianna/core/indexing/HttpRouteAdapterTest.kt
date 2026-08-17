package dev.arianna.core.indexing

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class HttpRouteAdapterTest {
    @Test
    fun `indexes explicit ktor routes with source evidence`() {
        val root = createTempDirectory("arianna-http-routes-")
        val file = root.resolve("src/main/kotlin/dev/example/WebServer.kt")
        file.parent.createDirectories()
        file.writeText(
            """
            fun module() {
                get("/api/payments") { call.respondText("ok") }
                post("/api/payments") { call.respondText("ok") }
            }
            """.trimIndent()
        )

        val analysis = HttpRouteAdapter().analyze(root, "/repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "endpoint:get:/api/payments" })
        assertTrue(analysis.entities.any { it.id.value == "endpoint:post:/api/payments" })
        assertTrue(analysis.relations.any { it.source.value == "function:module" && it.type == "exposes_endpoint" })
        assertTrue(analysis.relations.any { it.source.value == "file:src/main/kotlin/dev/example/WebServer.kt" && it.target.value == "function:module" && it.type == "contains" })
        assertTrue(analysis.entities.filter { it.kind == "endpoint" }.all { it.evidence?.file == "src/main/kotlin/dev/example/WebServer.kt" })
    }

    @Test
    fun `honors repository ignore configuration`() {
        val root = createTempDirectory("arianna-http-ignore-")
        root.resolve(".arianna").createDirectories()
        root.resolve(".arianna/ignore").writeText("src/test/\n")
        val file = root.resolve("src/test/kotlin/Fixture.kt")
        file.parent.createDirectories()
        file.writeText("fun fixture() { get(\"/api/fixture\") {} }")

        val analysis = HttpRouteAdapter().analyze(root, "/repo", "HEAD")

        assertTrue(analysis.entities.none { it.qualifiedName.contains("/api/fixture") })
    }

    @Test
    fun `does not treat qualified get calls as ktor routes`() {
        val root = createTempDirectory("arianna-http-map-get-")
        val file = root.resolve("src/main/kotlin/Lookup.kt")
        file.parent.createDirectories()
        file.writeText(
            """
            fun lookup(routes: Map<String, String>) {
                routes.get("/not-a-route")
                response.get("/also-not-a-route")
                get("/real-route") { call.respondText("ok") }
            }
            """.trimIndent()
        )

        val analysis = HttpRouteAdapter().analyze(root, "/repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "endpoint:get:/real-route" })
        assertTrue(analysis.entities.none { it.id.value == "endpoint:get:/not-a-route" })
        assertTrue(analysis.entities.none { it.id.value == "endpoint:get:/also-not-a-route" })
    }
}
