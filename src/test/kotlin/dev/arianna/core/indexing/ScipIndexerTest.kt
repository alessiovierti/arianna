package dev.arianna.core.indexing

import dev.arianna.core.api.Source
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.storage.SQLiteKnowledgeStore
import dev.arianna.core.model.SnapshotKind
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import dev.arianna.core.error.IndexingException
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.KnowledgeEntity
import java.time.Duration
import kotlin.io.path.readText

class ScipIndexerTest {
    @Test
    fun `promotes a cross-file external symbol when its definition is parsed later`() {
        val parser = ScipJsonParser()
        val parsed = parser.parse(
            """
                {
                  "documents": [
                    {
                      "relativePath": "src/Caller.java",
                      "symbols": [{"symbol":"java . Caller#run().","kind":"Method"}],
                      "occurrences": [
                        {"range":[1,0,1,4],"symbol":"java . Caller#run().","symbolRoles":1},
                        {"range":[2,0,2,10],"symbol":"java . Callee#process().","symbolRoles":0}
                      ]
                    },
                    {
                      "relativePath": "src/Callee.java",
                      "symbols": [{"symbol":"java . Callee#process().","kind":"Method"}],
                      "occurrences": [
                        {"range":[1,0,1,10],"symbol":"java . Callee#process().","symbolRoles":1}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            "repo",
            "head",
            "test"
        )

        assertEquals("method", parsed.entities.single { it.id.value == "scip:java . Callee#process()." }.kind)
    }

    @Test
    fun `rejects SCIP JSON without documents`() {
        assertFailsWith<IndexingException> {
            ScipJsonParser().parse("{}", "repo", "head", "test")
        }
    }

    @Test
    fun `java signature fixture contains a caller change between revisions`() {
        val base = Path.of(requireNotNull(javaClass.getResource("/fixtures/java-signature/base/PaymentService.java")).toURI()).readText()
        val head = Path.of(requireNotNull(javaClass.getResource("/fixtures/java-signature/head/PaymentService.java")).toURI()).readText()
        val caller = Path.of(requireNotNull(javaClass.getResource("/fixtures/java-signature/head/PaymentController.java")).toURI()).readText()

        assertTrue(base.contains("process(String paymentId)"))
        assertTrue(head.contains("process(String paymentId, String currency)"))
        assertTrue(caller.contains("service.process(paymentId, currency)"))
    }

    @Test
    fun `imports SCIP documents symbols and reference occurrences`() {
        val database = createTempDirectory("arianna-scip-").resolve("knowledge.db")
        val source = FakeSource()
        val runner = FakeCommandRunner(
            scipJson = """
                {
                  "documents": [
                    {
                      "relativePath": "src/Caller.java",
                      "symbols": [{"symbol": "java . Main#run().", "kind": "Method"}],
                      "occurrences": [
                        {"range": [2, 0, 4], "symbol": "java . Main#run().", "symbolRoles": 1},
                        {"range": [8, 4, 8], "symbol": "java . Main#run().", "symbolRoles": 0}
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )

        SQLiteKnowledgeStore(database).use { store ->
            val result = ScipIndexer(
                ScipIndexerConfig(timeout = Duration.ofSeconds(1)),
                runner
            ).index(source, store)

            assertEquals(1, result.indexedFiles)
            assertTrue(store.findEntities("Main#run").any { it.kind == "method" })
            assertEquals(1, store.findRelations("scip:java . Main#run().").count { it.type == "references" })
            assertTrue(store.findRelations("file:src/Caller.java").any { it.type == "defines" && it.target.value == "scip:java . Main#run()." })
            assertEquals(listOf("scip-java", "scip", "scip-java", "scip"), runner.executables)
        }
    }

    @Test
    fun `imports SCIP symbol relationships as static implementations`() {
        val database = createTempDirectory("arianna-scip-relationships-").resolve("knowledge.db")
        val source = FakeSource()
        val runner = FakeCommandRunner(
            scipJson = """
                {
                  "documents": [{
                    "relativePath": "src/Payment.java",
                    "symbols": [
                      {
                        "symbol": "java . PaymentService#process().",
                        "kind": "Method",
                        "relationships": [{"symbol":"java . PaymentPort#process().","isImplementation":true}]
                      },
                      {"symbol":"java . PaymentPort#process().","kind":"Method"}
                    ],
                    "occurrences": []
                  }]
                }
            """.trimIndent()
        )

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(source, store)

            val relation = store.findRelations("scip:java . PaymentService#process().")
                .single { it.type == "implements" }
            assertEquals("scip:java . PaymentPort#process().", relation.target.value)
            assertEquals("STATIC", relation.origin.name)
            assertEquals("HIGH", relation.confidence.name)
            assertEquals("src/Payment.java", relation.evidence?.file)
        }
    }

    @Test
    fun `attributes SCIP references to the most specific enclosing definition`() {
        val database = createTempDirectory("arianna-scip-callers-").resolve("knowledge.db")
        val source = FakeSource()
        val runner = FakeCommandRunner(
            """
                {
                  "documents": [{
                    "relativePath": "src/Caller.java",
                    "symbols": [
                      {"symbol":"java . Caller#run().","kind":"Method"},
                      {"symbol":"java . Callee#process().","kind":"Method"}
                    ],
                    "occurrences": [
                      {"range":[1,0,10,1],"symbol":"java . Caller#run().","symbolRoles":1},
                      {"range":[5,8,5,20],"symbol":"java . Callee#process().","symbolRoles":0}
                    ]
                  }]
                }
            """.trimIndent()
        )

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(source, store)

            val relation = store.findRelations("scip:java . Caller#run().")
                .single { it.target.value == "scip:java . Callee#process()." }
            assertEquals("references", relation.type)
            assertEquals("src/Caller.java", relation.evidence?.file)
            assertEquals(6, relation.evidence?.startLine)
        }
    }

    @Test
    fun `does not replace current snapshot when SCIP command fails`() {
        val database = createTempDirectory("arianna-scip-failure-").resolve("knowledge.db")
        val source = FakeSource()
        SQLiteKnowledgeStore(database).use { store ->
            store.replaceSnapshot(
                source.repositoryStatus().root,
                "old-revision",
                sequenceOf(KnowledgeEntity(EntityId("file:old"), "file", "old", Evidence("repo", "old-revision", "old", analyzerVersion = "test"))),
                emptySequence()
            )
            val failingRunner = ExternalCommandRunner { _, _, _, _ -> ExternalCommandResult("", "compiler failed", 1) }

            assertFailsWith<IndexingException> { ScipIndexer(commandRunner = failingRunner).index(source, store) }
            assertEquals("old-revision", store.currentRevision(source.repositoryStatus().root))
        }
    }

    @Test
    fun `publishes SCIP overlay without replacing baseline`() {
        val database = createTempDirectory("arianna-scip-overlay-").resolve("knowledge.db")
        val source = FakeSource()
        val runner = FakeCommandRunner(
            """{"documents":[{"relativePath":"src/Payment.java","symbols":[{"symbol":"java . Payment#process().","kind":"Method"}],"occurrences":[{"range":[3,0,3],"symbol":"java . Payment#process().","symbolRoles":1}]}]}"""
        )

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(source, store)
            val result = ScipIndexer(commandRunner = runner).indexOverlay(source, store)

            assertEquals("WORKING_TREE:abc123", result.revision)
            assertEquals("abc123", store.getCurrentSnapshot(source.repositoryStatus().root)?.revision)
            assertEquals(
                "WORKING_TREE:abc123",
                store.getLatestSnapshot(source.repositoryStatus().root, SnapshotKind.WORKING_TREE)?.revision
            )
            val overlaySnapshot = store.getLatestSnapshot(source.repositoryStatus().root, SnapshotKind.WORKING_TREE)!!
            assertTrue(store.entitiesForSnapshot(overlaySnapshot.id).all { it.evidence?.revision == result.revision })
            assertTrue(store.relationsForSnapshot(overlaySnapshot.id).all { it.evidence?.revision == result.revision })
        }
    }

    @Test
    fun `runs build tool preflight before SCIP commands`() {
        val root = createTempDirectory("arianna-scip-maven-")
        java.nio.file.Files.createFile(root.resolve("pom.xml"))
        val database = root.resolve("knowledge.db")
        val source = FakeSource(root)
        val runner = FakeCommandRunner(
            """{"documents":[]}"""
        )

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(source, store)
        }

        assertEquals(listOf("scip-java", "scip", "mvn", "scip-java", "scip"), runner.executables)
    }

    @Test
    fun `runs SCIP from a nested Gradle build and restores repository relative paths`() {
        val root = createTempDirectory("arianna-scip-nested-gradle-")
        java.nio.file.Files.createFile(root.resolve("settings.gradle"))
        val backend = root.resolve("backend")
        java.nio.file.Files.createDirectories(backend)
        java.nio.file.Files.createFile(backend.resolve("settings.gradle"))
        java.nio.file.Files.createFile(backend.resolve("build.gradle"))
        val database = root.resolve("knowledge.db")
        val source = FakeSource(root)
        val runner = FakeCommandRunner(
            """{"documents":[{"relativePath":"src/Caller.java","symbols":[],"occurrences":[] }]}"""
        )

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(source, store)
            assertTrue(store.findEntities("backend/src/Caller.java").any { it.id.value == "file:backend/src/Caller.java" })
        }

        assertTrue(runner.directories.drop(1).all { it == backend.toAbsolutePath().normalize() })
    }

    @Test
    fun `cleans generated SCIP index and preserves a pre-existing one`() {
        val root = createTempDirectory("arianna-scip-cleanup-")
        val indexPath = root.resolve("index.scip")
        val original = "existing-index".toByteArray()
        java.nio.file.Files.write(indexPath, original)
        val database = root.resolve("knowledge.db")
        val runner = object : ExternalCommandRunner {
            override fun run(
                directory: Path,
                executable: String,
                arguments: List<String>,
                timeout: Duration
            ): ExternalCommandResult {
                if (executable == "scip-java" && arguments == listOf("index", "--output", "index.scip")) {
                    java.nio.file.Files.writeString(indexPath, "generated-index")
                }
                if (executable == "scip" && arguments.firstOrNull() == "print") {
                    assertTrue(java.nio.file.Files.exists(indexPath))
                }
                return ExternalCommandResult("{\"documents\":[]}", "", 0)
            }
        }

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(FakeSource(root), store)
        }

        assertEquals(original.toList(), java.nio.file.Files.readAllBytes(indexPath).toList())
    }

    @Test
    fun `removes generated SCIP index when none existed before`() {
        val root = createTempDirectory("arianna-scip-cleanup-new-")
        val indexPath = root.resolve("index.scip")
        val database = root.resolve("knowledge.db")
        val runner = object : ExternalCommandRunner {
            override fun run(
                directory: Path,
                executable: String,
                arguments: List<String>,
                timeout: Duration
            ): ExternalCommandResult {
                if (executable == "scip-java" && arguments == listOf("index", "--output", "index.scip")) {
                    java.nio.file.Files.writeString(indexPath, "generated-index")
                }
                return ExternalCommandResult("{\"documents\":[]}", "", 0)
            }
        }

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(commandRunner = runner).index(FakeSource(root), store)
        }

        assertTrue(!java.nio.file.Files.exists(indexPath))
    }

    @Test
    fun `passes a custom index file name to scip java and cleans it`() {
        val root = createTempDirectory("arianna-scip-custom-index-")
        val indexPath = root.resolve("custom-index.scip")
        val database = root.resolve("knowledge.db")
        val runner = object : ExternalCommandRunner {
            override fun run(
                directory: Path,
                executable: String,
                arguments: List<String>,
                timeout: Duration
            ): ExternalCommandResult {
                if (executable == "scip-java" && arguments == listOf("index", "--output", "custom-index.scip")) {
                    java.nio.file.Files.writeString(indexPath, "generated-index")
                }
                return ExternalCommandResult("{\"documents\":[]}", "", 0)
            }
        }

        SQLiteKnowledgeStore(database).use { store ->
            ScipIndexer(
                ScipIndexerConfig(indexFileName = "custom-index.scip"),
                runner
            ).index(FakeSource(root), store)
        }

        assertTrue(!java.nio.file.Files.exists(indexPath))
    }

    @Test
    fun `invokes real process runner with a compatible SCIP launcher contract`() {
        val root = createTempDirectory("arianna-scip-process-")
        val scipJava = root.resolve("fake-scip-java.sh")
        val scip = root.resolve("fake-scip.sh")
        java.nio.file.Files.writeString(
            scipJava,
            """#!/bin/sh
if [ "${'$'}1" = "--version" ]; then echo "scip-java-test 0.1"; exit 0; fi
if [ "${'$'}1" = "index" ]; then printf '%s' 'generated' > "${'$'}3"; exit 0; fi
exit 1
""".trimIndent()
        )
        java.nio.file.Files.writeString(
            scip,
            """#!/bin/sh
if [ "${'$'}1" = "--version" ]; then echo "scip-test 0.1"; exit 0; fi
if [ "${'$'}1" = "print" ]; then printf '%s' '{"documents":[{"relativePath":"src/Main.java","symbols":[{"symbol":"java . Main#run().","kind":"Method"}],"occurrences":[{"range":[1,0,1,4],"symbol":"java . Main#run().","symbolRoles":1}]}]}'; exit 0; fi
exit 1
""".trimIndent()
        )
        check(scipJava.toFile().setExecutable(true))
        check(scip.toFile().setExecutable(true))
        val database = root.resolve("knowledge.db")

        SQLiteKnowledgeStore(database).use { store ->
            val result = ScipIndexer(
                ScipIndexerConfig(
                    scipJavaExecutable = scipJava.toString(),
                    scipExecutable = scip.toString(),
                    timeout = Duration.ofSeconds(2)
                )
            ).index(FakeSource(root), store)

            assertEquals(1, result.indexedFiles)
            assertTrue(store.findEntities("Main#run").any { it.kind == "method" })
            assertTrue(!java.nio.file.Files.exists(root.resolve("index.scip")))
        }
    }

    private class FakeSource(
        override val root: Path = Path.of("/tmp/arianna-scip-fixture")
    ) : Source {

        override fun repositoryStatus() = RepositoryStatus(
            root = root.toString(),
            head = "abc123",
            branch = "main",
            indexedRevision = null,
            stagedFiles = emptyList(),
            modifiedFiles = emptyList(),
            untrackedFiles = emptyList(),
            deletedFiles = emptyList()
        )
    }

    private class FakeCommandRunner(
        private val scipJson: String
    ) : ExternalCommandRunner {
        val executables = mutableListOf<String>()
        val directories = mutableListOf<Path>()

        override fun run(
            directory: Path,
            executable: String,
            arguments: List<String>,
            timeout: Duration
        ): ExternalCommandResult {
            executables += executable
            directories.add(directory)
            return if (executable == "scip") {
                ExternalCommandResult(scipJson, "", 0)
            } else {
                ExternalCommandResult("", "", 0)
            }
        }
    }
}
