package dev.arianna.cli

import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.error.AriannaException
import dev.arianna.core.error.IndexingException
import dev.arianna.core.config.AppConfig
import dev.arianna.core.indexing.ScipIndexer
import dev.arianna.core.indexing.ScipPreflight
import dev.arianna.core.logging.ConsoleLogger
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Page
import dev.arianna.core.model.SnapshotDiff
import dev.arianna.core.model.SnapshotChangeKind
import dev.arianna.core.model.SnapshotKind
import dev.arianna.core.indexing.SnapshotComparator
import dev.arianna.core.indexing.ImpactAnalyzer
import dev.arianna.core.indexing.RefactoringPlanner
import dev.arianna.core.indexing.ChangeVerifier
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.RefactoringPlan
import dev.arianna.core.model.VerificationReport
import dev.arianna.core.model.Evidence
import dev.arianna.core.query.KnowledgeQueryEngine
import dev.arianna.core.source.LocalGitRepository
import dev.arianna.core.source.RepositoryPathFilter
import dev.arianna.core.source.openRepositorySource
import dev.arianna.core.api.Source
import dev.arianna.core.source.GitRevisionMaterializer
import dev.arianna.core.model.RevisionPair
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.storage.SQLiteKnowledgeStore
import dev.arianna.mcp.McpServer
import dev.arianna.benchmark.BenchmarkRunner
import dev.arianna.benchmark.DirectRepositoryBaselineRunner
import dev.arianna.benchmark.BenchmarkComparisonRunner
import dev.arianna.benchmark.ValidationGateEvaluator
import dev.arianna.benchmark.ValidationObservationFile
import dev.arianna.web.WebServer
import dev.arianna.web.WebServerConfig
import dev.arianna.web.SnapshotExporter
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.api.IndexProgressListener
import dev.arianna.core.api.NoopIndexProgressListener
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.io.path.absolute

private const val VERSION = "0.1.0-SNAPSHOT"
private val logger = ConsoleLogger()
fun main(args: Array<String>) {
    val exitCode = try {
        runCommand(args)
        0
    } catch (error: CliException) {
        logger.error("Error: ${error.message}")
        2
    } catch (error: AriannaException) {
        logger.error("Error: ${error.message}")
        2
    } catch (error: Exception) {
        logger.error("Internal error: ${error.message ?: error::class.simpleName}")
        1
    }

    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

private fun runCommand(args: Array<String>) {
    when {
        args.isEmpty() || args[0] in setOf("help", "--help", "-h") -> printHelp()
        args[0] in setOf("--version", "-v", "version") -> println("Arianna $VERSION")
        args[0] == "mcp" -> McpServer(repositoryPath(args.drop(1))).run(System.`in`, System.out)
        args[0] == "serve" -> serveWeb(
            serveRepositoryPath(args.drop(1)),
            host = queryStringOption(args.drop(1), "--host") ?: "127.0.0.1",
            port = queryStringOption(args.drop(1), "--port")?.toIntOrNull() ?: 8080
        )
        args[0] == "export" -> exportSnapshot(repositoryPath(args.drop(1)), snapshotOutput(args.drop(1)))
        args[0] == "snapshot" -> serveSnapshot(snapshotArchive(args.drop(1)), queryStringOption(args.drop(1), "--host") ?: "127.0.0.1", queryStringOption(args.drop(1), "--port")?.toIntOrNull() ?: 8080)
        args[0] == "benchmark" -> printBenchmark(repositoryPath(args.drop(1)), json = args.contains("--json"), baseline = args.contains("--baseline"), compare = args.contains("--compare"))
        args[0] == "validate" -> printValidation(queryStringOption(args.drop(1), "--observations")?.let(Path::of) ?: throw CliException("specify --observations <file.json>"), json = args.contains("--json"))
        args[0] == "preflight" -> printPreflight(repositoryPath(args.drop(1)), json = args.contains("--json"))
        args[0] == "status" -> printStatus(repositoryPath(args.drop(1)), json = args.contains("--json"))
        args[0] == "index" -> printIndex(repositoryPath(args.drop(1)), json = args.contains("--json"), scip = args.contains("--scip"), spring = args.contains("--spring"), workingTree = args.contains("--working-tree"))
        args[0] == "diff" -> printDiff(
            diffRepositoryPath(args.drop(1)),
            json = args.contains("--json"),
            workingTree = args.contains("--working-tree"),
            baseRevision = queryStringOption(args.drop(1), "--base"),
            headRevision = queryStringOption(args.drop(1), "--head")
        )
        args[0] == "impact" -> printImpact(
            diffRepositoryPath(args.drop(1)),
            json = args.contains("--json"),
            workingTree = args.contains("--working-tree"),
            baseRevision = queryStringOption(args.drop(1), "--base"),
            headRevision = queryStringOption(args.drop(1), "--head")
        )
        args[0] in setOf("plan-refactor", "plan_refactor") -> printPlanRefactor(
            diffRepositoryPath(args.drop(1)),
            json = args.contains("--json"),
            workingTree = args.contains("--working-tree"),
            baseRevision = queryStringOption(args.drop(1), "--base"),
            headRevision = queryStringOption(args.drop(1), "--head")
        )
        args[0] in setOf("verify-change", "verify_change") -> printVerifyChange(
            diffRepositoryPath(args.drop(1)),
            json = args.contains("--json"),
            workingTree = args.contains("--working-tree"),
            baseRevision = queryStringOption(args.drop(1), "--base"),
            headRevision = queryStringOption(args.drop(1), "--head")
        )
        args[0] == "search" -> printSearch(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)))
        args[0] in setOf("search-knowledge", "search_knowledge") -> printSearchKnowledge(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)), repository = queryStringOption(args.drop(1), "--repository"), file = queryStringOption(args.drop(1), "--file"), kind = queryStringOption(args.drop(1), "--kind"))
        args[0] in setOf("get-document", "get_document") -> printDocument(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"))
        args[0] == "find-symbol" -> printSearch(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)))
        args[0] == "references" -> printRelations(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), implementations = false, limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)), revision = queryStringOption(args.drop(1), "--revision"), confidence = queryStringOption(args.drop(1), "--confidence"))
        args[0] == "implementations" -> printRelations(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), implementations = true, limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)), revision = queryStringOption(args.drop(1), "--revision"), confidence = queryStringOption(args.drop(1), "--confidence"))
        args[0] == "relations" -> printDirectRelations(queryArgument(args.drop(1)), queryPath(args.drop(1)), json = args.contains("--json"), limit = queryLimit(args.drop(1)), offset = queryOffset(args.drop(1)), revision = queryStringOption(args.drop(1), "--revision"), confidence = queryStringOption(args.drop(1), "--confidence"))
        isRepositoryPathArgument(args[0]) -> printIndex(
            repositoryPath(args.toList()),
            json = args.contains("--json"),
            scip = args.contains("--scip"),
            spring = args.contains("--spring"),
            workingTree = args.contains("--working-tree")
        )
        else -> throw CliException("unknown command '${args[0]}'. Use --help.")
    }
}

private fun serveWeb(path: Path, host: String, port: Int) {
    require(port in 1..65535) { "port must be between 1 and 65535" }
    println("Arianna Web Explorer: http://$host:$port")
    WebServer(path, WebServerConfig(host, port)).start()
}

private fun exportSnapshot(repository: Path, output: Path) {
    SnapshotExporter.export(repository, output)
    println("Snapshot exported: ${output.toAbsolutePath().normalize()}")
}

private fun serveSnapshot(archive: Path, host: String, port: Int) {
    require(port in 1..65535) { "port must be between 1 and 65535" }
    require(Files.isRegularFile(archive)) { "snapshot archive not found: $archive" }
    val root = Files.createTempDirectory("arianna-snapshot-")
    try {
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = root.resolve(entry.name).normalize()
                require(target.parent == root && entry.name in setOf("index.html", "knowledge.db")) { "invalid snapshot archive entry: ${entry.name}" }
                if (!entry.isDirectory) Files.newOutputStream(target).use { zip.copyTo(it) }
            }
        }
        require(Files.isRegularFile(root.resolve("index.html")) && Files.isRegularFile(root.resolve("knowledge.db"))) { "snapshot must contain index.html and knowledge.db" }
        println("Arianna Snapshot Explorer: http://$host:$port")
        WebServer(root, WebServerConfig(host, port), snapshot = true).start()
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun snapshotOutput(arguments: List<String>): Path =
    queryStringOption(arguments, "--output")?.let(Path::of) ?: Path.of("arianna-snapshot.zip")

private fun snapshotArchive(arguments: List<String>): Path =
    arguments.firstOrNull { !it.startsWith("--") && it !in setOf(queryStringOption(arguments, "--host"), queryStringOption(arguments, "--port")) }
        ?.let(Path::of) ?: throw CliException("specify a snapshot archive (.zip)")

private fun isRepositoryPathArgument(argument: String): Boolean =
    argument == "." || argument == ".." ||
        argument.startsWith("./") || argument.startsWith("../") ||
        argument.startsWith("/")

private fun repositoryPath(arguments: List<String>): Path {
    val explicitPath = arguments.indexOf("--path")
        .takeIf { it >= 0 }
        ?.let { arguments.getOrNull(it + 1) }
    val valueOptions = setOf("--host", "--port", "--path", "--base", "--head", "--revision", "--confidence", "--kind", "--file", "--repository", "--limit", "--offset")
    val pathArgument = explicitPath ?: run {
        var index = 0
        var found: String? = null
        while (index < arguments.size && found == null) {
            val argument = arguments[index]
            when {
                argument in valueOptions -> index += 2
                argument.startsWith("--") -> index++
                else -> found = argument
            }
        }
        found ?: "."
    }
    return Path.of(pathArgument).absolute().normalize()
}

private fun serveRepositoryPath(arguments: List<String>): Path = repositoryPath(arguments)

private fun printStatus(path: Path, json: Boolean) {
    val status = openRepositorySource(path).repositoryStatus()
    val layout = dev.arianna.core.source.ProjectLayout.detect(Path.of(status.root))
    val config = AppConfig.forRepository(Path.of(status.root))
    val indexedRevision = if (config.databaseFile.toFile().exists()) {
        SQLiteKnowledgeStore(config.databaseFile).use { it.currentRevision(status.root) }
    } else {
        null
    }
    val statusWithIndex = status.copy(indexedRevision = indexedRevision)
    if (json) {
        println(statusWithIndex.toJson(layout))
    } else {
        println("Repository: ${status.root}")
        println("HEAD:       ${status.head}")
        println("Branch:     ${status.branch ?: "detached"}")
        println("Build:      ${layout.buildSystems.joinToString().ifEmpty { "not detected" }}")
        println("Indexed:    ${indexedRevision ?: "never"}")
        println("Working tree:")
        println("  staged:    ${status.stagedFiles.size}")
        println("  modified:  ${status.modifiedFiles.size}")
        println("  untracked: ${status.untrackedFiles.size}")
        println("  deleted:   ${status.deletedFiles.size}")
    }
}

private fun printValidation(path: Path, json: Boolean) {
    if (!path.toFile().exists()) throw CliException("observation file not found: $path")
    val report = ValidationGateEvaluator.evaluate(ValidationObservationFile.read(path))
    if (json) {
        println(ObjectMapper().writeValueAsString(report))
    } else {
        println("Validation gate: ${if (report.passed) "PASSED" else "NOT PASSED"}")
        report.criteria.forEach { (criterion, passed) -> println("  ${if (passed) "✓" else "✗"} $criterion") }
        if (report.missingTasks.isNotEmpty()) println("  missing tasks: ${report.missingTasks.sorted().joinToString()}")
    }
}

private fun printPreflight(path: Path, json: Boolean) {
    val root = path.toAbsolutePath().normalize()
    if (!root.toFile().isDirectory) throw CliException("repository directory not found: $root")
    val status = ScipPreflight().check(root)
    if (json) {
        val diagnostics = status.diagnostics.joinToString(prefix = "[", postfix = "]") { quoteJson(it) }
        println(
            "{\"ready\":${status.ready}," +
                "\"scipJavaAvailable\":${status.scipJavaAvailable}," +
                "\"scipAvailable\":${status.scipAvailable}," +
                "\"buildToolAvailable\":${status.buildToolAvailable}," +
                "\"diagnostics\":$diagnostics}"
        )
    } else {
        println("SCIP preflight: ${if (status.ready) "READY" else "NOT READY"}")
        println("  scip-java: ${if (status.scipJavaAvailable) "available" else "missing"}")
        println("  scip:      ${if (status.scipAvailable) "available" else "missing"}")
        println("  build:     ${if (status.buildToolAvailable) "available or not required" else "missing"}")
        status.diagnostics.forEach { println("  diagnostic: $it") }
    }
}

private fun printBenchmark(path: Path, json: Boolean, baseline: Boolean, compare: Boolean) {
    if (baseline && compare) throw CliException("specify only one mode between --baseline and --compare")
    if (compare) {
        val result = BenchmarkComparisonRunner().run(path)
        if (json) println(result.toJson()) else {
            println("Benchmark comparison: ${result.repository}")
            println("  direct repository:")
            result.directRepository.queryMetrics.forEach { println("    ${it.query}: ${it.matchedFiles} files / ${it.matchedLines} lines in ${"%.2f".format(it.latencyMillis)} ms") }
            println("  direct tasks:")
            result.directRepository.taskMetrics.forEach { println("    ${it.taskId}: ${it.matchedFiles} files / ${it.matchedLines} lines in ${"%.2f".format(it.latencyMillis)} ms") }
            println("  Arianna:")
            result.arianna.queryMetrics.forEach { println("    ${it.query}: ${it.resultCount} results in ${"%.2f".format(it.latencyMillis)} ms") }
            println("    update: ${"%.2f".format(result.arianna.updateMillis)} ms")
            println("    impact: ${"%.2f".format(result.arianna.impactMillis)} ms (${result.arianna.impactFindingCount} findings)")
            println("  Arianna tasks:")
            result.arianna.taskMetrics.forEach { println("    ${it.taskId}: ${it.resultCount} results in ${"%.2f".format(it.latencyMillis)} ms") }
            println("  Arianna quality:")
            result.arianna.qualityMetrics.forEach { println("    ${it.taskId}: precision=${"%.2f".format(it.precision)} recall=${"%.2f".format(it.recall)} missing=${it.missing.size} false-positive=${it.falsePositives.size}") }
            println("  note: ${result.note}")
        }
        return
    }
    if (baseline) {
        val result = DirectRepositoryBaselineRunner().run(path)
        if (json) println(result.toJson()) else {
            println("Direct repository baseline: ${result.repository}")
            result.queryMetrics.forEach { println("  query ${it.query}: ${it.matchedFiles} files / ${it.matchedLines} lines in ${"%.2f".format(it.latencyMillis)} ms") }
        }
        return
    }
    val result = BenchmarkRunner().run(path)
    if (json) {
        println(result.toJson())
    } else {
        println("Benchmark: ${result.repository}")
        println("  index:    ${"%.2f".format(result.indexMillis)} ms")
        println("  files:    ${result.indexedFiles}")
        println("  entities: ${result.indexedEntities}")
        println("  relations:${result.indexedRelations}")
        println("  index:    ${result.indexBytes} bytes")
        println("  update:   ${"%.2f".format(result.updateMillis)} ms")
        println("  impact:   ${"%.2f".format(result.impactMillis)} ms (${result.impactFindingCount} findings)")
        result.queryMetrics.forEach { println("  query ${it.query}: ${it.resultCount} results in ${"%.2f".format(it.latencyMillis)} ms") }
        result.qualityMetrics.forEach { println("  quality ${it.taskId}: precision=${"%.2f".format(it.precision)} recall=${"%.2f".format(it.recall)} missing=${it.missing.size} false-positive=${it.falsePositives.size}") }
    }
}

private fun printIndex(path: Path, json: Boolean, scip: Boolean, spring: Boolean, workingTree: Boolean) {
    val repository = openRepositorySource(path)
    ensureBaselineIndexAllowed(repository, workingTree)
    val root = Path.of(repository.repositoryStatus().root)
    val config = AppConfig.forRepository(root).ensureDataDirectory()
    RepositoryPathFilter.ensureIgnoreFile(root)
    SQLiteKnowledgeStore(config.databaseFile).use { store ->
        if (workingTree) ensureBaselineSnapshotForOverlay(store, root)
        val terminalProgress = if (json) null else TerminalIndexProgress()
        val progress = terminalProgress ?: NoopIndexProgressListener
        val result = try {
            if (scip && spring) {
                throw CliException("specify only one indexer between --scip and --spring")
            }

            try {
                when {
                    workingTree && scip -> ScipIndexer().indexOverlay(repository, store, progress)
                    workingTree && spring -> SpringAwareIndexer().indexOverlay(repository, store, progress)
                    workingTree -> SpringAwareIndexer().indexOverlay(repository, store, progress)
                    scip -> ScipIndexer().index(repository, store, progress)
                    spring -> SpringAwareIndexer().index(repository, store, progress)
                    else -> SpringAwareIndexer().index(repository, store, progress)
                }
            } catch (error: IndexingException) {
                if (!scip) throw error

                val detail = error.message ?: "unspecified error"
                val mode = if (workingTree) "working-tree" else "baseline"
                System.err.println("SCIP unavailable ($mode): $detail")
                System.err.println("Continuing with Arianna's local JVM/Spring indexer.")
                if (workingTree) {
                    SpringAwareIndexer().indexOverlay(repository, store, progress)
                } else {
                    SpringAwareIndexer().index(repository, store, progress)
                }
            }
        } finally {
            terminalProgress?.finish()
        }
        if (json) {
            println("{\"revision\":\"${result.revision}\",\"indexedFiles\":${result.indexedFiles},\"indexedEntities\":${result.indexedEntities},\"indexedRelations\":${result.indexedRelations}}")
        } else {
            println("Index updated")
            println("  revision: ${result.revision}")
            println("  files:    ${result.indexedFiles}")
            println("  entities: ${result.indexedEntities}")
            println("  relations:${result.indexedRelations}")
        }
    }
}

internal class TerminalIndexProgress(
    private val output: PrintStream = System.out,
    private val nowNanos: () -> Long = System::nanoTime,
    private val heartbeatMillis: Long = 1_000
) : IndexProgressListener {
    private val lock = Any()
    private var startedAtNanos: Long? = null
    private var latest: IndexProgress? = null
    private var stopped = false
    private var heartbeat: Thread? = null
    private val terminalWidth = System.getenv("COLUMNS")?.toIntOrNull()?.coerceIn(60, 240) ?: 80

    override fun onProgress(progress: IndexProgress) {
        synchronized(lock) {
            if (startedAtNanos == null) {
                startedAtNanos = nowNanos()
                heartbeat = Thread({ heartbeatLoop() }, "arianna-index-progress").apply {
                    isDaemon = true
                    start()
                }
            }
            latest = progress
            render(progress, spinner = 0)
            if (progress.percent >= 100) stopLocked()
        }
    }

    fun finish() {
        val thread = synchronized(lock) {
            if (!stopped) {
                stopped = true
                output.println()
                output.flush()
            }
            heartbeat
        }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) thread.join(250)
    }

    private fun heartbeatLoop() {
        var spinner = 1
        while (true) {
            try {
                Thread.sleep(heartbeatMillis)
            } catch (_: InterruptedException) {
                return
            }
            synchronized(lock) {
                if (stopped) return
                latest?.let { render(it, spinner++) }
            }
        }
    }

    private fun render(progress: IndexProgress, spinner: Int) {
        // Reserve space for the phase description on narrow terminals. The bar
        // remains useful, but the operation being performed is more important.
        val width = ((terminalWidth - 68) / 2).coerceIn(12, 28)
        val filled = width * progress.percent / 100
        val bar = "#".repeat(filled) + "-".repeat(width - filled)
        val elapsed = formatElapsed(((nowNanos() - (startedAtNanos ?: nowNanos())) / 1_000_000_000).coerceAtLeast(0))
        val activity = if (progress.percent >= 100) "complete" else "working ${"|/-\\"[spinner % 4]}"
        val prefix = "Indexing [$bar] ${progress.percent.toString().padStart(3)}% [${progress.stage}] "
        val suffix = " • $elapsed • $activity"
        val messageWidth = (terminalWidth - prefix.length - suffix.length).coerceAtLeast(8)
        val line = prefix + progress.message.take(messageWidth) + suffix
        // Do not pad to an arbitrary width: padding can make the line wrap before
        // the carriage return gets a chance to replace it in narrow terminals.
        output.print("\u001B[2K\r$line")
        output.flush()
    }

    private fun stopLocked() {
        stopped = true
        output.println()
        output.flush()
    }
}

internal fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun ensureBaselineSnapshotForOverlay(store: SQLiteKnowledgeStore, root: Path) {
    if (store.getCurrentSnapshot(root.toString()) == null) {
        throw CliException("baseline not found. Run 'learn index' on a clean revision first, then run 'learn index --working-tree'.")
    }
}

internal fun ensureBaselineIndexAllowed(repository: Source, workingTree: Boolean) {
    if (workingTree || repository !is LocalGitRepository) return
    val status = repository.repositoryStatus()
    val root = Path.of(status.root)
    val changedFiles = (status.stagedFiles + status.modifiedFiles + status.untrackedFiles + status.deletedFiles)
        .distinct()
        .filterNot { RepositoryPathFilter.isIgnored(root, root.resolve(it)) }
    if (changedFiles.isNotEmpty()) {
        val listed = changedFiles.take(5).joinToString(", ")
        val suffix = if (changedFiles.size > 5) " …" else ""
        throw CliException("working tree is not clean ($listed$suffix). Index a clean baseline first or use 'learn index --working-tree'.")
    }
}

private fun printDiff(path: Path, json: Boolean, workingTree: Boolean, baseRevision: String?, headRevision: String?) {
    if (baseRevision != null || headRevision != null) {
        if (baseRevision == null || headRevision == null) throw CliException("specify both --base and --head")
        printRevisionDiff(path, json, RevisionPair(baseRevision, headRevision))
        return
    }
    if (!workingTree) throw CliException("specify --working-tree or --base and --head")
    val repository = openRepositorySource(path)
    val root = Path.of(repository.repositoryStatus().root)
    val config = AppConfig.forRepository(root)
    if (!config.databaseFile.toFile().exists()) throw CliException("index not found: ${config.databaseFile}. Run 'learn index'.")
    SQLiteKnowledgeStore(config.databaseFile).use { store ->
        val baseline = store.getCurrentSnapshot(root.toString())
            ?: throw CliException("baseline not found. Run 'learn index'.")
        val overlay = store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE)
            ?: throw CliException("overlay not found. Run 'learn index --working-tree'.")
        ensureCurrentWorkingTree(repository, overlay.revision)
        val diff = SnapshotComparator.compare(
            baseline.revision,
            overlay.revision,
            store.entitiesForSnapshot(baseline.id),
            store.entitiesForSnapshot(overlay.id),
            store.relationsForSnapshot(baseline.id),
            store.relationsForSnapshot(overlay.id)
        )
        printDiffResult(diff, json)
    }
}

private fun printRevisionDiff(path: Path, json: Boolean, pair: RevisionPair) {
    val repository = LocalGitRepository(path)
    val root = Path.of(repository.repositoryStatus().root)
    val materializer = GitRevisionMaterializer(root)
    val (base, head) = materializer.materializePair(pair)
    try {
        val databaseRoot = java.nio.file.Files.createTempDirectory("arianna-revision-diff-")
        try {
            SQLiteKnowledgeStore(databaseRoot.resolve("base.db")).use { baseStore ->
                SQLiteKnowledgeStore(databaseRoot.resolve("head.db")).use { headStore ->
                    SpringAwareIndexer().index(base, baseStore)
                    SpringAwareIndexer().index(head, headStore)
                    val baseSnapshot = baseStore.getCurrentSnapshot(base.root.toString())
                        ?: throw CliException("impossibile creare lo snapshot base ${pair.base}")
                    val headSnapshot = headStore.getCurrentSnapshot(head.root.toString())
                        ?: throw CliException("impossibile creare lo snapshot head ${pair.head}")
                    val diff = SnapshotComparator.compare(
                        pair.base,
                        pair.head,
                        baseStore.entitiesForSnapshot(baseSnapshot.id),
                        headStore.entitiesForSnapshot(headSnapshot.id),
                        baseStore.relationsForSnapshot(baseSnapshot.id),
                        headStore.relationsForSnapshot(headSnapshot.id)
                    )
                    printDiffResult(diff, json)
                }
            }
        } finally {
            java.nio.file.Files.walk(databaseRoot).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
    } finally {
        base.close()
        head.close()
    }
}

private fun printDiffResult(diff: SnapshotDiff, json: Boolean) {
    if (json) println(diff.toJson()) else printDiffHuman(diff)
}

private fun printImpact(path: Path, json: Boolean, workingTree: Boolean, baseRevision: String?, headRevision: String?) {
    if (baseRevision != null || headRevision != null) {
        if (baseRevision == null || headRevision == null) throw CliException("specify both --base and --head")
        printRevisionImpact(path, json, RevisionPair(baseRevision, headRevision))
        return
    }
    if (!workingTree) throw CliException("specify --working-tree to analyze local impact")
    val repository = openRepositorySource(path)
    val root = Path.of(repository.repositoryStatus().root)
    val config = AppConfig.forRepository(root)
    if (!config.databaseFile.toFile().exists()) throw CliException("index not found: ${config.databaseFile}. Run 'learn index'.")
    SQLiteKnowledgeStore(config.databaseFile).use { store ->
        val baseline = store.getCurrentSnapshot(root.toString())
            ?: throw CliException("baseline not found. Run 'learn index'.")
        val overlay = store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE)
            ?: throw CliException("overlay not found. Run 'learn index --working-tree'.")
        ensureCurrentWorkingTree(repository, overlay.revision)
        val baseEntities = store.entitiesForSnapshot(baseline.id)
        val overlayEntities = store.entitiesForSnapshot(overlay.id)
        val diff = SnapshotComparator.compare(
            baseline.revision,
            overlay.revision,
            baseEntities,
            overlayEntities,
            store.relationsForSnapshot(baseline.id),
            store.relationsForSnapshot(overlay.id)
        )
        val report = ImpactAnalyzer.analyze(
            diff,
            baseEntities,
            overlayEntities,
            store.relationsForSnapshot(baseline.id),
            store.relationsForSnapshot(overlay.id)
        )
        if (json) println(report.toJson()) else printImpactHuman(report)
    }
}

private fun printRevisionImpact(path: Path, json: Boolean, pair: RevisionPair) {
    withMaterializedRevisionChange(path, pair) { diff, baseEntities, headEntities, baseRelations, headRelations ->
        val report = ImpactAnalyzer.analyze(diff, baseEntities, headEntities, baseRelations, headRelations)
        if (json) println(report.toJson()) else printImpactHuman(report)
    }
}

private fun withMaterializedRevisionChange(
    path: Path,
    pair: RevisionPair,
    block: (SnapshotDiff, List<KnowledgeEntity>, List<KnowledgeEntity>, List<KnowledgeRelation>, List<KnowledgeRelation>) -> Unit
) {
    val repository = LocalGitRepository(path)
    val root = Path.of(repository.repositoryStatus().root)
    val materializer = GitRevisionMaterializer(root)
    val (base, head) = materializer.materializePair(pair)
    try {
        val databaseRoot = java.nio.file.Files.createTempDirectory("arianna-revision-change-")
        try {
            SQLiteKnowledgeStore(databaseRoot.resolve("base.db")).use { baseStore ->
                SQLiteKnowledgeStore(databaseRoot.resolve("head.db")).use { headStore ->
                    SpringAwareIndexer().index(base, baseStore)
                    SpringAwareIndexer().index(head, headStore)
                    val baseSnapshot = baseStore.getCurrentSnapshot(base.root.toString())
                        ?: throw CliException("impossibile creare lo snapshot base ${pair.base}")
                    val headSnapshot = headStore.getCurrentSnapshot(head.root.toString())
                        ?: throw CliException("impossibile creare lo snapshot head ${pair.head}")
                    val baseEntities = baseStore.entitiesForSnapshot(baseSnapshot.id)
                    val headEntities = headStore.entitiesForSnapshot(headSnapshot.id)
                    val baseRelations = baseStore.relationsForSnapshot(baseSnapshot.id)
                    val headRelations = headStore.relationsForSnapshot(headSnapshot.id)
                    val diff = SnapshotComparator.compare(
                        pair.base,
                        pair.head,
                        baseEntities,
                        headEntities,
                        baseRelations,
                        headRelations
                    )
                    block(diff, baseEntities, headEntities, baseRelations, headRelations)
                }
            }
        } finally {
            java.nio.file.Files.walk(databaseRoot).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
    } finally {
        base.close()
        head.close()
    }
}

private fun printImpactHuman(report: ImpactReport) {
    println("Baseline: ${report.baseRevision}")
    println("Overlay:  ${report.overlayRevision}")
    println("Changed entities: ${report.changedEntities.size}")
    println("Findings: ${report.findings.size} (breaking=${report.breakingCount}, possible=${report.possibleCount})")
    report.findings.forEach { finding ->
        println("  [${finding.severity.name.lowercase()}/${finding.certainty.name.lowercase()}] ${finding.category}: ${finding.message}")
        finding.evidence?.let { println("    evidence: ${it.file}:${it.startLine ?: "?"} @ ${it.revision}") }
    }
}

private fun ImpactReport.toJson(): String = buildString {
    append("{\"baseRevision\":${quoteJson(baseRevision)},\"overlayRevision\":${quoteJson(overlayRevision)},")
    append("\"changedEntities\":[")
    append(changedEntities.joinToString(",") { change ->
        "{\"change\":${quoteJson(change.kind.name.lowercase())},\"id\":${quoteJson(change.entityId.value)}}"
    })
    append("],\"findings\":[")
    append(findings.joinToString(",") { finding ->
        "{\"severity\":${quoteJson(finding.severity.name.lowercase())},\"certainty\":${quoteJson(finding.certainty.name.lowercase())},\"category\":${quoteJson(finding.category)},\"message\":${quoteJson(finding.message)},\"entityId\":${finding.entityId?.value?.let(::quoteJson) ?: "null"},\"relation\":${finding.relation?.toJson() ?: "null"},\"file\":${finding.evidence?.file?.let(::quoteJson) ?: "null"},\"startLine\":${finding.evidence?.startLine ?: "null"},\"revision\":${finding.evidence?.revision?.let(::quoteJson) ?: "null"}}"
    })
    append("]}")
}

private fun KnowledgeRelation.toJson(): String =
    "{\"source\":${quoteJson(source.value)},\"type\":${quoteJson(type)},\"target\":${quoteJson(target.value)},\"origin\":${quoteJson(origin.name.lowercase())},\"confidence\":${quoteJson(confidence.name.lowercase())},\"evidence\":${evidence?.toJson() ?: "null"}}"

private fun printPlanRefactor(path: Path, json: Boolean, workingTree: Boolean, baseRevision: String?, headRevision: String?) {
    if (baseRevision != null || headRevision != null) {
        if (baseRevision == null || headRevision == null) throw CliException("specify both --base and --head")
        withMaterializedRevisionChange(path, RevisionPair(baseRevision, headRevision)) { diff, baseEntities, headEntities, baseRelations, headRelations ->
            val report = ImpactAnalyzer.analyze(diff, baseEntities, headEntities, baseRelations, headRelations)
            val plan = RefactoringPlanner.plan(report)
            if (json) println(plan.toJson()) else printPlanHuman(plan)
        }
        return
    }
    if (!workingTree) throw CliException("specify --working-tree to generate the plan")
    withStoredWorkingTreeChange(path) { diff, baseEntities, overlayEntities, baseRelations, overlayRelations ->
        val report = ImpactAnalyzer.analyze(diff, baseEntities, overlayEntities, baseRelations, overlayRelations)
        val plan = RefactoringPlanner.plan(report)
        if (json) println(plan.toJson()) else printPlanHuman(plan)
    }
}

private fun printVerifyChange(path: Path, json: Boolean, workingTree: Boolean, baseRevision: String?, headRevision: String?) {
    if (baseRevision != null || headRevision != null) {
        if (baseRevision == null || headRevision == null) throw CliException("specify both --base and --head")
        withMaterializedRevisionChange(path, RevisionPair(baseRevision, headRevision)) { diff, baseEntities, headEntities, baseRelations, headRelations ->
            val impact = ImpactAnalyzer.analyze(diff, baseEntities, headEntities, baseRelations, headRelations)
            val report = ChangeVerifier.verify(diff, impact, baseEntities, headEntities, baseRelations, headRelations)
            if (json) println(report.toJson()) else printVerificationHuman(report)
        }
        return
    }
    if (!workingTree) throw CliException("specify --working-tree to verify the change")
    withStoredWorkingTreeChange(path) { diff, baseEntities, overlayEntities, baseRelations, overlayRelations ->
        val impact = ImpactAnalyzer.analyze(diff, baseEntities, overlayEntities, baseRelations, overlayRelations)
        val report = ChangeVerifier.verify(diff, impact, baseEntities, overlayEntities, baseRelations, overlayRelations)
        if (json) println(report.toJson()) else printVerificationHuman(report)
    }
}

private fun withStoredWorkingTreeChange(
    path: Path,
    block: (SnapshotDiff, List<dev.arianna.core.model.KnowledgeEntity>, List<dev.arianna.core.model.KnowledgeEntity>, List<dev.arianna.core.model.KnowledgeRelation>, List<dev.arianna.core.model.KnowledgeRelation>) -> Unit
) {
    val repository = openRepositorySource(path)
    val root = Path.of(repository.repositoryStatus().root)
    val config = AppConfig.forRepository(root)
    if (!config.databaseFile.toFile().exists()) throw CliException("index not found: ${config.databaseFile}. Run 'learn index'.")
    SQLiteKnowledgeStore(config.databaseFile).use { store ->
        val baseline = store.getCurrentSnapshot(root.toString()) ?: throw CliException("baseline not found. Run 'learn index'.")
        val overlay = store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE) ?: throw CliException("overlay not found. Run 'learn index --working-tree'.")
        ensureCurrentWorkingTree(repository, overlay.revision)
        val baseEntities = store.entitiesForSnapshot(baseline.id)
        val overlayEntities = store.entitiesForSnapshot(overlay.id)
        val baseRelations = store.relationsForSnapshot(baseline.id)
        val overlayRelations = store.relationsForSnapshot(overlay.id)
        block(
            SnapshotComparator.compare(baseline.revision, overlay.revision, baseEntities, overlayEntities, baseRelations, overlayRelations),
            baseEntities,
            overlayEntities,
            baseRelations,
            overlayRelations
        )
    }
}

private fun ensureCurrentWorkingTree(repository: Source, overlayRevision: String) {
    val current = repository.workingTreeRevision()
    if (current != overlayRevision) {
        throw CliException("working-tree overlay is stale. Run 'learn index --working-tree' again.")
    }
}

private fun printPlanHuman(plan: RefactoringPlan) {
    println("Baseline: ${plan.baseRevision}")
    println("Overlay:  ${plan.overlayRevision}")
    plan.steps.forEach { step ->
        println("${step.order}. ${step.title} (${step.findingCount} finding)")
        step.actions.forEach { println("   - $it") }
        step.entityIds.forEach { println("   - entity: ${it.value}") }
        step.evidence.forEach { println("   - evidence: ${it.file}:${it.startLine ?: "?"} @ ${it.revision}") }
    }
    println(plan.note)
}

private fun RefactoringPlan.toJson(): String = buildString {
    append("{\"baseRevision\":${quoteJson(baseRevision)},\"overlayRevision\":${quoteJson(overlayRevision)},\"externalVerificationRequired\":$externalVerificationRequired,\"steps\":[")
    append(steps.joinToString(",") { step ->
        "{\"order\":${step.order},\"category\":${quoteJson(step.category)},\"title\":${quoteJson(step.title)},\"findingCount\":${step.findingCount},\"entityIds\":[${step.entityIds.joinToString(",") { quoteJson(it.value) }}],\"actions\":[${step.actions.joinToString(",") { quoteJson(it) }}],\"evidence\":[${step.evidence.joinToString(",") { it.toJson() }}]}"
    })
    append("],\"note\":${quoteJson(note)}}")
}

private fun printVerificationHuman(report: VerificationReport) {
    println("Baseline: ${report.baseRevision}")
    println("Overlay:  ${report.overlayRevision}")
    println("Issues: ${report.issues.size} (confirmed=${report.confirmedIssueCount})")
    report.issues.forEach { issue ->
        println("  [${issue.severity.name.lowercase()}/${issue.certainty.name.lowercase()}] ${issue.category}: ${issue.message}")
        issue.evidence?.let { println("    evidence: ${it.file}:${it.startLine ?: "?"} @ ${it.revision}") }
    }
    println(report.note)
}

private fun VerificationReport.toJson(): String = buildString {
    append("{\"baseRevision\":${quoteJson(baseRevision)},\"overlayRevision\":${quoteJson(overlayRevision)},\"externalVerificationRequired\":$externalVerificationRequired,\"issues\":[")
    append(issues.joinToString(",") { issue ->
        "{\"severity\":${quoteJson(issue.severity.name.lowercase())},\"certainty\":${quoteJson(issue.certainty.name.lowercase())},\"category\":${quoteJson(issue.category)},\"message\":${quoteJson(issue.message)},\"entityId\":${issue.entityId?.value?.let(::quoteJson) ?: "null"},\"file\":${issue.evidence?.file?.let(::quoteJson) ?: "null"},\"startLine\":${issue.evidence?.startLine ?: "null"}}"
    })
    append("],\"note\":${quoteJson(note)}}")
}

internal fun diffRepositoryPath(arguments: List<String>): Path {
    val pathIndex = arguments.indexOf("--path")
    return if (pathIndex >= 0 && pathIndex + 1 < arguments.size) {
        Path.of(arguments[pathIndex + 1]).absolute().normalize()
    } else {
        val valueOptions = setOf("--base", "--head", "--path")
        var index = 0
        var positional: String? = null
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument in valueOptions -> index += 2
                argument.startsWith("--") -> index += 1
                else -> {
                    positional = argument
                    break
                }
            }
        }
        Path.of(positional ?: ".").absolute().normalize()
    }
}

private fun printDiffHuman(diff: SnapshotDiff) {
    println("Baseline: ${diff.baseRevision}")
    println("Overlay:  ${diff.overlayRevision}")
    println("Entities: ${diff.changedEntityCount}")
    diff.entities.forEach { change ->
        val entity = change.after ?: change.before
        println("  ${change.kind.symbol()} ${entity?.kind}: ${entity?.qualifiedName ?: change.entityId.value}")
    }
    println("Relations: ${diff.changedRelationCount}")
    diff.relations.forEach { change ->
        val relation = change.after ?: change.before
        println("  ${change.kind.symbol()} ${relation?.source?.value} --${relation?.type}--> ${relation?.target?.value}")
    }
}

private fun SnapshotChangeKind.symbol(): String = when (this) {
    SnapshotChangeKind.ADDED -> "+"
    SnapshotChangeKind.REMOVED -> "-"
    SnapshotChangeKind.MODIFIED -> "~"
}

private fun SnapshotDiff.toJson(): String = buildString {
    append("{\"baseRevision\":${quoteJson(baseRevision)},\"overlayRevision\":${quoteJson(overlayRevision)},")
    append("\"entities\":[")
    append(entities.joinToString(",") { change ->
        val entity = change.after ?: change.before
        "{\"change\":${quoteJson(change.kind.name.lowercase())},\"id\":${quoteJson(change.entityId.value)},\"kind\":${entity?.kind?.let(::quoteJson) ?: "null"},\"qualifiedName\":${entity?.qualifiedName?.let(::quoteJson) ?: "null"}}"
    })
    append("],\"relations\":[")
    append(relations.joinToString(",") { change ->
        val relation = change.after ?: change.before
        "{\"change\":${quoteJson(change.kind.name.lowercase())},\"key\":${quoteJson(change.key)},\"source\":${relation?.source?.value?.let(::quoteJson) ?: "null"},\"type\":${relation?.type?.let(::quoteJson) ?: "null"},\"target\":${relation?.target?.value?.let(::quoteJson) ?: "null"}}"
    })
    append("]}")
}

internal fun queryArgument(arguments: List<String>): String {
    val pathIndex = arguments.indexOf("--path")
    return arguments.withIndex()
        .firstOrNull { (index, value) ->
            !value.startsWith("--") && (pathIndex < 0 || index != pathIndex + 1)
        }
        ?.value
        ?: throw CliException("missing search term or symbol")
}

private fun queryPath(arguments: List<String>): Path {
    val pathIndex = arguments.indexOf("--path")
    return if (pathIndex >= 0 && pathIndex + 1 < arguments.size) {
        Path.of(arguments[pathIndex + 1]).absolute().normalize()
    } else {
        Path.of(".").absolute().normalize()
    }
}

private fun printSearch(query: String, path: Path, json: Boolean, limit: Int, offset: Int) {
    withQueryEngine(path) { engine ->
        val page = engine.findSymbolsPage(query, offset, limit)
        if (json) println(page.entitiesToJson()) else page.items.forEach { printEntity(it) }
    }
}

private fun printSearchKnowledge(query: String, path: Path, json: Boolean, limit: Int, offset: Int, repository: String?, file: String?, kind: String?) {
    withQueryEngine(path) { engine ->
        val page = engine.searchKnowledge(query, offset, limit, repository, file, kind)
        if (json) println(page.entitiesToJson()) else page.items.forEach { printEntity(it) }
    }
}

private fun printDocument(pathValue: String, repositoryPath: Path, json: Boolean) {
    withQueryEngine(repositoryPath) { engine ->
        val document = engine.getDocument(pathValue)
            ?: throw CliException("document not found in the index: $pathValue")
        if (json) {
            println(document.toDocumentJson())
        } else {
            println("Document: ${document.qualifiedName}")
            document.evidence?.let { println("Evidence: ${it.file}:${it.startLine ?: "?"} @ ${it.revision}") }
            println(document.content.orEmpty())
        }
    }
}

private fun printRelations(query: String, path: Path, json: Boolean, implementations: Boolean, limit: Int, offset: Int, revision: String?, confidence: String?) {
    withQueryEngine(path) { engine ->
        val page = if (implementations) {
            engine.findImplementationsPage(query, offset, limit, revision, confidence)
        } else {
            engine.findReferencesPage(query, offset, limit, revision, confidence)
        }
        if (json) println(page.relationsToJson()) else page.items.forEach { printRelation(it) }
    }
}

private fun printDirectRelations(entityId: String, path: Path, json: Boolean, limit: Int, offset: Int, revision: String?, confidence: String?) {
    withQueryEngine(path) { engine ->
        val page = engine.findRelationshipsPage(entityId, offset, limit, revision, confidence)
        if (json) println(page.relationsToJson()) else page.items.forEach { printRelation(it) }
    }
}

private fun queryLimit(arguments: List<String>): Int = queryOption(arguments, "--limit", 50).coerceIn(1, 1000)

private fun queryOffset(arguments: List<String>): Int = queryOption(arguments, "--offset", 0).coerceAtLeast(0)

private fun queryOption(arguments: List<String>, name: String, default: Int): Int {
    val index = arguments.indexOf(name)
    return if (index >= 0 && index + 1 < arguments.size) arguments[index + 1].toIntOrNull() ?: default else default
}

internal fun queryStringOption(arguments: List<String>, name: String): String? {
    val index = arguments.indexOf(name)
    if (index < 0) return null
    return arguments.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
}

private fun withQueryEngine(path: Path, block: (KnowledgeQueryEngine) -> Unit) {
    val repository = openRepositorySource(path)
    val root = Path.of(repository.repositoryStatus().root)
    val config = AppConfig.forRepository(root)
    if (!config.databaseFile.toFile().exists()) {
        throw CliException("index not found: ${config.databaseFile}. Run 'learn index'.")
    }
    SQLiteKnowledgeStore(config.databaseFile).use { block(KnowledgeQueryEngine(it)) }
}

private fun printEntity(entity: KnowledgeEntity) {
    println("${entity.kind}: ${entity.qualifiedName}")
    entity.evidence?.let { println("  evidence: ${it.file}:${it.startLine ?: "?"}-${it.endLine ?: "?"} @ ${it.revision}") }
}

private fun printRelation(relation: KnowledgeRelation) {
    println("${relation.source.value} --${relation.type}--> ${relation.target.value}")
    println("  ${relation.origin.name.lowercase()} / ${relation.confidence.name.lowercase()}")
    relation.evidence?.let { println("  evidence: ${it.file}:${it.startLine ?: "?"}-${it.endLine ?: "?"} @ ${it.revision}") }
}

private fun quoteJson(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun Evidence.toJson(): String =
    "{\"repository\":${quoteJson(repository)},\"revision\":${quoteJson(revision)},\"file\":${quoteJson(file)},\"startLine\":${startLine ?: "null"},\"endLine\":${endLine ?: "null"},\"analyzerVersion\":${quoteJson(analyzerVersion)}}"

private fun Page<KnowledgeEntity>.entitiesToJson(): String =
    "{\"items\":${items.entitiesToJson()},\"total\":$total,\"offset\":$offset,\"limit\":$limit,\"nextOffset\":${nextOffset ?: "null"}}"

private fun List<KnowledgeEntity>.entitiesToJson(): String = joinToString(prefix = "[", postfix = "]", separator = ",") { entity ->
    "{\"id\":${quoteJson(entity.id.value)},\"kind\":${quoteJson(entity.kind)},\"qualifiedName\":${quoteJson(entity.qualifiedName)},\"file\":${entity.evidence?.file?.let(::quoteJson) ?: "null"},\"startLine\":${entity.evidence?.startLine ?: "null"},\"endLine\":${entity.evidence?.endLine ?: "null"},\"revision\":${entity.evidence?.revision?.let(::quoteJson) ?: "null"},\"analyzerVersion\":${entity.evidence?.analyzerVersion?.let(::quoteJson) ?: "null"}}"
}

private fun KnowledgeEntity.toDocumentJson(): String =
    "{\"id\":${quoteJson(id.value)},\"kind\":${quoteJson(kind)},\"path\":${quoteJson(qualifiedName)},\"content\":${quoteJson(content.orEmpty())},\"file\":${evidence?.file?.let(::quoteJson) ?: "null"},\"revision\":${evidence?.revision?.let(::quoteJson) ?: "null"},\"analyzerVersion\":${evidence?.analyzerVersion?.let(::quoteJson) ?: "null"}}"

private fun Page<KnowledgeRelation>.relationsToJson(): String =
    "{\"items\":${items.relationsToJson()},\"total\":$total,\"offset\":$offset,\"limit\":$limit,\"nextOffset\":${nextOffset ?: "null"}}"

private fun List<KnowledgeRelation>.relationsToJson(): String = joinToString(prefix = "[", postfix = "]", separator = ",") { relation ->
    "{\"source\":${quoteJson(relation.source.value)},\"type\":${quoteJson(relation.type)},\"target\":${quoteJson(relation.target.value)},\"origin\":${quoteJson(relation.origin.name.lowercase())},\"confidence\":${quoteJson(relation.confidence.name.lowercase())},\"file\":${relation.evidence?.file?.let(::quoteJson) ?: "null"},\"startLine\":${relation.evidence?.startLine ?: "null"},\"endLine\":${relation.evidence?.endLine ?: "null"},\"revision\":${relation.evidence?.revision?.let(::quoteJson) ?: "null"},\"analyzerVersion\":${relation.evidence?.analyzerVersion?.let(::quoteJson) ?: "null"}}"
}

private fun RepositoryStatus.toJson(layout: dev.arianna.core.source.ProjectLayout): String {
    fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun array(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { quote(it) }

    return buildString {
        append("{\n")
        append("  \"root\": ").append(quote(root)).append(",\n")
        append("  \"head\": ").append(quote(head)).append(",\n")
        append("  \"branch\": ").append(branch?.let(::quote) ?: "null").append(",\n")
        append("  \"buildSystems\": ").append(array(layout.buildSystems.map { it.name.lowercase() })).append(",\n")
        append("  \"indexedRevision\": ").append(indexedRevision?.let(::quote) ?: "null").append(",\n")
        append("  \"stagedFiles\": ").append(array(stagedFiles)).append(",\n")
        append("  \"modifiedFiles\": ").append(array(modifiedFiles)).append(",\n")
        append("  \"untrackedFiles\": ").append(array(untrackedFiles)).append(",\n")
        append("  \"deletedFiles\": ").append(array(deletedFiles)).append("\n")
        append("}")
    }
}

private fun printHelp() {
    println(
        """Arianna — local knowledge engine

Usage:
  learn [path]              Index the repository and update the local index
  learn status [path]       Show repository status
  learn index [path]        Index repository, documents, and the local JVM graph
  learn index --scip        Use scip-java + scip for symbols and references
  learn index --spring      Add static Spring relations
  learn index --working-tree Index the overlay and JVM graph without replacing the baseline
  learn diff --working-tree  Compare baseline and overlay
  learn diff --base <rev> --head <rev> --path <repo> Compare two Git revisions
  learn impact --working-tree Analyze local change impact
  learn impact --base <rev> --head <rev> --path <repo> Analyze impact for a Git revision pair
  learn plan-refactor --working-tree Generate the ordered refactoring plan
  learn plan-refactor --base <rev> --head <rev> --path <repo> Generate a plan for a Git revision pair
  learn verify-change --working-tree Verify change leftovers and risks
  learn verify-change --base <rev> --head <rev> --path <repo> Verify a Git revision pair
  learn mcp [--path <repository>] Start the local MCP server over stdio
  learn serve [path]        Start the local Web Explorer
  learn export <path> --output <file.zip> Export a portable snapshot
  learn snapshot <file.zip> Start a read-only Web Explorer from a snapshot
  learn benchmark <path>     Measure indexing and query latency
  learn benchmark <path> --baseline Measure the direct grep-like baseline
  learn benchmark <path> --compare Compare the direct baseline with Arianna
  learn validate --observations <file.json> Evaluate the gate on collected sessions
  learn preflight [path]   Check SCIP and the Maven/Gradle build tool
  learn search <term>       Search entities and files in the index
  learn search-knowledge <term> Search entities and document content
  learn get-document <path> Return an indexed document
  learn find-symbol <name>  Search for a symbol in the index
  learn references <name>   Search callers/references
  learn implementations <name> Search implementations
  learn relations <id>      Show direct relations
  Query options: --path <repository> --limit <n> --offset <n> --json
  Relation options: --revision <revision> --confidence <high|medium|low>
  Knowledge options: --repository <id> --file <path> --kind <kind>
  learn status --json       Return status as JSON
  learn --version           Show the version
  learn --help              Show this help
""".trimIndent()
    )
}

private class CliException(message: String) : RuntimeException(message)
