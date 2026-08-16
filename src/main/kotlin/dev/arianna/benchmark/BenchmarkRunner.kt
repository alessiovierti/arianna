package dev.arianna.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import dev.arianna.core.api.Source
import dev.arianna.core.indexing.ImpactAnalyzer
import dev.arianna.core.indexing.RepositoryFileIndexer
import dev.arianna.core.indexing.SnapshotComparator
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.model.SnapshotKind
import dev.arianna.core.query.KnowledgeQueryEngine
import dev.arianna.core.source.LocalDirectorySource
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.storage.SQLiteKnowledgeStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

data class BenchmarkQueryMetric(
    val query: String,
    val resultCount: Int,
    val latencyMillis: Double
)

data class BenchmarkTaskMetric(
    val taskId: String,
    val queryCount: Int,
    val resultCount: Int,
    val latencyMillis: Double
)

data class BenchmarkResult(
    val repository: String,
    val indexedFiles: Int,
    val indexedEntities: Int,
    val indexedRelations: Int,
    val indexMillis: Double,
    val queryMetrics: List<BenchmarkQueryMetric>,
    val taskMetrics: List<BenchmarkTaskMetric>,
    val indexBytes: Long,
    val qualityMetrics: List<BenchmarkTaskQuality> = emptyList(),
    val updateMillis: Double = 0.0,
    val impactMillis: Double = 0.0,
    val impactFindingCount: Int = 0
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)
}

data class BenchmarkUpdateMetrics(
    val updateMillis: Double,
    val impactMillis: Double,
    val impactFindingCount: Int
)

data class BenchmarkComparison(
    val repository: String,
    val directRepository: DirectRepositoryBaseline,
    val arianna: BenchmarkResult,
    val note: String = "The direct-repository and Arianna metrics use different units and are not causal proof of productivity."
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)
}

/** Measures observable Arianna costs; human/IDE baselines are collected separately. */
class BenchmarkRunner(
    private val queries: List<String> = listOf("PaymentService", "PaymentController", "PaymentCreated", "/payments"),
    private val tasks: List<BenchmarkTask> = BenchmarkTaskCatalog.default
) {
    fun run(root: Path): BenchmarkResult {
        val databaseDirectory = Files.createTempDirectory("arianna-benchmark-")
        val database = databaseDirectory.resolve("knowledge.db")
        try {
            val source = BenchmarkSource(root.toAbsolutePath().normalize())
            var indexResult: dev.arianna.core.api.IndexResult? = null
            val indexNanos = measureNanoTime {
                SQLiteKnowledgeStore(database).use { store ->
                    indexResult = SpringAwareIndexer().index(source, store)
                }
            }
            val metrics = mutableListOf<BenchmarkQueryMetric>()
            val taskMetrics = mutableListOf<BenchmarkTaskMetric>()
            val qualityMetrics = mutableListOf<BenchmarkTaskQuality>()
            SQLiteKnowledgeStore(database).use { store ->
                val engine = KnowledgeQueryEngine(store)
                queries.forEach { query ->
                    var count = 0
                    val queryNanos = measureNanoTime { count = engine.searchKnowledge(query, limit = 100).total }
                    metrics += BenchmarkQueryMetric(query, count, queryNanos / 1_000_000.0)
                }
                tasks.forEach { task ->
                    var resultCount = 0
                    val taskNanos = measureNanoTime {
                        task.queries.forEach { query ->
                            resultCount += engine.searchKnowledge(query, limit = 100).total
                        }
                    }
                    taskMetrics += BenchmarkTaskMetric(task.taskId, task.queries.size, resultCount, taskNanos / 1_000_000.0)
                    val actual = task.queries
                        .flatMap { query -> engine.searchKnowledge(query, limit = 100).items }
                        .map { it.qualifiedName }
                        .toSet()
                    val quality = BenchmarkQualityEvaluator.evaluate(
                        task.taskId,
                        BenchmarkReferenceAnswers.expectedFor(task.taskId),
                        actual
                    )
                    qualityMetrics += BenchmarkTaskQuality(
                        task.taskId,
                        quality.expected,
                        quality.actual,
                        quality.truePositives,
                        quality.precision,
                        quality.recall,
                        quality.falsePositives,
                        quality.missing
                    )
                }
            }
            val result = requireNotNull(indexResult)
            val updateMetrics = measureWorkingTreeUpdate(root, databaseDirectory)
            return BenchmarkResult(
                source.root.toString(),
                result.indexedFiles,
                result.indexedEntities,
                result.indexedRelations,
                indexNanos / 1_000_000.0,
                metrics,
                taskMetrics,
                Files.size(database),
                qualityMetrics,
                updateMetrics.updateMillis,
                updateMetrics.impactMillis,
                updateMetrics.impactFindingCount
            )
        } finally {
            Files.walk(databaseDirectory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }

    private fun measureWorkingTreeUpdate(root: Path, temporaryDirectory: Path): BenchmarkUpdateMetrics {
        val workingRoot = temporaryDirectory.resolve("working-tree")
        copyTree(root, workingRoot)
        val source = LocalDirectorySource(workingRoot)
        val database = temporaryDirectory.resolve("working-tree.db")
        SQLiteKnowledgeStore(database).use { store ->
            SpringAwareIndexer().index(source, store)
            val changedFile = workingRoot.resolve("src/main/kotlin/fixture/BenchmarkChange.kt")
            Files.createDirectories(changedFile.parent)
            changedFile.writeText(
                """
                package fixture
                class BenchmarkChange {
                    fun changed() = "overlay"
                }
                """.trimIndent()
            )
            val updateNanos = measureNanoTime {
                SpringAwareIndexer().indexOverlay(source, store)
            }
            val repository = workingRoot.toAbsolutePath().normalize().toString()
            val baseline = store.getCurrentSnapshot(repository)
                ?: error("benchmark baseline snapshot non creato")
            val overlay = store.getLatestSnapshot(repository, SnapshotKind.WORKING_TREE)
                ?: error("benchmark overlay snapshot non creato")
            val baseEntities = store.entitiesForSnapshot(baseline.id)
            val overlayEntities = store.entitiesForSnapshot(overlay.id)
            val baseRelations = store.relationsForSnapshot(baseline.id)
            val overlayRelations = store.relationsForSnapshot(overlay.id)
            val diff = SnapshotComparator.compare(
                baseline.revision,
                overlay.revision,
                baseEntities,
                overlayEntities,
                baseRelations,
                overlayRelations
            )
            var findingCount = 0
            val impactNanos = measureNanoTime {
                findingCount = ImpactAnalyzer.analyze(
                    diff,
                    baseEntities,
                    overlayEntities,
                    baseRelations,
                    overlayRelations
                ).findings.size
            }
            return BenchmarkUpdateMetrics(
                updateMillis = updateNanos / 1_000_000.0,
                impactMillis = impactNanos / 1_000_000.0,
                impactFindingCount = findingCount
            )
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target)
            }
        }
    }

    private class BenchmarkSource(override val root: Path) : Source {
        override fun repositoryStatus(): RepositoryStatus = RepositoryStatus(
            root = root.toString(),
            head = "benchmark-fixture",
            branch = null,
            indexedRevision = null,
            stagedFiles = emptyList(),
            modifiedFiles = emptyList(),
            untrackedFiles = emptyList(),
            deletedFiles = emptyList()
        )
    }
}

class BenchmarkComparisonRunner(
    private val queries: List<String> = listOf("PaymentService", "PaymentController", "PaymentCreated", "/payments")
) {
    fun run(root: Path): BenchmarkComparison {
        val direct = DirectRepositoryBaselineRunner(queries).run(root)
        val arianna = BenchmarkRunner(queries).run(root)
        return BenchmarkComparison(root.toAbsolutePath().normalize().toString(), direct, arianna)
    }
}
