package dev.arianna.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import dev.arianna.core.source.RepositoryPathFilter
import kotlin.system.measureNanoTime

data class DirectSearchMetric(
    val query: String,
    val matchedFiles: Int,
    val matchedLines: Int,
    val latencyMillis: Double
)

data class DirectTaskMetric(
    val taskId: String,
    val queryCount: Int,
    val matchedFiles: Int,
    val matchedLines: Int,
    val latencyMillis: Double
)

data class DirectRepositoryBaseline(
    val repository: String,
    val queryMetrics: List<DirectSearchMetric>,
    val taskMetrics: List<DirectTaskMetric> = emptyList()
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)
}

/** Reproducible direct-repository baseline, analogous to a simple grep workflow. */
class DirectRepositoryBaselineRunner(
    private val queries: List<String> = listOf("PaymentService", "PaymentController", "PaymentCreated", "/payments"),
    private val tasks: List<BenchmarkTask> = BenchmarkTaskCatalog.default
) {
    fun run(root: Path): DirectRepositoryBaseline {
        val files = Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .toList()
        }
        val metrics = queries.map { query ->
            var matchedFiles = 0
            var matchedLines = 0
            val nanos = measureNanoTime {
                files.forEach { file ->
                    val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
                    val matches = lines.count { it.contains(query, ignoreCase = true) }
                    if (matches > 0) {
                        matchedFiles++
                        matchedLines += matches
                    }
                }
            }
            DirectSearchMetric(query, matchedFiles, matchedLines, nanos / 1_000_000.0)
        }
        val taskMetrics = tasks.map { task ->
            var matchedFiles = 0
            var matchedLines = 0
            val nanos = measureNanoTime {
                task.queries.forEach { query ->
                    files.forEach { file ->
                        val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
                        val matches = lines.count { it.contains(query, ignoreCase = true) }
                        if (matches > 0) matchedFiles++
                        matchedLines += matches
                    }
                }
            }
            DirectTaskMetric(task.taskId, task.queries.size, matchedFiles, matchedLines, nanos / 1_000_000.0)
        }
        return DirectRepositoryBaseline(root.toAbsolutePath().normalize().toString(), metrics, taskMetrics)
    }
}
