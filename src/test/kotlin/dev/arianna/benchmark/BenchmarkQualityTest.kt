package dev.arianna.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkQualityTest {
    @Test
    fun `computes precision recall false positives and missing results`() {
        val quality = BenchmarkQualityEvaluator.evaluate(
            "payment callers",
            expected = setOf("PaymentController.get", "PaymentServiceTest.processPayment"),
            actual = setOf("PaymentController.get", "Unrelated.call")
        )

        assertEquals(0.5, quality.precision)
        assertEquals(0.5, quality.recall)
        assertEquals(setOf("Unrelated.call"), quality.falsePositives)
        assertEquals(setOf("PaymentServiceTest.processPayment"), quality.missing)
        assertTrue(quality.truePositives == 1)
    }
}
