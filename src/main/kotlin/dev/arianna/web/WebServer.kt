package dev.arianna.web

import com.fasterxml.jackson.databind.SerializationFeature
import dev.arianna.core.config.AppConfig
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.api.IndexProgressListener
import dev.arianna.core.indexing.ChangeVerifier
import dev.arianna.core.indexing.ImpactAnalyzer
import dev.arianna.core.indexing.RefactoringPlanner
import dev.arianna.core.indexing.SnapshotComparator
import dev.arianna.core.model.Confidence
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.ImpactFinding
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.RefactoringPlan
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.model.RevisionPair
import dev.arianna.core.model.SnapshotKind
import dev.arianna.core.model.SnapshotDiff
import dev.arianna.core.model.VerificationReport
import dev.arianna.core.query.KnowledgeQueryEngine
import dev.arianna.core.source.GitRevisionMaterializer
import dev.arianna.core.source.LocalGitRepository
import dev.arianna.core.source.ProjectLayout
import dev.arianna.core.source.RepositoryPathFilter
import dev.arianna.core.source.openRepositorySource
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.storage.SQLiteKnowledgeStore
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080
)

class WebServer(
    private val repositoryPath: Path,
    private val config: WebServerConfig = WebServerConfig(),
    private val snapshot: Boolean = false
) {
    fun start(wait: Boolean = true): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val root = repositoryRoot(repositoryPath)
        return embeddedServer(Netty, host = config.host, port = config.port) {
            ariannaWebModule(root, snapshot)
        }.start(wait)
    }

    private fun repositoryRoot(path: Path): Path =
        Path.of(openRepositorySource(path).repositoryStatus().root)

}

private data class WebContext(
    val root: Path,
    val snapshot: Boolean = false,
    val databaseFile: Path = if (snapshot) root.resolve("knowledge.db") else AppConfig.forRepository(root).databaseFile,
    val jobs: IndexJobManager = IndexJobManager(root)
)

private class IndexJobManager(private val root: Path) {
    private val jobs = ConcurrentHashMap<String, MutableIndexJob>()
    @Volatile private var activeJobId: String? = null

    @Synchronized
    fun start(mode: String): IndexJobDto {
        if (mode !in setOf("baseline", "working-tree")) throw WebBadRequest("mode must be baseline or working-tree")
        activeJobId?.let { id ->
            val current = jobs[id]
            if (current != null && current.status in setOf("queued", "running")) throw WebConflict("An indexing job is already running: $id")
        }
        val job = MutableIndexJob(UUID.randomUUID().toString(), mode)
        jobs[job.id] = job
        activeJobId = job.id
        Thread({ run(job) }, "arianna-index-${job.id.take(8)}").apply { isDaemon = true }.start()
        return job.dto()
    }

    fun get(id: String?): IndexJobDto = (jobs[id] ?: throw WebNotFound("Index job not found: $id")).dto()

    @Synchronized
    fun cancel(id: String?): IndexJobDto {
        val job = jobs[id] ?: throw WebNotFound("Index job not found: $id")
        if (job.status == "queued" || job.status == "running") job.cancelRequested = true
        return job.dto()
    }

    private fun run(job: MutableIndexJob) {
        job.status = "running"
        try {
            val source = openRepositorySource(root)
            val status = source.repositoryStatus()
            if (job.mode == "baseline") {
                val changed = (status.stagedFiles + status.modifiedFiles + status.untrackedFiles + status.deletedFiles)
                    .distinct().filterNot { RepositoryPathFilter.isIgnored(root, root.resolve(it)) }
                if (changed.isNotEmpty()) throw WebConflict("Working tree is not clean; use working-tree indexing or commit the changes first")
            } else if (!Files.exists(AppConfig.forRepository(root).databaseFile)) {
                throw WebConflict("Baseline index not found; index the repository before indexing the working tree")
            }
            val config = AppConfig.forRepository(root).ensureDataDirectory()
            RepositoryPathFilter.ensureIgnoreFile(root)
            SQLiteKnowledgeStore(config.databaseFile).use { store ->
                val listener = IndexProgressListener { progress ->
                    if (job.cancelRequested) throw IndexCancelled()
                    job.stage = progress.stage
                    job.percent = progress.percent
                    job.message = progress.message
                }
                val result = if (job.mode == "working-tree") SpringAwareIndexer().indexOverlay(source, store, listener)
                else SpringAwareIndexer().index(source, store, listener)
                job.revision = result.revision
                job.files = result.indexedFiles
                job.entities = result.indexedEntities
                job.relations = result.indexedRelations
            }
            job.status = "completed"
            job.percent = 100
            job.message = "Indexing complete"
        } catch (error: Exception) {
            if (error is IndexCancelled || job.cancelRequested) {
                job.status = "cancelled"
                job.message = "Indexing cancelled"
            } else {
                job.status = "failed"
                job.error = error.message ?: error::class.simpleName ?: "Indexing failed"
                job.message = "Indexing failed"
            }
        } finally {
            synchronized(this) { if (activeJobId == job.id) activeJobId = null }
        }
    }
}

private class IndexCancelled : RuntimeException()

private class MutableIndexJob(val id: String, val mode: String) {
    @Volatile var status = "queued"
    @Volatile var stage = "queued"
    @Volatile var percent = 0
    @Volatile var message = "Waiting to start"
    @Volatile var cancelRequested = false
    @Volatile var revision: String? = null
    @Volatile var files = 0
    @Volatile var entities = 0
    @Volatile var relations = 0
    @Volatile var error: String? = null
    fun dto() = IndexJobDto(id, mode, status, stage, percent, message, files, entities, relations, revision, error, cancelRequested)
}

data class IndexJobDto(
    val jobId: String, val mode: String, val status: String, val phase: String, val percent: Int,
    val message: String, val filesProcessed: Int, val entities: Int, val relations: Int,
    val revision: String?, val error: String?, val cancelRequested: Boolean
)

fun Application.ariannaWebModule(root: Path, snapshot: Boolean = false) {
    install(ContentNegotiation) {
        jackson {
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(StatusPages) {
        exception<WebNotFound> { call, error -> call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", error.message.orEmpty())) }
        exception<WebBadRequest> { call, error -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", error.message.orEmpty())) }
        exception<WebConflict> { call, error -> call.respond(HttpStatusCode.Conflict, ErrorDto("conflict", error.message.orEmpty())) }
        exception<Throwable> { call, error -> call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal_error", error.message ?: "Request failed")) }
    }
    val context = WebContext(root, snapshot)
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok", "repository" to root.toString())) }
        post("/api/index") {
            if (context.snapshot) throw WebConflict("This is a read-only snapshot; repository indexing is unavailable")
            call.respond(HttpStatusCode.Accepted, context.jobs.start(call.request.queryParameters["mode"].orEmpty()))
        }
        get("/api/index/jobs/{jobId}") { call.respond(context.jobs.get(call.parameters["jobId"])) }
        post("/api/index/jobs/{jobId}/cancel") { call.respond(context.jobs.cancel(call.parameters["jobId"])) }
        get("/api/repository") { call.respond(WebExplorerService(context).repository()) }
        get("/api/overview") { call.respond(WebExplorerService(context).overview()) }
        get("/api/search") { call.respond(WebExplorerService(context).search(call.request.queryParameters)) }
        get("/api/entities") { call.respond(WebExplorerService(context).entity(call.request.queryParameters["id"])) }
        get("/api/entities/relationships") { call.respond(WebExplorerService(context).relationships(call.request.queryParameters)) }
        get("/api/entities/neighborhood") { call.respond(WebExplorerService(context).neighborhood(call.request.queryParameters)) }
        get("/api/documents/index") { call.respond(WebExplorerService(context).documentIndex()) }
        get("/api/documents") { call.respond(WebExplorerService(context).document(call.request.queryParameters["path"])) }
        get("/api/source") { call.respond(WebExplorerService(context).source(call.request.queryParameters)) }
        get("/api/impact") { call.respond(WebExplorerService(context).impact(call.request.queryParameters)) }
        get("/api/refactoring-plan") { call.respond(WebExplorerService(context).refactoringPlan(call.request.queryParameters)) }
        get("/api/verification") { call.respond(WebExplorerService(context).verification(call.request.queryParameters)) }
        get("/") {
            val index = (if (context.snapshot) context.root.resolve("index.html").takeIf(Files::isRegularFile)?.toFile()?.readText()
                else WebServer::class.java.classLoader.getResource("web/index.html")?.readText())
                ?: throw WebNotFound("Web Explorer assets are not available")
            call.respondText(index, ContentType.Text.Html)
        }
        staticResources("/", "web")
    }
}

private class WebExplorerService(private val context: WebContext) {
    private fun root(): Path = context.root

    fun repository(): RepositoryDto {
        val database = context.databaseFile
        val stored = if (Files.exists(database)) withStore { if (context.snapshot) it.getCurrentSnapshot() else it.getCurrentSnapshot(root().toString()) } else null
        if (context.snapshot) {
            val status = RepositoryStatus(stored?.repository ?: "snapshot", stored?.revision ?: "unknown", null, stored?.revision, emptyList(), emptyList(), emptyList(), emptyList())
            return RepositoryDto(status, emptyList(), stored?.revision, stored?.kind?.name?.lowercase(), stored != null, snapshot = true)
        }
        val source = openRepositorySource(root())
        val status = source.repositoryStatus()
        val layout = ProjectLayout.detect(root())
        return RepositoryDto(status, layout.buildSystems.map { it.name.lowercase() }, stored?.revision, stored?.kind?.name?.lowercase(), stored != null)
    }

    fun overview(): OverviewDto {
        val repository = repository()
        val database = context.databaseFile
        if (!Files.exists(database)) return emptyOverview(repository)
        return withStore { store ->
        val snapshot = (if (context.snapshot) store.getCurrentSnapshot() else store.getCurrentSnapshot(root().toString()))
            ?: return@withStore emptyOverview(repository)
        val entities = store.entitiesForSnapshot(snapshot.id)
        val relations = store.relationsForSnapshot(snapshot.id)
        val entityCounts = entities.groupingBy { it.kind }.eachCount().toSortedMap()
        val relationCounts = relations.groupingBy { it.type }.eachCount().toSortedMap()
        val moduleDependencies = relations.filter { it.type == "depends_on" }.map { relationDto(it, entities) }
        val architecture = ArchitectureAggregator.build(entities, relations)
        OverviewDto(
            repository = repository,
            entities = entityCounts,
            relations = relationCounts,
            modules = entities.filter { it.kind == "module" }.map(::entityDto),
            springComponents = entities.filter { it.kind in setOf("component", "service", "repository", "controller", "configuration") }.map(::entityDto),
            endpoints = entities.filter { it.kind == "endpoint" }.map(::entityDto),
            events = entities.filter { it.kind == "event" }.map(::entityDto),
            configurations = entities.filter { it.kind == "configuration_property" }.map(::entityDto),
            moduleDependencies = moduleDependencies,
            unresolvedRelations = relations.count { it.type == "unresolved" || it.confidence == Confidence.LOW },
            lowConfidenceRelations = relations.count { it.confidence == Confidence.LOW },
            analyzers = (entities.mapNotNull { it.evidence?.analyzerVersion } + relations.mapNotNull { it.evidence?.analyzerVersion }).distinct().sorted(),
            architecture = architecture
        )
        }
    }

    private fun emptyOverview(repository: RepositoryDto) = OverviewDto(
        repository = repository,
        entities = emptyMap(), relations = emptyMap(), modules = emptyList(), springComponents = emptyList(),
        endpoints = emptyList(), events = emptyList(), configurations = emptyList(), moduleDependencies = emptyList(),
        unresolvedRelations = 0, lowConfidenceRelations = 0, analyzers = emptyList(), architecture = ArchitectureDto(emptyList(), emptyList())
    )

    fun search(parameters: io.ktor.http.Parameters): PageDto<EntityDto> = withStore { store ->
        val scope = parameters["scope"]?.trim()?.trim('/')
        if (!scope.isNullOrEmpty()) {
            val queryTokens = parameters["q"].orEmpty().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            val kind = parameters["kind"]
            val snapshot = currentSnapshot(store)
            val scoped = store.entitiesForSnapshot(snapshot.id)
                .asSequence()
                .filter { entity ->
                    val path = entity.evidence?.file ?: return@filter false
                    (path == scope || path.startsWith("$scope/")) && (kind.isNullOrEmpty() || entity.kind == kind) &&
                        queryTokens.all { token -> entity.qualifiedName.contains(token, ignoreCase = true) || entity.content.orEmpty().contains(token, ignoreCase = true) }
                }
                .sortedWith(compareBy({ it.qualifiedName }, { it.kind }, { it.id.value }))
                .toList()
            val offset = parameters["offset"].intValue()
            val limit = parameters["limit"].boundedLimit()
            return@withStore PageDto(scoped.drop(offset).take(limit).map(::entityDto), scoped.size, offset, limit, if (offset + limit < scoped.size) offset + limit else null)
        }
        val page = KnowledgeQueryEngine(store).searchKnowledge(
            parameters["q"].orEmpty(),
            parameters["offset"].intValue(),
            parameters["limit"].boundedLimit(),
            parameters["repository"],
            parameters["file"],
            parameters["kind"]
        )
        PageDto(page.items.map(::entityDto), page.total, page.offset, page.limit, page.nextOffset)
    }

    fun entity(id: String?): EntityDetailDto = withStore { store ->
        val snapshot = currentSnapshot(store)
        val entities = store.entitiesForSnapshot(snapshot.id)
        val entity = entities.firstOrNull { it.id.value == id } ?: throw WebNotFound("Entity not found: $id")
        val relations = store.relationsForSnapshot(snapshot.id).filter { it.source == entity.id || it.target == entity.id }
        EntityDetailDto(entityDto(entity), relations.map { relationDto(it, entities) })
    }

    fun relationships(parameters: io.ktor.http.Parameters): PageDto<RelationDto> = withStore { store ->
        val page = store.findRelationsPage(
            parameters["entityId"].orEmpty(),
            parameters["offset"].intValue(),
            parameters["limit"].boundedLimit(),
            parameters["revision"],
            parameters["confidence"]
        )
        val entities = store.entitiesForSnapshot(currentSnapshot(store).id)
        PageDto(page.items.map { relationDto(it, entities) }, page.total, page.offset, page.limit, page.nextOffset)
    }

    fun neighborhood(parameters: io.ktor.http.Parameters): NeighborhoodDto = withStore { store ->
        val snapshot = currentSnapshot(store)
        val entities = store.entitiesForSnapshot(snapshot.id)
        val relations = store.relationsForSnapshot(snapshot.id)
        val center = entities.firstOrNull { it.id.value == parameters["entityId"] } ?: throw WebNotFound("Entity not found")
        val depth = parameters["depth"].intValue(default = 2).coerceIn(0, 4)
        val maxNodes = parameters["limit"].boundedLimit(default = 100)
        val selected = linkedSetOf(center.id)
        repeat(depth) {
            relations.filter { it.source in selected || it.target in selected }.forEach { relation ->
                if (selected.size < maxNodes) {
                    selected += if (relation.source in selected) relation.target else relation.source
                }
            }
        }
        val graphEntities = entities.filter { it.id in selected }
        val graphRelations = relations.filter { it.source in selected && it.target in selected }
        NeighborhoodDto(entityDto(center), graphEntities.map(::entityDto), graphRelations.map { relationDto(it, entities) }, depth)
    }

    fun document(path: String?): EntityDto = withStore { store ->
        val document = KnowledgeQueryEngine(store).getDocument(path.orEmpty()) ?: throw WebNotFound("Document not found: $path")
        entityDto(document)
    }

    fun documentIndex(): DocumentIndexDto = withStore { store ->
        val documents = store.findEntitiesPage("", 0, 10_000, kind = "document").items
            .sortedBy { it.qualifiedName }
            .map { DocumentItemDto(it.id.value, it.qualifiedName, it.evidence?.revision, it.content?.length ?: 0) }
        DocumentIndexDto(documents)
    }

    fun source(parameters: io.ktor.http.Parameters): SourcePreviewDto {
        if (context.snapshot) throw WebConflict("Source previews require the repository and are unavailable in a snapshot")
        val relative = parameters["path"]?.trim('/')?.replace('\\', '/')
            ?: throw WebBadRequest("path is required")
        val candidate = root().resolve(relative).normalize()
        val repositoryRoot = root().normalize()
        if (!candidate.startsWith(repositoryRoot) || !Files.isRegularFile(candidate)) {
            throw WebNotFound("Source file not found: $relative")
        }
        val lines = Files.readAllLines(candidate)
        val requestedLine = parameters["line"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val focusStart = parameters["startLine"]?.toIntOrNull()?.coerceAtLeast(1) ?: requestedLine
        val focusEnd = parameters["endLine"]?.toIntOrNull()?.coerceAtLeast(focusStart) ?: focusStart
        val windowStart = (focusStart - 40).coerceAtLeast(1)
        val windowEnd = (focusEnd + 40).coerceAtMost(lines.size.coerceAtLeast(1))
        val preview = (windowStart..windowEnd).mapNotNull { number ->
            lines.getOrNull(number - 1)?.let { SourceLineDto(number, it, number in focusStart..focusEnd) }
        }
        return SourcePreviewDto(relative, sourceLanguage(relative), windowStart, windowEnd, focusStart, focusEnd, preview)
    }

    fun impact(parameters: io.ktor.http.Parameters): ImpactDto = withChangeContext(parameters) { context ->
        ImpactDto(context.impact.toDto(), context.diff.toDto())
    }

    fun refactoringPlan(parameters: io.ktor.http.Parameters): RefactoringPlanDto = withChangeContext(parameters) { context ->
        RefactoringPlanner.plan(context.impact).toPlanDto()
    }

    fun verification(parameters: io.ktor.http.Parameters): VerificationDto = withChangeContext(parameters) { context ->
        ChangeVerifier.verify(context.diff, context.impact, context.baseEntities, context.overlayEntities, context.baseRelations, context.overlayRelations).toDto()
    }

    private fun <T> withStore(block: (SQLiteKnowledgeStore) -> T): T {
        val database = context.databaseFile
        if (!Files.exists(database)) throw WebNotFound("Index not found: run 'learn ${root()}'")
        return SQLiteKnowledgeStore(database).use(block)
    }

    private fun currentSnapshot(store: SQLiteKnowledgeStore): SQLiteKnowledgeStore.SnapshotInfo =
        (if (context.snapshot) store.getCurrentSnapshot() else store.getCurrentSnapshot(root().toString()))
            ?: throw WebNotFound("Baseline snapshot not found")

    private fun <T> withChangeContext(parameters: io.ktor.http.Parameters, block: (ChangeContext) -> T): T {
        if (context.snapshot) throw WebConflict("Change analysis requires a repository and is unavailable in a snapshot")
        val baseRevision = parameters["baseRevision"]
        val headRevision = parameters["headRevision"]
        if ((baseRevision == null) != (headRevision == null)) throw WebBadRequest("baseRevision and headRevision must be specified together")
        if (baseRevision != null && headRevision != null) {
            val repository = LocalGitRepository(root())
            val materialized = GitRevisionMaterializer(root()).materializePair(RevisionPair(baseRevision, headRevision))
            return try {
                val baseStore = temporaryStore()
                val headStore = temporaryStore()
                try {
                    SpringAwareIndexer().index(materialized.first, baseStore)
                    SpringAwareIndexer().index(materialized.second, headStore)
                    val baseSnapshot = baseStore.getCurrentSnapshot(materialized.first.repositoryStatus().root) ?: throw WebNotFound("Base snapshot not available")
                    val headSnapshot = headStore.getCurrentSnapshot(materialized.second.repositoryStatus().root) ?: throw WebNotFound("Head snapshot not available")
                    block(changeContext(baseStore, headStore, baseSnapshot, headSnapshot))
                } finally {
                    baseStore.close(); headStore.close()
                }
            } finally {
                materialized.first.close(); materialized.second.close()
            }
        }
        return withStore { store ->
            val baseline = currentSnapshot(store)
            val overlay = store.getLatestSnapshot(root().toString(), SnapshotKind.WORKING_TREE) ?: throw WebNotFound("Working-tree overlay not found: run 'learn index --working-tree'")
            val currentWorkingTree = openRepositorySource(root()).workingTreeRevision()
            if (currentWorkingTree != overlay.revision) throw WebConflict("Working-tree overlay is stale: reindex it")
            block(changeContext(store, store, baseline, overlay))
        }
    }

    private fun temporaryStore(): SQLiteKnowledgeStore =
        SQLiteKnowledgeStore(Files.createTempFile("arianna-web-", ".db"))

    private fun changeContext(baseStore: SQLiteKnowledgeStore, overlayStore: SQLiteKnowledgeStore, base: SQLiteKnowledgeStore.SnapshotInfo, overlay: SQLiteKnowledgeStore.SnapshotInfo): ChangeContext {
        val baseEntities = baseStore.entitiesForSnapshot(base.id)
        val overlayEntities = overlayStore.entitiesForSnapshot(overlay.id)
        val baseRelations = baseStore.relationsForSnapshot(base.id)
        val overlayRelations = overlayStore.relationsForSnapshot(overlay.id)
        val diff = SnapshotComparator.compare(base.revision, overlay.revision, baseEntities, overlayEntities, baseRelations, overlayRelations)
        return ChangeContext(diff, baseEntities, overlayEntities, baseRelations, overlayRelations, ImpactAnalyzer.analyze(diff, baseEntities, overlayEntities, baseRelations, overlayRelations))
    }

    private data class ChangeContext(
        val diff: SnapshotDiff,
        val baseEntities: List<KnowledgeEntity>,
        val overlayEntities: List<KnowledgeEntity>,
        val baseRelations: List<KnowledgeRelation>,
        val overlayRelations: List<KnowledgeRelation>,
        val impact: ImpactReport
    )
}

private fun String?.intValue(default: Int = 0): Int = this?.toIntOrNull()?.coerceAtLeast(0) ?: default
private fun String?.boundedLimit(default: Int = 50): Int = intValue(default).coerceIn(1, 200)

internal fun entityDto(entity: KnowledgeEntity) = EntityDto(entity.id.value, entity.kind, entity.qualifiedName, entity.content, evidenceDto(entity.evidence))
internal fun relationDto(relation: KnowledgeRelation, entities: List<KnowledgeEntity>) = RelationDto(
    relation.source.value, relation.type, relation.target.value, relation.origin.name.lowercase(), relation.confidence.name.lowercase(),
    entities.firstOrNull { it.id == relation.source }?.qualifiedName,
    entities.firstOrNull { it.id == relation.target }?.qualifiedName,
    evidenceDto(relation.evidence)
)
internal fun evidenceDto(evidence: Evidence?) = evidence?.let { EvidenceDto(it.repository, it.revision, it.file, it.startLine, it.endLine, it.analyzerVersion) }
private fun sourceLanguage(path: String): String = when {
    path.endsWith(".kt") -> "kotlin"
    path.endsWith(".java") -> "java"
    path.endsWith(".md") -> "markdown"
    path.endsWith(".yml") || path.endsWith(".yaml") -> "yaml"
    path.endsWith(".json") -> "json"
    path.endsWith(".properties") -> "properties"
    path.endsWith(".css") -> "css"
    path.endsWith(".js") -> "javascript"
    else -> "text"
}

data class RepositoryDto(val status: RepositoryStatus, val buildSystems: List<String>, val indexedRevision: String?, val snapshotKind: String?, val indexPresent: Boolean, val snapshot: Boolean = false)
data class ErrorDto(val code: String, val message: String)
data class EntityDto(val id: String, val kind: String, val qualifiedName: String, val content: String?, val evidence: EvidenceDto?)
data class DocumentIndexDto(val items: List<DocumentItemDto>)
data class DocumentItemDto(val id: String, val path: String, val revision: String?, val bytes: Int)
data class EvidenceDto(val repository: String, val revision: String, val file: String, val startLine: Int?, val endLine: Int?, val analyzerVersion: String)
data class RelationDto(val source: String, val type: String, val target: String, val origin: String, val confidence: String, val sourceName: String?, val targetName: String?, val evidence: EvidenceDto?)
data class PageDto<T>(val items: List<T>, val total: Int, val offset: Int, val limit: Int, val nextOffset: Int?)
data class EntityDetailDto(val entity: EntityDto, val relationships: List<RelationDto>)
data class NeighborhoodDto(val center: EntityDto, val entities: List<EntityDto>, val relationships: List<RelationDto>, val depth: Int)
data class SourcePreviewDto(val path: String, val language: String, val startLine: Int, val endLine: Int, val focusStartLine: Int, val focusEndLine: Int, val lines: List<SourceLineDto>)
data class SourceLineDto(val number: Int, val content: String, val focused: Boolean)
data class OverviewDto(
    val repository: RepositoryDto,
    val entities: Map<String, Int>,
    val relations: Map<String, Int>,
    val modules: List<EntityDto>,
    val springComponents: List<EntityDto>,
    val endpoints: List<EntityDto>,
    val events: List<EntityDto>,
    val configurations: List<EntityDto>,
    val moduleDependencies: List<RelationDto>,
    val unresolvedRelations: Int,
    val lowConfidenceRelations: Int,
    val analyzers: List<String>,
    val architecture: ArchitectureDto
)
data class ImpactDto(val report: ImpactReportDto, val diff: SnapshotDiffDto)
data class ImpactReportDto(val baseRevision: String, val overlayRevision: String, val changedEntities: List<EntityChangeDto>, val findings: List<ImpactFindingDto>, val breakingCount: Int, val possibleCount: Int)
data class EntityChangeDto(val kind: String, val entityId: String, val before: EntityDto?, val after: EntityDto?)
data class ImpactFindingDto(val severity: String, val certainty: String, val category: String, val message: String, val entityId: String?, val relation: RelationDto?, val evidence: EvidenceDto?)
data class SnapshotDiffDto(val baseRevision: String, val overlayRevision: String, val entities: List<EntityChangeDto>, val relations: List<RelationChangeDto>)
data class RelationChangeDto(val kind: String, val key: String, val before: RelationDto?, val after: RelationDto?)
data class RefactoringPlanDto(val baseRevision: String, val overlayRevision: String, val steps: List<RefactoringStepDto>, val externalVerificationRequired: Boolean, val note: String)
data class RefactoringStepDto(val order: Int, val category: String, val title: String, val actions: List<String>, val entityIds: List<String>, val findingCount: Int, val evidence: List<EvidenceDto?>)
data class VerificationDto(val baseRevision: String, val overlayRevision: String, val issues: List<VerificationIssueDto>, val externalVerificationRequired: Boolean, val note: String)
data class VerificationIssueDto(val severity: String, val certainty: String, val category: String, val message: String, val entityId: String?, val evidence: EvidenceDto?)

private fun ImpactReport.toDto(): ImpactReportDto = ImpactReportDto(baseRevision, overlayRevision, changedEntities.map { it.toDto() }, findings.map { it.toDto() }, breakingCount, possibleCount)
private fun ImpactFinding.toDto(): ImpactFindingDto = ImpactFindingDto(severity.name.lowercase(), certainty.name.lowercase(), category, message, entityId?.value, relation?.let { relationDto(it, emptyList()) }, evidenceDto(evidence))
private fun RefactoringPlan.toPlanDto(): RefactoringPlanDto = RefactoringPlanDto(baseRevision, overlayRevision, steps.map { RefactoringStepDto(it.order, it.category, it.title, it.actions, it.entityIds.map { id -> id.value }, it.findingCount, it.evidence.map(::evidenceDto)) }, externalVerificationRequired, note)
private fun SnapshotDiff.toDto(): SnapshotDiffDto = SnapshotDiffDto(baseRevision, overlayRevision, entities.map { it.toDto() }, relations.map { RelationChangeDto(it.kind.name.lowercase(), it.key, it.before?.let { relation -> relation.entityRelationDto() }, it.after?.let { relation -> relation.entityRelationDto() }) })
private fun dev.arianna.core.model.EntityChange.toDto(): EntityChangeDto = EntityChangeDto(kind.name.lowercase(), entityId.value, before?.let(::entityDto), after?.let(::entityDto))
private fun KnowledgeRelation.entityRelationDto(): RelationDto = RelationDto(source = source.value, type = type, target = target.value, origin = origin.name.lowercase(), confidence = confidence.name.lowercase(), sourceName = null, targetName = null, evidence = evidenceDto(evidence))
private fun VerificationReport.toDto(): VerificationDto = VerificationDto(baseRevision, overlayRevision, issues.map { VerificationIssueDto(it.severity.name.lowercase(), it.certainty.name.lowercase(), it.category, it.message, it.entityId?.value, evidenceDto(it.evidence)) }, externalVerificationRequired, note)

private class WebNotFound(message: String) : RuntimeException(message)
private class WebBadRequest(message: String) : RuntimeException(message)
private class WebConflict(message: String) : RuntimeException(message)
