package dev.arianna.frameworks.spring

import dev.arianna.core.api.Source
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.model.SnapshotKind
import dev.arianna.storage.SQLiteKnowledgeStore
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpringAdapterTest {
    @Test
    fun `extracts components injection endpoints and events`() {
        val root = createTempDirectory("arianna-spring-")
        val source = root.resolve("src/main/kotlin/Payments.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @Service
            class PaymentService

            @Controller
            class PaymentController {
                @Bean
                fun paymentClient(): String = "client"

                @Autowired
                @Qualifier("primary")
                private PaymentService service;

                @GetMapping("/payments")
                fun list(): String = "ok"

                @EventListener
                fun on(event: PaymentCreated) {}
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "abc")

        assertTrue(analysis.entities.any { it.kind == "service" && it.qualifiedName == "PaymentService" })
        assertTrue(analysis.relations.any { it.type == "defines_bean" && it.target.value == "bean:PaymentController.paymentClient" })
        assertTrue(analysis.relations.any { it.type == "injects" && it.target.value == "class:PaymentService" })
        assertTrue(analysis.relations.any { it.type == "qualified_by" && it.target.value == "qualifier:primary" })
        assertTrue(analysis.relations.any { it.type == "exposes_endpoint" && it.target.value.contains("/payments") })
        assertTrue(analysis.relations.any { it.type == "handles_event" && it.target.value == "event:PaymentCreated" })
        assertTrue(analysis.relations.all { it.origin.name == "FRAMEWORK" })
    }

    @Test
    fun `does not treat event listener text or parser signatures as events`() {
        val root = createTempDirectory("arianna-spring-event-false-positive-")
        val source = root.resolve("src/main/kotlin/Parser.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            class Parser {
                val marker = "@EventListener"
                // @EventListener
                fun accepts(values: MutableMap<String, String>) {}

                @EventListener
                fun handles(event: PaymentCreated) {}
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "event:PaymentCreated" })
        assertTrue(analysis.entities.none { it.id.value == "event:MutableMap" })
    }

    @Test
    fun `indexes yaml and properties configuration keys`() {
        val root = createTempDirectory("arianna-spring-config-")
        val resources = root.resolve("src/main/resources")
        resources.createDirectories()
        resources.resolve("application.yml").writeText(
            """
            spring:
              datasource:
                url: jdbc:h2:mem:test
            server:
              port: 8080
            """.trimIndent()
        )
        resources.resolve("application.properties").writeText(
            "management.endpoints.web.exposure.include=health\n"
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "config:spring.datasource.url" })
        assertTrue(analysis.entities.any { it.id.value == "config:server.port" })
        assertTrue(analysis.entities.any { it.id.value == "config:management.endpoints.web.exposure.include" })
        assertTrue(analysis.relations.any { it.type == "configures" && it.source.value.endsWith("application.yml") })
        assertTrue(analysis.relations.all { it.origin.name == "FRAMEWORK" })
    }

    @Test
    fun `combines class request mapping with method mapping`() {
        val root = createTempDirectory("arianna-spring-path-")
        val source = root.resolve("src/main/kotlin/Controller.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @RequestMapping("/api")
            @Controller
            class PaymentController {
                @GetMapping("/payments/{id}")
                fun get(id: String): String = id
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "endpoint:get:/api/payments/{id}" })
        assertTrue(analysis.relations.any { it.type == "exposes_endpoint" && it.target.value == "endpoint:get:/api/payments/{id}" })
    }

    @Test
    fun `recognizes named path and value in spring mappings`() {
        val root = createTempDirectory("arianna-spring-named-mapping-")
        val source = root.resolve("src/main/kotlin/Controller.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @Controller
            class PaymentController {
                @GetMapping(path = "/payments")
                fun list(): String = "ok"

                @PostMapping(value = "/payments")
                fun create(): String = "ok"

                @RequestMapping(method = [RequestMethod.GET], path = "/search")
                fun search(): String = "ok"
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "endpoint:get:/payments" })
        assertTrue(analysis.entities.any { it.id.value == "endpoint:post:/payments" })
        assertTrue(analysis.entities.any { it.id.value == "endpoint:request:/search" })
    }

    @Test
    fun `recognizes unannotated kotlin constructor injection on a spring component`() {
        val root = createTempDirectory("arianna-spring-constructor-")
        val source = root.resolve("src/main/kotlin/Service.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @Service
            class PaymentService(private val repository: PaymentRepository)
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "injects" && it.source.value == "class:PaymentService" && it.target.value == "class:PaymentRepository" && it.origin.name == "FRAMEWORK"
        }, analysis.relations.toString())
    }

    @Test
    fun `recognizes unannotated java constructor injection on a spring component`() {
        val root = createTempDirectory("arianna-spring-java-constructor-")
        val source = root.resolve("src/main/java/Service.java")
        source.parent.createDirectories()
        source.writeText(
            """
            @Service
            public class PaymentService {
                public PaymentService(PaymentRepository repository) {}
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "injects" && it.source.value == "class:PaymentService" && it.target.value == "class:PaymentRepository" && it.origin.name == "FRAMEWORK"
        }, analysis.relations.toString())
    }

    @Test
    fun `preserves qualifier declared on a constructor parameter`() {
        val root = createTempDirectory("arianna-spring-constructor-qualifier-")
        val source = root.resolve("src/main/kotlin/Service.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @Service
            class PaymentService(@Qualifier("primary") private val repository: PaymentRepository)
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "qualified_by" && it.source.value == "class:PaymentService" && it.target.value == "qualifier:primary"
        }, analysis.relations.toString())
    }

    @Test
    fun `records dynamic spring wiring as unresolved`() {
        val root = createTempDirectory("arianna-spring-dynamic-")
        val source = root.resolve("src/main/kotlin/Dynamic.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @ConditionalOnProperty("feature.enabled")
            class DynamicWiring(private val context: ApplicationContext) {
                fun resolve(environment: String) = context.getBean("payment-" + environment)
            }
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        val unresolved = analysis.entities.filter { it.kind == "unresolved_spring" }
        assertTrue(unresolved.any { it.qualifiedName.contains("getBean") })
        assertTrue(unresolved.any { it.qualifiedName.contains("ConditionalOnProperty") })
        assertTrue(unresolved.all { entity -> analysis.relations.any {
            it.type == "unresolved" && it.target == entity.id && it.origin == dev.arianna.core.model.Origin.INFERRED && it.confidence == dev.arianna.core.model.Confidence.LOW
        } })
    }

    @Test
    fun `reports ambiguous bean injection without qualifier`() {
        val root = createTempDirectory("arianna-spring-ambiguity-")
        val source = root.resolve("src/main/kotlin/Configuration.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            @Configuration
            class PaymentConfiguration {
                @Bean
                fun primaryRepository(): PaymentRepository = PaymentRepository()

                @Bean
                fun backupRepository(): PaymentRepository = PaymentRepository()

                @Bean
                @Primary
                fun primarySpecialRepository(): SpecialRepository = SpecialRepository()

                @Bean
                fun backupSpecialRepository(): SpecialRepository = SpecialRepository()
            }

            @Service
            class PaymentService(private val repository: PaymentRepository)

            @Service
            class QualifiedPaymentService(@Qualifier("primary") private val repository: PaymentRepository)

            @Service
            class PrimaryPaymentService(private val repository: SpecialRepository)
            """.trimIndent()
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "ambiguous_injection" && it.source.value == "class:PaymentService" && it.origin == dev.arianna.core.model.Origin.INFERRED
        }, analysis.relations.toString())
        assertEquals(2, analysis.relations.count {
            it.type == "provides_bean_type" && it.target.value == "class:PaymentRepository"
        })
        assertTrue(analysis.relations.none {
            it.type == "ambiguous_injection" && it.source.value == "class:QualifiedPaymentService"
        }, analysis.relations.toString())
        assertTrue(analysis.relations.any {
            it.type == "qualified_by" && it.source.value == "class:QualifiedPaymentService" && it.target.value == "qualifier:primary"
        }, analysis.relations.toString())
        assertTrue(analysis.relations.none {
            it.type == "ambiguous_injection" && it.source.value == "class:PrimaryPaymentService"
        }, analysis.relations.toString())
    }

    @Test
    fun `indexes spring overlay without replacing baseline`() {
        val root = createTempDirectory("arianna-spring-overlay-")
        val sourceFile = root.resolve("src/main/kotlin/PaymentService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText("@Service\nclass PaymentService")
        val source = TestSource(root)
        SQLiteKnowledgeStore(root.resolve("index.db")).use { store ->
            SpringAwareIndexer().index(source, store)
            sourceFile.writeText("@Service\nclass RenamedPaymentService")

            val result = SpringAwareIndexer().indexOverlay(source, store)

            assertEquals("WORKING_TREE:HEAD", result.revision)
            assertEquals("HEAD", store.getCurrentSnapshot(root.toString())?.revision)
            val overlay = assertNotNull(store.getLatestSnapshot(root.toString(), SnapshotKind.WORKING_TREE))
            val overlayEntities = store.entitiesForSnapshot(overlay.id)
            assertTrue(overlayEntities.any { it.qualifiedName == "RenamedPaymentService" })
            assertTrue(overlayEntities.none { it.qualifiedName == "PaymentService" && it.kind == "class" })
            assertTrue(overlayEntities.filter { it.qualifiedName == "RenamedPaymentService" }.all { it.evidence?.revision == result.revision })
        }
    }

    @Test
    fun `reports progress for each indexing phase`() {
        val root = createTempDirectory("arianna-spring-progress-")
        val sourceFile = root.resolve("src/main/kotlin/PaymentService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText("@Service\nclass PaymentService")
        val progress = mutableListOf<IndexProgress>()

        SQLiteKnowledgeStore(root.resolve("index.db")).use { store ->
            SpringAwareIndexer().index(TestSource(root), store) { progress += it }
        }

        assertTrue(progress.isNotEmpty())
        assertEquals(0, progress.first().percent)
        assertEquals(100, progress.maxOf { it.percent })
        assertTrue(progress.map { it.stage }.containsAll(listOf("discover", "spring", "jvm", "modules", "documents", "publish")))
        assertTrue(progress.map { it.message }.any { it == "Analyzing HTTP routes" })
        assertTrue(progress.map { it.message }.any { it == "Linking documents to indexed entities" })
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
