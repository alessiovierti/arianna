package dev.arianna.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkRunnerTest {
    @Test
    fun `runs reproducible measurements on the MVP fixture`() {
        val fixtureUrl = requireNotNull(javaClass.getResource("/benchmarks/mvp-fixture"))
        val sourceRoot = Path.of(fixtureUrl.toURI())
        val root = Files.createTempDirectory("arianna-benchmark-fixture-")
        copyTree(sourceRoot, root)

        val result = BenchmarkRunner().run(root)

        assertEquals(root.toAbsolutePath().normalize().toString(), result.repository)
        assertTrue(result.indexedFiles > 0)
        assertTrue(result.indexedEntities > result.indexedFiles)
        assertTrue(result.indexedRelations > 0)
        assertEquals(4, result.queryMetrics.size)
        assertEquals(7, result.taskMetrics.size)
        assertEquals((1..7).map { "B%02d".format(it) }, result.taskMetrics.map { it.taskId })
        assertEquals(7, result.qualityMetrics.size)
        assertTrue(result.qualityMetrics.all { it.precision in 0.0..1.0 && it.recall in 0.0..1.0 })
        assertTrue(result.qualityMetrics.first { it.taskId == "B03" }.recall > 0.0)
        assertTrue(result.indexMillis >= 0.0)
        assertTrue(result.updateMillis >= 0.0)
        assertTrue(result.impactMillis >= 0.0)
        assertTrue(result.impactFindingCount > 0)
        assertTrue(result.queryMetrics.all { it.latencyMillis >= 0.0 })
        assertTrue(result.taskMetrics.all { it.latencyMillis >= 0.0 })
    }

    @Test
    fun `compares direct repository and Arianna metrics without merging their units`() {
        val fixtureUrl = requireNotNull(javaClass.getResource("/benchmarks/mvp-fixture"))
        val sourceRoot = Path.of(fixtureUrl.toURI())
        val root = Files.createTempDirectory("arianna-benchmark-comparison-")
        copyTree(sourceRoot, root)

        val result = BenchmarkComparisonRunner().run(root)

        assertEquals(4, result.directRepository.queryMetrics.size)
        assertEquals(4, result.arianna.queryMetrics.size)
        assertEquals(7, result.directRepository.taskMetrics.size)
        assertEquals(7, result.arianna.taskMetrics.size)
        assertTrue(result.note.contains("different units"))
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) target.createDirectories() else Files.copy(path, target)
            }
        }
    }
}
