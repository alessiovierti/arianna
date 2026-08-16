package dev.arianna.core.indexing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.arianna.core.api.IndexResult
import dev.arianna.core.api.Indexer
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.api.IndexProgressListener
import dev.arianna.core.api.NoopIndexProgressListener
import dev.arianna.core.api.Source
import dev.arianna.core.api.Store
import dev.arianna.core.api.SnapshotStore
import dev.arianna.core.error.IndexingException
import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.source.BuildSystem
import dev.arianna.core.source.ProjectLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicReference

data class ExternalCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

fun interface ExternalCommandRunner {
    fun run(directory: Path, executable: String, arguments: List<String>, timeout: Duration): ExternalCommandResult
}

class ProcessExternalCommandRunner : ExternalCommandRunner {
    override fun run(
        directory: Path,
        executable: String,
        arguments: List<String>,
        timeout: Duration
    ): ExternalCommandResult {
        val process = try {
            ProcessBuilder(listOf(executable) + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(false)
                .apply {
                    // scip-java injects temporary Gradle tasks. Gradle's configuration
                    // cache can evaluate that init script without the generated extras,
                    // causing a false `dependenciesOut` failure. Do not alter the user's
                    // project; disable the cache only for this child process.
                    if (executable.substringAfterLast('/').equals("scip-java", ignoreCase = true)) {
                        val existing = environment()["GRADLE_OPTS"].orEmpty()
                        environment()["GRADLE_OPTS"] = "$existing -Dorg.gradle.configuration-cache=false".trim()
                    }
                }
                .start()
        } catch (error: Exception) {
            throw IndexingException("Unable to start $executable: ${error.message}", error)
        }

        val stdoutRef = AtomicReference("")
        val stderrRef = AtomicReference("")
        val stdoutThread = thread(start = true, isDaemon = true) {
            stdoutRef.set(process.inputStream.bufferedReader().use { it.readText() })
        }
        val stderrThread = thread(start = true, isDaemon = true) {
            stderrRef.set(process.errorStream.bufferedReader().use { it.readText() })
        }
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IndexingException("Timeout while running $executable ${arguments.joinToString(" ")}")
        }
        stdoutThread.join()
        stderrThread.join()
        return ExternalCommandResult(stdoutRef.get(), stderrRef.get(), process.exitValue())
    }
}

data class ScipIndexerConfig(
    val scipJavaExecutable: String = "scip-java",
    val scipExecutable: String = "scip",
    val indexFileName: String = "index.scip",
    val timeout: Duration = Duration.ofMinutes(10),
    val analyzerVersion: String = "scip-java-adapter-0.1"
)

data class ScipToolchainStatus(
    val scipJavaAvailable: Boolean,
    val scipAvailable: Boolean,
    val diagnostics: List<String>,
    val buildToolAvailable: Boolean = true
) {
    val ready: Boolean
        get() = scipJavaAvailable && scipAvailable && buildToolAvailable
}

class ScipPreflight(
    private val commandRunner: ExternalCommandRunner = ProcessExternalCommandRunner()
) {
    fun check(directory: Path, config: ScipIndexerConfig = ScipIndexerConfig()): ScipToolchainStatus {
        val diagnostics = mutableListOf<String>()
        val buildRoot = ProjectLayout.scipBuildRoot(directory)
        val javaAvailable = checkExecutable(buildRoot, config.scipJavaExecutable, config.timeout, diagnostics)
        val scipAvailable = checkExecutable(buildRoot, config.scipExecutable, config.timeout, diagnostics)
        val buildToolAvailable = checkBuildTools(buildRoot, config.timeout, diagnostics)
        return ScipToolchainStatus(javaAvailable, scipAvailable, diagnostics, buildToolAvailable)
    }

    private fun checkBuildTools(directory: Path, timeout: Duration, diagnostics: MutableList<String>): Boolean {
        val buildRoot = ProjectLayout.scipBuildRoot(directory)
        val layout = ProjectLayout.detect(buildRoot)
        if (!layout.isJvmProject) return true

        return layout.buildSystems.all { buildSystem ->
            val executable = when (buildSystem) {
                BuildSystem.MAVEN -> if (Files.exists(buildRoot.resolve("mvnw"))) "./mvnw" else "mvn"
                BuildSystem.GRADLE -> if (Files.exists(buildRoot.resolve("gradlew"))) "./gradlew" else "gradle"
            }
            checkExecutable(buildRoot, executable, timeout, diagnostics)
        }
    }

    private fun checkExecutable(directory: Path, executable: String, timeout: Duration, diagnostics: MutableList<String>): Boolean {
        val versionProbe = runCatching { commandRunner.run(directory, executable, listOf("--version"), timeout) }
        if (versionProbe.getOrNull()?.exitCode == 0) return true

        // Some valid launchers expose --help but not --version (notably older scip-java distributions).
        val helpProbe = runCatching { commandRunner.run(directory, executable, listOf("--help"), timeout) }
        if (helpProbe.getOrNull()?.exitCode == 0) return true

        fun detail(probe: Result<ExternalCommandResult>): String = probe.fold(
            onSuccess = { result -> result.stderr.trim().ifEmpty { "exit code ${result.exitCode}" } },
            onFailure = { error -> error.message ?: error::class.simpleName.orEmpty() }
        )
        diagnostics += "$executable is unavailable: --version: ${detail(versionProbe)}; --help: ${detail(helpProbe)}"
        return false
    }
}

class ScipJsonParser(
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    data class ParsedIndex(
        val entities: List<KnowledgeEntity>,
        val relations: List<KnowledgeRelation>,
        val fileCount: Int
    )

    fun parse(
        json: String,
        repository: String,
        revision: String,
        analyzerVersion: String,
        pathPrefix: String = ""
    ): ParsedIndex {
        val root = try {
            objectMapper.readTree(json)
        } catch (error: Exception) {
            throw IndexingException("Invalid SCIP JSON output", error)
        }
        val entities = linkedMapOf<String, KnowledgeEntity>()
        val relations = linkedMapOf<String, KnowledgeRelation>()
        val documents = root.path("documents")
        if (!documents.isArray) {
            throw IndexingException("Output SCIP incompleto: campo documents assente o non valido")
        }

        documents.forEach { document ->
            val scipPath = document.path("relativePath").asText("")
            if (scipPath.isEmpty()) return@forEach
            val relativePath = listOf(pathPrefix.trim('/'), scipPath.trimStart('/'))
                .filter { it.isNotEmpty() }
                .joinToString("/")
            val fileEvidence = Evidence(repository, revision, relativePath, analyzerVersion = analyzerVersion)
            val fileId = EntityId("file:$relativePath")
            entities[fileId.value] = KnowledgeEntity(fileId, "file", relativePath, fileEvidence)

            val symbolNames = document.path("symbols")
                .mapNotNull { it.path("symbol").asText(null) }
                .toSet()
            val definitionSpans = document.path("occurrences")
                .mapNotNull { occurrence ->
                    if (occurrence.path("symbolRoles").asInt(0) and DEFINITION_ROLE == 0) return@mapNotNull null
                    occurrenceSpan(occurrence)?.let { span ->
                        DefinitionSpan(occurrence.path("symbol").asText(""), span.first, span.last)
                    }
                }
                .filter { it.symbol.isNotEmpty() }

            document.path("symbols").forEach { symbolInformation ->
                val sourceSymbol = symbolInformation.path("symbol").asText("")
                if (sourceSymbol.isEmpty()) return@forEach
                val sourceId = EntityId("scip:$sourceSymbol")
                putDefinition(
                    entities,
                    KnowledgeEntity(sourceId, symbolKind(document, sourceSymbol), sourceSymbol, fileEvidence)
                )
                val definitionRelation = KnowledgeRelation(
                    source = fileId,
                    type = "defines",
                    target = sourceId,
                    origin = Origin.STATIC,
                    confidence = Confidence.HIGH,
                    evidence = fileEvidence
                )
                relations["${definitionRelation.source.value}|${definitionRelation.type}|${definitionRelation.target.value}|${relativePath}"] = definitionRelation
                symbolInformation.path("relationships").forEach { relationship ->
                    val targetSymbol = relationship.path("symbol").asText("")
                    if (targetSymbol.isEmpty()) return@forEach
                    val targetId = EntityId("scip:$targetSymbol")
                    entities.putIfAbsent(targetId.value, KnowledgeEntity(targetId, "external_symbol", targetSymbol, fileEvidence))
                    val relationType = when {
                        relationship.path("isImplementation").asBoolean(false) -> "implements"
                        relationship.path("isReference").asBoolean(false) -> "references"
                        else -> "references"
                    }
                    val relation = KnowledgeRelation(
                        source = sourceId,
                        type = relationType,
                        target = targetId,
                        origin = Origin.STATIC,
                        confidence = Confidence.HIGH,
                        evidence = fileEvidence
                    )
                    relations["${relation.source.value}|${relation.type}|${relation.target.value}|${relativePath}"] = relation
                }
            }

            document.path("occurrences").forEach { occurrence ->
                val symbol = occurrence.path("symbol").asText("")
                if (symbol.isEmpty()) return@forEach
                val evidence = fileEvidence.copy(
                    startLine = occurrence.path("range").firstOrNull()?.asInt()?.plus(1),
                    endLine = occurrence.path("range").firstOrNull()?.asInt()?.plus(1)
                )
                val isDefinition = occurrence.path("symbolRoles").asInt(0) and DEFINITION_ROLE != 0
                if (isDefinition || symbol in symbolNames) {
                    val entityId = EntityId("scip:$symbol")
                    putDefinition(
                        entities,
                        KnowledgeEntity(entityId, symbolKind(document, symbol), symbol, evidence)
                    )
                }
                if (!isDefinition) {
                    val targetId = EntityId("scip:$symbol")
                    entities.putIfAbsent(targetId.value, KnowledgeEntity(targetId, "external_symbol", symbol, evidence))
                    val sourceId = occurrenceSpan(occurrence)?.let { occurrenceSpan ->
                        definitionSpans
                            .filter { definition ->
                                definition.startLine <= occurrenceSpan.first && definition.endLine >= occurrenceSpan.last
                            }
                            .minWithOrNull(compareBy<DefinitionSpan>({ it.endLine - it.startLine }, { it.startLine }))
                            ?.let { EntityId("scip:${it.symbol}") }
                    } ?: fileId
                    val relation = KnowledgeRelation(
                        source = sourceId,
                        type = "references",
                        target = targetId,
                        origin = Origin.STATIC,
                        confidence = Confidence.HIGH,
                        evidence = evidence
                    )
                    relations["${relation.source.value}|${relation.type}|${relation.target.value}|${relativePath}:${evidence.startLine}"] = relation
                }
            }
        }

        return ParsedIndex(entities.values.toList(), relations.values.toList(), documents.size())
    }

    private fun symbolKind(document: JsonNode, symbol: String): String {
        return document.path("symbols").firstOrNull { it.path("symbol").asText("") == symbol }
            ?.path("kind")?.asText("symbol")?.lowercase()
            ?: "symbol"
    }

    private fun putDefinition(
        entities: MutableMap<String, KnowledgeEntity>,
        definition: KnowledgeEntity
    ) {
        val existing = entities[definition.id.value]
        if (existing == null || existing.kind == "external_symbol") {
            entities[definition.id.value] = definition
        }
    }

    private fun occurrenceSpan(occurrence: JsonNode): IntRange? {
        val range = occurrence.path("range")
        if (!range.isArray || range.size() == 0) return null
        val startLine = range[0].asInt(-1)
        if (startLine < 0) return null
        val endLine = if (range.size() >= 4) range[2].asInt(startLine) else startLine
        return startLine..maxOf(startLine, endLine)
    }

    private data class DefinitionSpan(
        val symbol: String,
        val startLine: Int,
        val endLine: Int
    )

    companion object {
        private const val DEFINITION_ROLE = 1
    }
}

class ScipIndexer(
    private val config: ScipIndexerConfig = ScipIndexerConfig(),
    private val commandRunner: ExternalCommandRunner = ProcessExternalCommandRunner(),
    private val parser: ScipJsonParser = ScipJsonParser()
) : Indexer {
    override fun index(source: Source, store: Store): IndexResult {
        return indexSnapshot(source, store, overlay = false, progress = NoopIndexProgressListener)
    }

    fun index(source: Source, store: Store, progress: IndexProgressListener): IndexResult {
        return indexSnapshot(source, store, overlay = false, progress)
    }

    fun indexOverlay(source: Source, store: Store, progress: IndexProgressListener = NoopIndexProgressListener): IndexResult {
        return indexSnapshot(source, store, overlay = true, progress)
    }

    private fun indexSnapshot(source: Source, store: Store, overlay: Boolean, progress: IndexProgressListener): IndexResult {
        val status = source.repositoryStatus()
        val root = Path.of(status.root)
        val buildRoot = ProjectLayout.scipBuildRoot(root)
        val pathPrefix = root.relativize(buildRoot).toString()
            .replace(buildRoot.fileSystem.getSeparator(), "/")
        val totalStages = 5
        progress.onProgress(IndexProgress("preflight", 0, totalStages, "Checking SCIP toolchain"))
        val toolchain = ScipPreflight(commandRunner).check(root, config)
        if (!toolchain.ready) {
            throw IndexingException(
                "Prerequisiti SCIP non disponibili: ${toolchain.diagnostics.joinToString("; ")}" 
            )
        }
        progress.onProgress(IndexProgress("preflight", 1, totalStages, "SCIP toolchain ready"))
        val revision = if (overlay) source.workingTreeRevision() ?: "WORKING_TREE:${status.head}" else status.head
        progress.onProgress(IndexProgress("scip", 1, totalStages, "Generating SCIP index"))
        val json = withGeneratedIndex(buildRoot) {
            runCommand(
                buildRoot,
                config.scipJavaExecutable,
                listOf("index", "--output", config.indexFileName),
                "generare ${config.indexFileName}"
            )
            runCommand(
                buildRoot,
                config.scipExecutable,
                listOf("print", "--json", config.indexFileName),
                "leggere index.scip"
            )
        }
        progress.onProgress(IndexProgress("scip", 2, totalStages, "Parsing SCIP symbols"))
        val parsed = parser.parse(json, status.root, revision, config.analyzerVersion, pathPrefix)
        progress.onProgress(IndexProgress("documents", 3, totalStages, "Indexing documents"))
        val documents = DocumentIndexer.analyze(root, status.root, revision).entities
        progress.onProgress(IndexProgress("relations", 3, totalStages, "Linking documents to SCIP entities"))
        val allEntities = parsed.entities + documents
        val documentRelations = DocumentLinker.link(documents, allEntities)
        val composeAnalysis = ComposeArchitectureAdapter.analyze(root, status.root, revision)
        val entitiesWithRuntime = allEntities + composeAnalysis.entities
        val snapshotStore = store as? SnapshotStore
            ?: throw IndexingException("L’adapter SCIP richiede uno SnapshotStore")
        val relations = parsed.relations + composeAnalysis.relations + documentRelations
        progress.onProgress(IndexProgress("publish", 4, totalStages, "Publishing snapshot"))
        if (overlay) {
            snapshotStore.replaceOverlaySnapshot(
                status.root,
                revision,
                entitiesWithRuntime.asSequence(),
                relations.asSequence()
            )
        } else {
            snapshotStore.replaceSnapshot(
                status.root,
                revision,
                entitiesWithRuntime.asSequence(),
                relations.asSequence()
            )
        }
        progress.onProgress(IndexProgress("publish", 5, totalStages, "Index complete"))
        return IndexResult(parsed.fileCount, entitiesWithRuntime.size, relations.size, revision)
    }

    private fun runCommand(directory: Path, executable: String, arguments: List<String>, action: String): String {
        val result = commandRunner.run(directory, executable, arguments, config.timeout)
        if (result.exitCode != 0) {
            val detail = result.stderr.trim().ifEmpty { "exit code ${result.exitCode}" }
            throw IndexingException("Unable to $action: $detail")
        }
        return result.stdout
    }

    private fun withGeneratedIndex(root: Path, action: () -> String): String {
        val indexPath = root.resolve(config.indexFileName).normalize()
        val hadExistingIndex = Files.isRegularFile(indexPath)
        val previousContent = if (hadExistingIndex) Files.readAllBytes(indexPath) else null
        if (Files.exists(indexPath) && !hadExistingIndex) {
            throw IndexingException("The SCIP index path is not a regular file: $indexPath")
        }
        return try {
            action()
        } finally {
            if (hadExistingIndex) {
                Files.write(indexPath, requireNotNull(previousContent))
            } else if (Files.isRegularFile(indexPath)) {
                Files.deleteIfExists(indexPath)
            }
        }
    }
}
