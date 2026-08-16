package dev.arianna.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.arianna.core.indexing.ChangeVerifier
import dev.arianna.core.indexing.ImpactAnalyzer
import dev.arianna.core.indexing.RefactoringPlanner
import dev.arianna.core.indexing.SnapshotComparator
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Page
import dev.arianna.core.model.SnapshotDiff
import dev.arianna.core.model.SnapshotKind
import dev.arianna.core.config.AppConfig
import dev.arianna.core.model.RevisionPair
import dev.arianna.core.source.GitRevisionMaterializer
import dev.arianna.core.source.LocalGitRepository
import dev.arianna.core.source.openRepositorySource
import dev.arianna.core.query.KnowledgeQueryEngine
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.storage.SQLiteKnowledgeStore
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class McpServerConfig(
    val protocolVersion: String = "2024-11-05",
    val requestTimeoutMillis: Long = 30_000,
    val maxPageSize: Int = 100
)

class McpServer(
    private val repositoryPath: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val config: McpServerConfig = McpServerConfig()
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "arianna-mcp-request").apply { isDaemon = true }
    }

    fun run(input: InputStream, output: PrintStream) {
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val response = handleLine(line)
                if (response != null) {
                    output.println(response)
                    output.flush()
                }
            }
        }
        executor.shutdownNow()
    }

    fun handleLine(line: String): String? {
        val request = try {
            mapper.readTree(line)
        } catch (error: Exception) {
            return errorResponse(null, -32700, "Invalid JSON: ${error.message}")
        }
        val id = request.get("id")
        val method = request.path("method").asText("")
        if (method.isEmpty()) return errorResponse(id, -32600, "Missing method")
        if (!request.has("id")) {
            runCatching { dispatch(method, request.path("params")) }
            return null
        }
        val future = executor.submit<String> { dispatch(method, request.path("params")) }
        return try {
            successResponse(id, mapper.readTree(future.get(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)))
        } catch (_: TimeoutException) {
            future.cancel(true)
            errorResponse(id, -32001, "Request timeout")
        } catch (error: McpRequestException) {
            errorResponse(id, error.code, error.message)
        } catch (error: Exception) {
            val cause = error.cause
            if (cause is McpRequestException) errorResponse(id, cause.code, cause.message)
            else errorResponse(id, -32000, cause?.message ?: error.message ?: "Request failed")
        }
    }

    private fun dispatch(method: String, params: JsonNode): String = when (method) {
        "initialize" -> mapper.writeValueAsString(initializeResult())
        "ping" -> "{}"
        "tools/list" -> mapper.writeValueAsString(toolsListResult())
        "tools/call" -> callTool(params)
        else -> throw McpRequestException(-32601, "Unknown method: $method")
    }

    private fun callTool(params: JsonNode): String {
        val name = params.path("name").asText("")
        val arguments = params.path("arguments")
        val payload = when (name) {
            "search_knowledge" -> withStore { store ->
                val page = store.findEntitiesPage(
                    arguments.path("query").asText("").required("query"),
                    cursorOffset(arguments),
                    boundedLimit(arguments.path("limit").asInt(50)),
                    arguments.optionalText("repository"),
                    arguments.optionalText("file"),
                    arguments.optionalText("kind"),
                    arguments.optionalText("revision")
                )
                mapper.writeValueAsString(pageToJson(page, ::entityToJson))
            }
            "find_symbol" -> withStore { store ->
                val page = KnowledgeQueryEngine(store).findSymbolsPage(
                    arguments.path("query").asText("").required("query"),
                    cursorOffset(arguments),
                    boundedLimit(arguments.path("limit").asInt(50)),
                    arguments.optionalText("revision")
                )
                mapper.writeValueAsString(pageToJson(page, ::entityToJson))
            }
            "find_references" -> withStore { store ->
                val engine = KnowledgeQueryEngine(store)
                val page = engine.findReferencesPage(
                    arguments.path("query").asText("").required("query"),
                    cursorOffset(arguments),
                    boundedLimit(arguments.path("limit").asInt(50)),
                    arguments.optionalText("revision"),
                    arguments.optionalText("confidence")
                )
                mapper.writeValueAsString(pageToJson(page, ::relationToJson))
            }
            "find_implementations" -> withStore { store ->
                val engine = KnowledgeQueryEngine(store)
                val page = engine.findImplementationsPage(
                    arguments.path("query").asText("").required("query"),
                    cursorOffset(arguments),
                    boundedLimit(arguments.path("limit").asInt(50)),
                    arguments.optionalText("revision"),
                    arguments.optionalText("confidence")
                )
                mapper.writeValueAsString(pageToJson(page, ::relationToJson))
            }
            "find_relationships" -> withStore { store ->
                val page = store.findRelationsPage(arguments.path("entityId").asText("").required("entityId"), cursorOffset(arguments), boundedLimit(arguments.path("limit").asInt(50)), arguments.optionalText("revision"), arguments.optionalText("confidence"))
                mapper.writeValueAsString(pageToJson(page, ::relationToJson))
            }
            "get_evidence" -> withStore { store ->
                val query = arguments.path("query").asText("").required("query")
                val page = store.findEntitiesPage(
                    query,
                    cursorOffset(arguments),
                    boundedLimit(arguments.path("limit").asInt(50)),
                    arguments.optionalText("repository"),
                    arguments.optionalText("file"),
                    arguments.optionalText("kind"),
                    arguments.optionalText("revision")
                )
                mapper.writeValueAsString(pageToJson(page, ::entityToJson))
            }
            "analyze_change" -> withChangeContext(arguments) { context -> mapper.writeValueAsString(impactToJson(context.impact)) }
            "plan_refactor" -> withChangeContext(arguments) { context -> mapper.writeValueAsString(planToJson(RefactoringPlanner.plan(context.impact))) }
            "verify_change" -> withChangeContext(arguments) { context ->
                mapper.writeValueAsString(verificationToJson(ChangeVerifier.verify(context.diff, context.impact, context.baseEntities, context.overlayEntities, context.baseRelations, context.overlayRelations)))
            }
            else -> throw McpRequestException(-32602, "Unknown tool: $name")
        }
        return mapper.writeValueAsString(toolResult(payload))
    }

    private fun withStore(block: (SQLiteKnowledgeStore) -> String): String {
        val root = Path.of(openRepositorySource(repositoryPath).repositoryStatus().root)
        val config = AppConfig.forRepository(root)
        if (!config.databaseFile.toFile().exists()) throw McpRequestException(-32004, "Index not found; run learn index")
        return SQLiteKnowledgeStore(config.databaseFile).use(block)
    }

    private fun withChangeContext(arguments: JsonNode, block: (ChangeContext) -> String): String {
        val baseRevision = arguments.optionalText("baseRevision")
        val headRevision = arguments.optionalText("headRevision")
        if (baseRevision != null || headRevision != null) {
            if (baseRevision == null || headRevision == null) {
                throw McpRequestException(-32602, "baseRevision and headRevision must be specified together")
            }
            return withMaterializedRevisionContext(RevisionPair(baseRevision, headRevision), block)
        }
        return withStore { store ->
            val repository = openRepositorySource(repositoryPath)
            val root = Path.of(repository.repositoryStatus().root)
            val baseline = store.getCurrentSnapshot(root.toString()) ?: throw McpRequestException(-32004, "Baseline not found")
            val overlay = store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE) ?: throw McpRequestException(-32004, "Working-tree overlay not found")
            val currentWorkingTree = repository.workingTreeRevision()
            if (currentWorkingTree != overlay.revision) throw McpRequestException(-32005, "Working-tree overlay is stale; reindex the working tree")
            val baseEntities = store.entitiesForSnapshot(baseline.id)
            val overlayEntities = store.entitiesForSnapshot(overlay.id)
            val baseRelations = store.relationsForSnapshot(baseline.id)
            val overlayRelations = store.relationsForSnapshot(overlay.id)
            val diff = SnapshotComparator.compare(baseline.revision, overlay.revision, baseEntities, overlayEntities, baseRelations, overlayRelations)
            block(ChangeContext(diff, baseEntities, overlayEntities, baseRelations, overlayRelations, ImpactAnalyzer.analyze(diff, baseEntities, overlayEntities, baseRelations, overlayRelations)))
        }
    }

    private fun withMaterializedRevisionContext(pair: RevisionPair, block: (ChangeContext) -> String): String {
        val repository = LocalGitRepository(repositoryPath)
        val root = Path.of(repository.repositoryStatus().root)
        val materializer = GitRevisionMaterializer(root)
        val (base, head) = try {
            materializer.materializePair(pair)
        } catch (error: Exception) {
            throw McpRequestException(-32004, "Unable to materialize revisions ${pair.base} and ${pair.head}: ${error.message}")
        }
        try {
            val databaseRoot = try {
                Files.createTempDirectory("arianna-mcp-revision-")
            } catch (error: Exception) {
                throw McpRequestException(-32000, "Unable to create temporary revision workspace: ${error.message}")
            }
            try {
                SQLiteKnowledgeStore(databaseRoot.resolve("base.db")).use { baseStore ->
                    SQLiteKnowledgeStore(databaseRoot.resolve("head.db")).use { headStore ->
                        try {
                            SpringAwareIndexer().index(base, baseStore)
                            SpringAwareIndexer().index(head, headStore)
                        } catch (error: Exception) {
                            throw McpRequestException(-32006, "Unable to index revisions ${pair.base} and ${pair.head}: ${error.message}")
                        }
                        val baseSnapshot = baseStore.getCurrentSnapshot(base.root.toString())
                            ?: throw McpRequestException(-32006, "Base revision snapshot was not created")
                        val headSnapshot = headStore.getCurrentSnapshot(head.root.toString())
                            ?: throw McpRequestException(-32006, "Head revision snapshot was not created")
                        val baseEntities = baseStore.entitiesForSnapshot(baseSnapshot.id)
                        val headEntities = headStore.entitiesForSnapshot(headSnapshot.id)
                        val baseRelations = baseStore.relationsForSnapshot(baseSnapshot.id)
                        val headRelations = headStore.relationsForSnapshot(headSnapshot.id)
                        val diff = SnapshotComparator.compare(pair.base, pair.head, baseEntities, headEntities, baseRelations, headRelations)
                        return block(ChangeContext(diff, baseEntities, headEntities, baseRelations, headRelations, ImpactAnalyzer.analyze(diff, baseEntities, headEntities, baseRelations, headRelations)))
                    }
                }
            } finally {
                Files.walk(databaseRoot).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        } finally {
            base.close()
            head.close()
        }
    }

    private fun boundedLimit(value: Int): Int = value.coerceIn(1, config.maxPageSize)
    private fun boundedOffset(value: Int): Int = value.coerceAtLeast(0)

    private fun cursorOffset(arguments: JsonNode): Int {
        val cursor = arguments.optionalText("cursor")
        if (cursor == null) return boundedOffset(arguments.path("offset").asInt(0))
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()
            ?: throw McpRequestException(-32602, "Invalid cursor")
        if (!decoded.startsWith("arianna-v1:")) throw McpRequestException(-32602, "Unsupported cursor")
        return decoded.removePrefix("arianna-v1:").toIntOrNull()?.let(::boundedOffset)
            ?: throw McpRequestException(-32602, "Invalid cursor offset")
    }

    private fun cursor(offset: Int?): String? = offset?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString("arianna-v1:$it".toByteArray())
    }

    private fun initializeResult(): ObjectNode = mapper.createObjectNode().apply {
        put("protocolVersion", config.protocolVersion)
        set<ObjectNode>("capabilities", mapper.createObjectNode().set("tools", mapper.createObjectNode()))
        set<ObjectNode>("serverInfo", mapper.createObjectNode().put("name", "arianna").put("version", "0.1.0-SNAPSHOT"))
    }

    private fun toolsListResult(): ObjectNode = mapper.createObjectNode().apply {
        val tools = mapper.createArrayNode()
        toolDefinitions().forEach { tools.add(it) }
        set<ArrayNode>("tools", tools)
    }

    private fun toolDefinitions(): List<ObjectNode> = listOf(
        tool("search_knowledge", "Search indexed entities and document content", listOf("query")),
        tool("find_symbol", "Find symbols with source evidence", listOf("query")),
        tool("find_references", "Find callers and references for a symbol", listOf("query")),
        tool("find_implementations", "Find implementations and overrides for a symbol", listOf("query")),
        tool("find_relationships", "Find direct relationships for an entity", listOf("entityId")),
        tool("analyze_change", "Analyze a working-tree or base/head change closure", emptyList()),
        tool("plan_refactor", "Create an ordered refactoring plan for a working tree or base/head", emptyList()),
        tool("get_evidence", "Retrieve source evidence for a query", listOf("query")),
        tool("verify_change", "Verify residual references and unresolved risks for a working tree or base/head", emptyList())
    )

    private fun tool(name: String, description: String, required: List<String>): ObjectNode = mapper.createObjectNode().apply {
        put("name", name)
        put("description", description)
        val schema = mapper.createObjectNode().put("type", "object")
        val properties = mapper.createObjectNode()
        when (name) {
            "search_knowledge" -> {
                properties.putObject("query").put("type", "string")
                properties.putObject("repository").put("type", "string")
                properties.putObject("file").put("type", "string")
                properties.putObject("kind").put("type", "string")
                properties.putObject("revision").put("type", "string")
                properties.putObject("offset").put("type", "integer")
                properties.putObject("limit").put("type", "integer")
                properties.putObject("cursor").put("type", "string")
            }
            "find_symbol" -> {
                properties.putObject("query").put("type", "string")
                properties.putObject("revision").put("type", "string")
                properties.putObject("offset").put("type", "integer")
                properties.putObject("limit").put("type", "integer")
                properties.putObject("cursor").put("type", "string")
            }
            "find_references", "find_implementations" -> {
                properties.putObject("query").put("type", "string")
                properties.putObject("revision").put("type", "string")
                properties.putObject("confidence").put("type", "string")
                properties.putObject("offset").put("type", "integer")
                properties.putObject("limit").put("type", "integer")
                properties.putObject("cursor").put("type", "string")
            }
            "find_relationships" -> {
                properties.putObject("entityId").put("type", "string")
                properties.putObject("revision").put("type", "string")
                properties.putObject("confidence").put("type", "string")
                properties.putObject("offset").put("type", "integer")
                properties.putObject("limit").put("type", "integer")
                properties.putObject("cursor").put("type", "string")
            }
            "get_evidence" -> {
                properties.putObject("query").put("type", "string")
                properties.putObject("repository").put("type", "string")
                properties.putObject("file").put("type", "string")
                properties.putObject("kind").put("type", "string")
                properties.putObject("revision").put("type", "string")
                properties.putObject("offset").put("type", "integer")
                properties.putObject("limit").put("type", "integer")
                properties.putObject("cursor").put("type", "string")
            }
            "analyze_change", "plan_refactor", "verify_change" -> {
                properties.putObject("baseRevision").put("type", "string")
                properties.putObject("headRevision").put("type", "string")
            }
        }
        schema.set<ObjectNode>("properties", properties)
        if (required.isNotEmpty()) schema.set<ArrayNode>("required", mapper.createArrayNode().also { required.forEach(it::add) })
        set<ObjectNode>("inputSchema", schema)
    }

    private fun toolResult(payload: String): ObjectNode = mapper.createObjectNode().apply {
        val content = mapper.createArrayNode()
        content.add(mapper.createObjectNode().put("type", "text").put("text", payload))
        set<ArrayNode>("content", content)
    }

    private fun successResponse(id: JsonNode, result: JsonNode): String = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

    private fun errorResponse(id: JsonNode?, code: Int, message: String): String = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))

    private fun entityToJson(entity: KnowledgeEntity): ObjectNode = mapper.createObjectNode().apply {
        put("id", entity.id.value); put("kind", entity.kind); put("qualifiedName", entity.qualifiedName)
        entity.content?.let { put("content", it) }
        evidenceToJson(entity.evidence)?.let { set<ObjectNode>("evidence", it) }
    }

    private fun relationToJson(relation: KnowledgeRelation): ObjectNode = mapper.createObjectNode().apply {
        put("source", relation.source.value); put("type", relation.type); put("target", relation.target.value)
        put("origin", relation.origin.name.lowercase()); put("confidence", relation.confidence.name.lowercase())
        evidenceToJson(relation.evidence)?.let { set<ObjectNode>("evidence", it) }
    }

    private fun evidenceToJson(evidence: dev.arianna.core.model.Evidence?): ObjectNode? = evidence?.let {
        mapper.createObjectNode().put("repository", it.repository).put("revision", it.revision).put("file", it.file).apply {
            it.startLine?.let { line -> put("startLine", line) }; it.endLine?.let { line -> put("endLine", line) }; put("analyzerVersion", it.analyzerVersion)
        }
    }

    private fun <T> pageToJson(page: Page<T>, item: (T) -> ObjectNode): ObjectNode = mapper.createObjectNode().apply {
        set<ArrayNode>("items", mapper.createArrayNode().also { page.items.forEach { value -> it.add(item(value)) } })
        put("total", page.total); put("offset", page.offset); put("limit", page.limit); page.nextOffset?.let { put("nextOffset", it); put("nextCursor", cursor(it)) } ?: putNull("nextCursor")
    }

    private fun impactToJson(report: ImpactReport): ObjectNode = mapper.createObjectNode().apply {
        put("baseRevision", report.baseRevision); put("overlayRevision", report.overlayRevision); put("breakingCount", report.breakingCount); put("possibleCount", report.possibleCount)
        set<ArrayNode>("findings", mapper.createArrayNode().also { report.findings.forEach { finding -> it.add(findingToJson(finding)) } })
    }

    private fun findingToJson(finding: dev.arianna.core.model.ImpactFinding): ObjectNode = mapper.createObjectNode().apply {
        put("severity", finding.severity.name.lowercase())
        put("certainty", finding.certainty.name.lowercase())
        put("category", finding.category)
        put("message", finding.message)
        finding.entityId?.let { put("entityId", it.value) }
        finding.relation?.let { set<ObjectNode>("relation", relationToJson(it)) }
        evidenceToJson(finding.evidence)?.let { set<ObjectNode>("evidence", it) }
    }

    private fun planToJson(plan: dev.arianna.core.model.RefactoringPlan): ObjectNode = mapper.createObjectNode().apply {
        put("baseRevision", plan.baseRevision); put("overlayRevision", plan.overlayRevision); put("externalVerificationRequired", plan.externalVerificationRequired)
        set<ArrayNode>("steps", mapper.createArrayNode().also { plan.steps.forEach { step ->
            it.add(mapper.createObjectNode().put("order", step.order).put("category", step.category).put("title", step.title).put("findingCount", step.findingCount).apply {
                set<ArrayNode>("actions", mapper.createArrayNode().also { actions -> step.actions.forEach(actions::add) })
                set<ArrayNode>("entityIds", mapper.createArrayNode().also { ids -> step.entityIds.forEach { ids.add(it.value) } })
                set<ArrayNode>("evidence", mapper.createArrayNode().also { evidence -> step.evidence.mapNotNull(::evidenceToJson).forEach(evidence::add) })
            })
        } })
    }

    private fun verificationToJson(report: dev.arianna.core.model.VerificationReport): ObjectNode = mapper.createObjectNode().apply {
        put("baseRevision", report.baseRevision); put("overlayRevision", report.overlayRevision); put("externalVerificationRequired", report.externalVerificationRequired)
        put("confirmedIssueCount", report.confirmedIssueCount)
        set<ArrayNode>("issues", mapper.createArrayNode().also { report.issues.forEach { issue ->
            it.add(mapper.createObjectNode().put("severity", issue.severity.name.lowercase()).put("certainty", issue.certainty.name.lowercase()).put("category", issue.category).put("message", issue.message).apply {
                issue.entityId?.let { entityId -> put("entityId", entityId.value) }
                evidenceToJson(issue.evidence)?.let { evidence -> set<ObjectNode>("evidence", evidence) }
            })
        } })
    }

    private fun String.required(name: String): String = takeIf { it.isNotBlank() } ?: throw McpRequestException(-32602, "Missing argument: $name")
    private fun JsonNode.optionalText(name: String): String? = get(name)?.takeIf { !it.isNull }?.asText()

    private data class ChangeContext(
        val diff: SnapshotDiff,
        val baseEntities: List<KnowledgeEntity>,
        val overlayEntities: List<KnowledgeEntity>,
        val baseRelations: List<KnowledgeRelation>,
        val overlayRelations: List<KnowledgeRelation>,
        val impact: ImpactReport
    )

    private class McpRequestException(val code: Int, override val message: String) : RuntimeException(message)
}
