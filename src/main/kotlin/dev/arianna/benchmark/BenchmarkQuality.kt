package dev.arianna.benchmark

data class RetrievalQuality(
    val query: String,
    val expected: Set<String>,
    val actual: Set<String>
) {
    val truePositives: Int get() = expected.intersect(actual).size
    val falsePositives: Set<String> get() = actual - expected
    val missing: Set<String> get() = expected - actual
    val precision: Double get() = if (actual.isEmpty()) 1.0 else truePositives.toDouble() / actual.size
    val recall: Double get() = if (expected.isEmpty()) 1.0 else truePositives.toDouble() / expected.size
}

data class BenchmarkTaskQuality(
    val taskId: String,
    val expected: Set<String>,
    val actual: Set<String>,
    val truePositives: Int,
    val precision: Double,
    val recall: Double,
    val falsePositives: Set<String>,
    val missing: Set<String>
)

/** Reference entities from the checked-in MVP benchmark manifest. */
object BenchmarkReferenceAnswers {
    private val answers = mapOf(
        "B01" to setOf("PaymentService.process", "PaymentController.get"),
        "B02" to setOf("PaymentController", "PaymentService", "PaymentRepository", "PaymentEventHandler"),
        "B03" to setOf("GET /payments/{id}"),
        "B04" to setOf("PaymentServiceTest.processPayment", "PaymentServiceTest.mock"),
        "B05" to setOf("README.md", "src/main/resources/application.yml", "src/main/resources/application.properties"),
        "B06" to setOf("PaymentService.process", "PaymentController.get"),
        "B07" to setOf("unknown.dynamicBeanName")
    )

    fun expectedFor(taskId: String): Set<String> = answers[taskId].orEmpty()
}

object BenchmarkQualityEvaluator {
    fun evaluate(query: String, expected: Set<String>, actual: Set<String>): RetrievalQuality =
        RetrievalQuality(query, expected, actual)
}
