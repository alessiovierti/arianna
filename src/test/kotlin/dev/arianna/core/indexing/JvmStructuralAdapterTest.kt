package dev.arianna.core.indexing

import dev.arianna.core.api.Source
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.model.SnapshotKind
import dev.arianna.frameworks.spring.SpringAwareIndexer
import dev.arianna.storage.SQLiteKnowledgeStore
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmStructuralAdapterTest {
    @Test
    fun `extracts kotlin definitions inheritance and typed calls`() {
        val root = createTempDirectory("arianna-jvm-structural-")
        val source = root.resolve("src/main/kotlin/fixture/Payments.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            package fixture

            interface PaymentService {
                fun process(paymentId: String): Payment
            }

            class PaymentController(private val service: PaymentService) {
                fun get(id: String): Payment = service.process(id)
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "class:PaymentService" && it.kind == "interface" })
        assertTrue(analysis.entities.any { it.id.value == "package:fixture" && it.kind == "package" })
        assertTrue(analysis.relations.any { it.type == "contains" && it.source.value == "package:fixture" && it.target.value == "class:PaymentService" })
        assertTrue(analysis.entities.any { it.id.value == "method:PaymentController.get" })
        assertTrue(analysis.relations.any {
            it.type == "defines" && it.source.value == "class:PaymentController" && it.target.value == "method:PaymentController.get"
        })
        assertTrue(analysis.entities.any { it.id.value == "field:PaymentController.service" })
        assertTrue(analysis.relations.any { it.type == "contains" && it.target.value == "field:PaymentController.service" })
        assertTrue(analysis.relations.any {
            it.type == "calls" && it.source.value == "method:PaymentController.get" && it.target.value == "method:PaymentService.process"
        })
        assertTrue(analysis.relations.all { it.origin.name == "STATIC" })
    }

    @Test
    fun `extracts java implements relation`() {
        val root = createTempDirectory("arianna-jvm-java-")
        val source = root.resolve("Payment.java")
        source.writeText(
            """
            interface PaymentService { Payment process(String id); }
            class PaymentServiceImpl implements PaymentService {
                public Payment process(String id) { return null; }
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "implements" && it.source.value == "class:PaymentServiceImpl" && it.target.value == "class:PaymentService"
        })
    }

    @Test
    fun `extracts kotlin override relation`() {
        val root = createTempDirectory("arianna-jvm-override-")
        root.resolve("Services.kt").writeText(
            """
            interface PaymentService { fun process(id: String): Payment }
            class PaymentServiceImpl : PaymentService {
                override fun process(id: String): Payment = Payment(id)
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "overrides" && it.source.value == "method:PaymentServiceImpl.process" && it.target.value == "method:PaymentService.process"
        })
    }

    @Test
    fun `links override when parent is declared in another file`() {
        val root = createTempDirectory("arianna-jvm-cross-file-override-")
        root.resolve("PaymentService.kt").writeText("interface PaymentService { fun process(id: String): Payment }")
        root.resolve("PaymentServiceImpl.kt").writeText(
            """
            class PaymentServiceImpl : PaymentService {
                override fun process(id: String): Payment = Payment(id)
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "overrides" && it.source.value == "method:PaymentServiceImpl.process" && it.target.value == "method:PaymentService.process"
        })
    }

    @Test
    fun `links method access to a declared field`() {
        val root = createTempDirectory("arianna-jvm-field-reference-")
        root.resolve("Controller.kt").writeText(
            """
            class Controller {
                private val service: PaymentService = PaymentService()
                fun get(id: String): Payment = service.process(id)
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "references" && it.source.value == "method:Controller.get" && it.target.value == "field:Controller.service"
        })
    }

    @Test
    fun `resolves unqualified calls to methods declared later`() {
        val root = createTempDirectory("arianna-jvm-unqualified-call-")
        root.resolve("Service.kt").writeText(
            """
            class Service {
                fun get() = helper()
                private fun helper() = "value"
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "calls" &&
                it.source.value == "method:Service.get" &&
                it.target.value == "method:Service.helper"
        })
    }

    @Test
    fun `extracts multiline Kotlin method signatures with evidence range`() {
        val root = createTempDirectory("arianna-jvm-multiline-method-")
        root.resolve("Service.kt").writeText(
            """
            class Service {
                fun process(
                    paymentId: String,
                    currency: String
                ): String = paymentId + currency
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")
        val method = analysis.entities.single { it.id.value == "method:Service.process" }

        assertEquals(2, method.evidence?.startLine)
        assertEquals(5, method.evidence?.endLine)
        assertTrue(method.content?.contains("paymentId") == true)
    }

    @Test
    fun `links test calls with tested_by`() {
        val root = createTempDirectory("arianna-jvm-tested-by-")
        val production = root.resolve("src/main/kotlin/PaymentService.kt")
        production.parent.createDirectories()
        production.writeText(
            """
            class PaymentService {
                fun process(id: String): String = id
            }
            """.trimIndent()
        )
        val test = root.resolve("src/test/kotlin/PaymentServiceTest.kt")
        test.parent.createDirectories()
        test.writeText(
            """
            class PaymentServiceTest {
                private val service: PaymentService = PaymentService()
                fun processPayment() = service.process("payment")
            }
            """.trimIndent()
        )

        val analysis = JvmStructuralAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "class:PaymentServiceTest" && it.kind == "test" })
        assertTrue(analysis.relations.any {
            it.type == "tested_by" &&
                it.source.value == "method:PaymentService.process" &&
                it.target.value == "method:PaymentServiceTest.processPayment" &&
                it.evidence?.file == "src/test/kotlin/PaymentServiceTest.kt"
        })
    }

    @Test
    fun `end to end signature change finds caller in impact report`() {
        val root = createTempDirectory("arianna-jvm-impact-")
        val sourceFile = root.resolve("src/main/kotlin/fixture/PaymentService.kt")
        val callerFile = root.resolve("src/main/kotlin/fixture/PaymentController.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package fixture
            interface PaymentService { fun process(paymentId: String): Payment }
            data class Payment(val id: String)
            """.trimIndent()
        )
        callerFile.writeText(
            """
            package fixture
            class PaymentController(private val service: PaymentService) {
                fun get(id: String): Payment = service.process(id)
            }
            """.trimIndent()
        )
        val source = TestSource(root)
        SQLiteKnowledgeStore(root.resolve("knowledge.db")).use { store ->
            SpringAwareIndexer().index(source, store)
            sourceFile.writeText(
                """
                package fixture
                interface PaymentService { fun process(paymentId: String, currency: String): Payment }
                data class Payment(val id: String)
                """.trimIndent()
            )
            callerFile.writeText(
                """
                package fixture
                class PaymentController(private val service: PaymentService) {
                    fun get(id: String, currency: String): Payment = service.process(id, currency)
                }
                """.trimIndent()
            )
            SpringAwareIndexer().indexOverlay(source, store)

            val baseline = store.getCurrentSnapshot(root.toString())!!
            val overlay = store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE)!!
            val baseEntities = store.entitiesForSnapshot(baseline.id)
            val overlayEntities = store.entitiesForSnapshot(overlay.id)
            val baseRelations = store.relationsForSnapshot(baseline.id)
            val overlayRelations = store.relationsForSnapshot(overlay.id)
            val diff = SnapshotComparator.compare(baseline.revision, overlay.revision, baseEntities, overlayEntities, baseRelations, overlayRelations)
            val report = ImpactAnalyzer.analyze(diff, baseEntities, overlayEntities, baseRelations, overlayRelations)

            assertTrue(diff.entities.any { it.entityId.value == "method:PaymentService.process" })
            assertTrue(report.findings.any { it.category == "direct_callers" && it.entityId?.value == "method:PaymentController.get" })
            assertEquals("HEAD", baseline.revision)
            assertTrue(overlay.revision.startsWith("WORKING_TREE:"))
            assertTrue(overlayEntities.filter { it.id.value == "method:PaymentService.process" }.all { it.evidence?.revision == overlay.revision })
            assertTrue(overlayRelations.filter { it.type == "calls" }.all { it.evidence?.revision == overlay.revision })
        }
    }

    private class TestSource(override val root: java.nio.file.Path) : Source {
        override fun repositoryStatus() = RepositoryStatus(
            root = root.toString(),
            head = "HEAD",
            branch = "main",
            indexedRevision = null,
            stagedFiles = emptyList(),
            modifiedFiles = emptyList(),
            untrackedFiles = emptyList(),
            deletedFiles = emptyList()
        )
    }
}
