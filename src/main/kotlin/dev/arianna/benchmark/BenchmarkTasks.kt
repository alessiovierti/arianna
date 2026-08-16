package dev.arianna.benchmark

data class BenchmarkTask(
    val taskId: String,
    val queries: List<String>
)

/** Stable task/query matrix shared by the automated baseline and Arianna measurements. */
object BenchmarkTaskCatalog {
    val default: List<BenchmarkTask> = listOf(
        BenchmarkTask("B01", listOf("PaymentService", "PaymentController")),
        BenchmarkTask("B02", listOf("PaymentController", "PaymentService", "PaymentRepository", "PaymentEventHandler", "primary")),
        BenchmarkTask("B03", listOf("/payments/{id}")),
        BenchmarkTask("B04", listOf("PaymentServiceTest", "processPayment", "mock")),
        BenchmarkTask("B05", listOf("README.md", "application.yml", "application.properties")),
        BenchmarkTask("B06", listOf("signature", "process(")),
        BenchmarkTask("B07", listOf("dynamicBeanName", "dynamic"))
    )
}
