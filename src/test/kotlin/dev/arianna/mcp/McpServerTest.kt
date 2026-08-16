package dev.arianna.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.arianna.core.config.AppConfig
import dev.arianna.core.indexing.RepositoryFileIndexer
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.core.source.LocalGitRepository
import dev.arianna.core.source.LocalDirectorySource
import dev.arianna.storage.SQLiteKnowledgeStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private val mapper = ObjectMapper()

    @Test
    fun `queries an indexed non Git directory`() {
        val root = Files.createTempDirectory("arianna-mcp-local-")
        Files.writeString(root.resolve("README.md"), "# PaymentService\n")
        val source = LocalDirectorySource(root)
        val config = AppConfig.forRepository(root).ensureDataDirectory()
        SQLiteKnowledgeStore(config.databaseFile).use { store ->
            RepositoryFileIndexer().index(source, store)
        }

        val response = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"PaymentService"}}}"""))
        )
        val payload = mapper.readTree(response.path("result").path("content").first().path("text").asText())

        assertEquals(1, payload.path("total").asInt())
        assertEquals("README.md", payload.path("items").first().path("qualifiedName").asText())
    }

    @Test
    fun `analyzes a working tree change through MCP on a non Git directory`() {
        val root = Files.createTempDirectory("arianna-mcp-local-impact-")
        val sourceFile = root.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class PaymentService {
                fun process(paymentId: String) = paymentId
            }
            class PaymentController {
                private val service: PaymentService = PaymentService()
                fun get() = service.process("payment")
            }
            """.trimIndent()
        )
        val source = LocalDirectorySource(root)
        val config = AppConfig.forRepository(root).ensureDataDirectory()
        SQLiteKnowledgeStore(config.databaseFile).use { store ->
            SpringAwareIndexer().index(source, store)
            Files.writeString(
                sourceFile,
                """
                class PaymentService {
                    fun process(paymentId: String, currency: String) = paymentId
                }
                class PaymentController {
                    private val service: PaymentService = PaymentService()
                    fun get() = service.process("payment", "EUR")
                }
                """.trimIndent()
            )
            SpringAwareIndexer().indexOverlay(source, store)
        }

        val response = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"analyze_change","arguments":{}}}"""))
        )
        val payload = mapper.readTree(response.path("result").path("content").first().path("text").asText())
        val caller = payload.path("findings").firstOrNull { it.path("category").asText() == "direct_callers" }
            ?: error(payload.toString())
        assertEquals("method:PaymentController.get", caller.path("entityId").asText())
    }

    @Test
    fun `analyzes base and head revisions through MCP without a local index`() {
        val root = Files.createTempDirectory("arianna-mcp-pr-")
        val source = root.resolve("src/main/kotlin/Payment.kt")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            """
            class PaymentService {
                fun process(paymentId: String) = paymentId
            }
            class PaymentController {
                private val service: PaymentService = PaymentService()
                fun get() = service.process("payment")
            }
            """.trimIndent()
        )
        git(root, "init")
        git(root, "config", "user.email", "arianna@test.local")
        git(root, "config", "user.name", "Arianna Test")
        git(root, "add", ".")
        git(root, "commit", "-m", "baseline")
        val base = gitOutput(root, "rev-parse", "HEAD").trim()

        Files.writeString(
            source,
            """
            class PaymentService {
                fun process(paymentId: String, currency: String) = paymentId
            }
            class PaymentController {
                private val service: PaymentService = PaymentService()
                fun get() = service.process("payment", "EUR")
            }
            """.trimIndent()
        )
        git(root, "add", ".")
        git(root, "commit", "-m", "head")
        val head = gitOutput(root, "rev-parse", "HEAD").trim()

        val request = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"analyze_change","arguments":{"baseRevision":"$base","headRevision":"$head"}}}"""
        val response = mapper.readTree(requireNotNull(McpServer(root).handleLine(request)))
        if (response.has("error")) error(response.toString())
        val payload = mapper.readTree(response.path("result").path("content").first().path("text").asText())
        assertEquals(base, payload.path("baseRevision").asText())
        assertEquals(head, payload.path("overlayRevision").asText())
        val caller = payload.path("findings").firstOrNull { it.path("category").asText() == "direct_callers" }
            ?: error(payload.toString())
        assertEquals("method:PaymentController.get", caller.path("entityId").asText())
        assertEquals(base, caller.path("evidence").path("revision").asText())
    }

    @Test
    fun `implements initialize and tools list JSON-RPC methods`() {
        val server = McpServer(Path.of("/tmp/arianna-mcp-test"))

        val initialize = mapper.readTree(requireNotNull(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")))
        val tools = mapper.readTree(requireNotNull(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")))

        assertEquals("2.0", initialize.path("jsonrpc").asText())
        assertEquals("2024-11-05", initialize.path("result").path("protocolVersion").asText())
        assertEquals(2, tools.path("id").asInt())
        assertEquals(9, tools.path("result").path("tools").size())
        assertTrue(tools.path("result").path("tools").any { it.path("name").asText() == "verify_change" })
        val searchTool = tools.path("result").path("tools").first { it.path("name").asText() == "search_knowledge" }
        assertTrue(searchTool.path("inputSchema").path("properties").has("repository"))
        assertTrue(searchTool.path("inputSchema").path("properties").has("limit"))
        val analyzeTool = tools.path("result").path("tools").first { it.path("name").asText() == "analyze_change" }
        assertTrue(analyzeTool.path("inputSchema").path("properties").has("baseRevision"))
        assertTrue(analyzeTool.path("inputSchema").path("properties").has("headRevision"))
        val referencesTool = tools.path("result").path("tools").first { it.path("name").asText() == "find_references" }
        assertTrue(referencesTool.path("inputSchema").path("properties").has("cursor"))
        assertTrue(referencesTool.path("inputSchema").path("properties").has("revision"))
        assertTrue(referencesTool.path("inputSchema").path("properties").has("confidence"))
    }

    @Test
    fun `returns JSON-RPC errors and suppresses notification responses`() {
        val server = McpServer(Path.of("/tmp/arianna-mcp-test"))

        val malformed = mapper.readTree(requireNotNull(server.handleLine("not-json")))
        val unknown = mapper.readTree(requireNotNull(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"unknown\"}")))
        val notification = server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")

        assertEquals(-32700, malformed.path("error").path("code").asInt())
        assertEquals(-32601, unknown.path("error").path("code").asInt())
        assertNull(notification)
    }

    @Test
    fun `tools call returns the indexed query result`() {
        val root = Files.createTempDirectory("arianna-mcp-repo-")
        Files.writeString(root.resolve("README.md"), "# PaymentService\n")
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("docs/PaymentService.md"), "PaymentService usage\n")
        git(root, "init")
        git(root, "config", "user.email", "arianna@test.local")
        git(root, "config", "user.name", "Arianna Test")
        git(root, "add", "README.md", "docs/PaymentService.md")
        git(root, "commit", "-m", "fixture")

        val repository = LocalGitRepository(root)
        val config = AppConfig.forRepository(Path.of(repository.status().root)).ensureDataDirectory()
        SQLiteKnowledgeStore(config.databaseFile).use { store ->
            RepositoryFileIndexer().index(repository, store)
            val snapshot = store.getCurrentSnapshot(Path.of(repository.status().root).toString())
            val documents = store.entitiesForSnapshot(requireNotNull(snapshot).id).filter { it.kind == "document" }
            assertEquals(2, documents.size, documents.toString())
            val search = store.findEntitiesPage("PaymentService", 0, 10, kind = "document")
            assertEquals(2, search.total, "docs=${documents.joinToString { it.qualifiedName + ":" + it.content }}; items=${search.items}")
        }

        val response = ObjectMapper().readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"PaymentService","kind":"document","limit":1}}}"""))
        )
        val text = response.path("result").path("content").first().path("text").asText()
        val payload = ObjectMapper().readTree(text)

        assertEquals(2, payload.path("total").asInt())
        assertEquals(1, payload.path("items").size())
        assertTrue(payload.path("nextCursor").asText().isNotBlank())
        val next = ObjectMapper().readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"PaymentService","kind":"document","limit":1,"cursor":"${payload.path("nextCursor").asText()}"}}}"""))
        )
        val nextPayload = ObjectMapper().readTree(next.path("result").path("content").first().path("text").asText())
        assertEquals(1, nextPayload.path("offset").asInt())
        assertEquals(1, nextPayload.path("items").size())

        val evidenceResponse = ObjectMapper().readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_evidence","arguments":{"query":"PaymentService","kind":"document","revision":"${snapshotRevision(root)}","limit":1}}}"""))
        )
        val evidencePayload = ObjectMapper().readTree(evidenceResponse.path("result").path("content").first().path("text").asText())
        assertEquals(2, evidencePayload.path("total").asInt())
        assertEquals(1, evidencePayload.path("items").size())
        assertTrue(evidencePayload.path("items").first().path("evidence").path("file").asText().isNotBlank())
        assertTrue(evidencePayload.path("nextCursor").asText().isNotBlank())
    }

    @Test
    fun `impact tool preserves finding evidence and relation`() {
        val root = Files.createTempDirectory("arianna-mcp-impact-")
        val source = root.resolve("src/main/kotlin/Payment.kt")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            """
            class PaymentService {
                fun process(paymentId: String) = paymentId
            }

            class PaymentController {
                private val service: PaymentService = PaymentService()
                fun get() = service.process("payment")
            }
            """.trimIndent()
        )
        git(root, "init")
        git(root, "config", "user.email", "arianna@test.local")
        git(root, "config", "user.name", "Arianna Test")
        git(root, "add", ".")
        git(root, "commit", "-m", "baseline")

        val repository = LocalGitRepository(root)
        val base = repository.status().head
        val config = AppConfig.forRepository(Path.of(repository.status().root)).ensureDataDirectory()
        SQLiteKnowledgeStore(config.databaseFile).use { store ->
            SpringAwareIndexer().index(repository, store)
            Files.writeString(
                source,
                """
                class PaymentService {
                    fun process(paymentId: String, audit: Boolean) = paymentId
                }

                class PaymentController {
                    private val service: PaymentService = PaymentService()
                    fun get() = service.process("payment")
                }
                """.trimIndent()
            )
            SpringAwareIndexer().indexOverlay(repository, store)
        }

        val response = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"analyze_change","arguments":{}}}"""))
        )
        if (response.has("error")) error(response.toString())
        val payload = mapper.readTree(response.path("result").path("content").first().path("text").asText())
        val finding = payload.path("findings").firstOrNull { it.path("category").asText() == "direct_callers" }
            ?: error(payload.toString())
        assertTrue(finding.path("entityId").asText().isNotBlank())
        assertEquals("calls", finding.path("relation").path("type").asText())
        assertEquals("src/main/kotlin/Payment.kt", finding.path("evidence").path("file").asText())
        assertTrue(finding.path("evidence").path("startLine").asInt() > 0)

        val planResponse = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"plan_refactor","arguments":{}}}"""))
        )
        val planPayload = mapper.readTree(planResponse.path("result").path("content").first().path("text").asText())
        val callerStep = planPayload.path("steps").first { it.path("category").asText() == "callers" }
        assertTrue(callerStep.path("actions").size() > 0)
        assertTrue(callerStep.path("entityIds").size() > 0)
        assertTrue(callerStep.path("evidence").first().path("file").asText().isNotBlank())

        val referencesResponse = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"find_references","arguments":{"query":"PaymentService.process","limit":10}}}"""))
        )
        val referencesPayload = mapper.readTree(referencesResponse.path("result").path("content").first().path("text").asText())
        assertTrue(referencesPayload.path("items").any { it.path("source").asText() == "method:PaymentController.get" })
        assertTrue(referencesPayload.path("items").first().path("evidence").path("file").asText().isNotBlank())
        val filteredReferencesResponse = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"find_references","arguments":{"query":"PaymentService.process","limit":10,"revision":"$base","confidence":"medium"}}}"""))
        )
        val filteredReferencesPayload = mapper.readTree(filteredReferencesResponse.path("result").path("content").first().path("text").asText())
        assertTrue(filteredReferencesPayload.path("items").any { it.path("source").asText() == "method:PaymentController.get" })

        val symbolResponse = mapper.readTree(
            requireNotNull(McpServer(root).handleLine("""{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"find_symbol","arguments":{"query":"PaymentService.process","revision":"$base","limit":10}}}"""))
        )
        val symbolPayload = mapper.readTree(symbolResponse.path("result").path("content").first().path("text").asText())
        assertEquals("method:PaymentService.process", symbolPayload.path("items").first().path("id").asText())
        assertTrue(symbolPayload.path("items").none { it.path("kind").asText() == "file" || it.path("kind").asText() == "document" })
    }

    private fun git(root: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments.toList()).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }

    private fun gitOutput(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments.toList()).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }

    private fun snapshotRevision(root: Path): String = "${LocalGitRepository(root).status().head}"
}
