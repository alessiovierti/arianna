package dev.arianna.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectRepositoryBaselineTest {
    @Test
    fun `measures direct repository search for the same benchmark queries`() {
        val root = Files.createTempDirectory("arianna-direct-baseline-")
        root.resolve("README.md").toFile().writeText("PaymentService is documented here\n")
        root.resolve("src").createDirectories()
        root.resolve("src/Payment.kt").toFile().writeText("class PaymentController { fun process() = PaymentService() }\n")

        val result = DirectRepositoryBaselineRunner(listOf("PaymentService", "PaymentController")).run(root)

        assertEquals(2, result.queryMetrics.size)
        assertEquals(7, result.taskMetrics.size)
        assertEquals(2, result.queryMetrics.first().matchedFiles)
        assertTrue(result.queryMetrics.all { it.latencyMillis >= 0.0 })
    }
}
