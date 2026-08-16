package dev.arianna.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.source.LocalGitRepository
import dev.arianna.storage.SQLiteKnowledgeStore

class MainCliTest {
    @Test
    fun `terminal progress explains active phase and elapsed time`() {
        val output = ByteArrayOutputStream()
        val progress = TerminalIndexProgress(
            PrintStream(output),
            nowNanos = { 0L },
            heartbeatMillis = 60_000
        )

        progress.onProgress(IndexProgress("documents", 5, 7, "Indexing documents and configuration"))
        progress.finish()

        val rendered = output.toString()
        assertContains(rendered, "71%")
        assertContains(rendered, "[documents]")
        assertContains(rendered, "0s •")
        assertContains(rendered, "working")
        assertContains(rendered, "\u001B[2K")
    }

    @Test
    fun `elapsed time formatting remains compact`() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("2m 3s", formatElapsed(123))
        assertEquals("1h 1m 1s", formatElapsed(3661))
    }

    @Test
    fun `parses query term with optional path`() {
        assertEquals("PaymentService", queryArgument(listOf("PaymentService", "--json")))
        assertEquals("PaymentService", queryArgument(listOf("PaymentService", "--path", "/tmp/repository", "--json")))
        assertEquals(null, queryStringOption(listOf("PaymentService", "--json"), "--kind"))
        assertEquals("document", queryStringOption(listOf("--kind", "document"), "--kind"))
        assertEquals("abc", queryStringOption(listOf("Payment.process", "--revision", "abc"), "--revision"))
        assertEquals("high", queryStringOption(listOf("Payment.process", "--confidence", "high"), "--confidence"))
    }

    @Test
    fun `accepts positional repository path for change commands`() {
        assertEquals(Path.of("/tmp/repository").toAbsolutePath().normalize(), diffRepositoryPath(listOf("--working-tree", "/tmp/repository", "--json")))
        assertEquals(Path.of(".").toAbsolutePath().normalize(), diffRepositoryPath(listOf("--base", "abc", "--head", "def", "--json")))
        assertEquals(Path.of("/tmp/repository").toAbsolutePath().normalize(), diffRepositoryPath(listOf("--base", "abc", "--head", "def", "/tmp/repository")))
    }

    @Test
    fun `rejects baseline indexing on a dirty git working tree`() {
        val repository = Files.createTempDirectory("arianna-cli-dirty-index-")
        try {
            runGit(repository, "init", "-q")
            runGit(repository, "config", "user.email", "test@example.invalid")
            runGit(repository, "config", "user.name", "Arianna Test")
            repository.resolve("Main.java").writeText("class Main {}\n")
            runGit(repository, "add", ".")
            runGit(repository, "commit", "-q", "-m", "baseline")
            repository.resolve("Main.java").writeText("class Main { void changed() {} }\n")

            assertFailsWith<RuntimeException> {
                ensureBaselineIndexAllowed(LocalGitRepository(repository), workingTree = false)
            }
            ensureBaselineIndexAllowed(LocalGitRepository(repository), workingTree = true)
        } finally {
            Files.walk(repository).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `requires a baseline before indexing a working tree overlay`() {
        val directory = Files.createTempDirectory("arianna-cli-overlay-baseline-")
        val database = directory.resolve("knowledge.db")
        SQLiteKnowledgeStore(database).use { store ->
            assertFailsWith<RuntimeException> {
                ensureBaselineSnapshotForOverlay(store, directory)
            }
        }
    }

    @Test
    fun `indexes repository when path is used as the command`() {
        val repository = Files.createTempDirectory("arianna-cli-default-index-")
        try {
            runGit(repository, "init", "-q")
            runGit(repository, "config", "user.email", "test@example.invalid")
            runGit(repository, "config", "user.name", "Arianna Test")
            repository.resolve("Main.java").writeText("class Main { String value() { return \"ok\"; } }\n")
            runGit(repository, "add", ".")
            runGit(repository, "commit", "-q", "-m", "baseline")

            val output = runMain(repository.toString(), "--json")

            assertContains(output, "\"indexedFiles\"")
            assertContains(output, "\"indexedEntities\"")
            assertTrue(repository.resolve(".arianna/knowledge.db").toFile().exists())
        } finally {
            Files.walk(repository).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `impact base head materializes and analyzes two git revisions`() {
        val repository = Files.createTempDirectory("arianna-cli-pr-")
        try {
            runGit(repository, "init", "-q")
            runGit(repository, "config", "user.email", "test@example.invalid")
            runGit(repository, "config", "user.name", "Arianna Test")
            val sourceDirectory = repository.resolve("src/main/java/fixture").also(Path::createDirectories)
            sourceDirectory.resolve("PaymentService.java").writeText(
                """
                package fixture;
                class PaymentService {
                    String process() { return "ok"; }
                }
                """.trimIndent()
            )
            sourceDirectory.resolve("PaymentController.java").writeText(
                """
                package fixture;
                class PaymentController {
                    private final PaymentService service = new PaymentService();
                    String get() { return service.process(); }
                }
                """.trimIndent()
            )
            runGit(repository, "add", ".")
            runGit(repository, "commit", "-q", "-m", "base")
            val base = runGit(repository, "rev-parse", "HEAD").trim()

            sourceDirectory.resolve("PaymentService.java").writeText(
                """
                package fixture;
                class PaymentService {
                    String process(String id) { return id; }
                }
                """.trimIndent()
            )
            sourceDirectory.resolve("PaymentController.java").writeText(
                """
                package fixture;
                class PaymentController {
                    private final PaymentService service = new PaymentService();
                    String get() { return service.process("known"); }
                }
                """.trimIndent()
            )
            runGit(repository, "add", ".")
            runGit(repository, "commit", "-q", "-m", "head")
            val head = runGit(repository, "rev-parse", "HEAD").trim()

            val report = runMain("impact", "--base", base, "--head", head, "--path", repository.toString(), "--json")
            assertContains(report, "\"baseRevision\":\"$base\"")
            assertContains(report, "\"overlayRevision\":\"$head\"")
            assertContains(report, "changed_entity")
            assertTrue(report.contains("PaymentService.process"))
            assertContains(report, "\"type\":\"calls\"")
            assertContains(report, "\"confidence\":\"medium\"")

            val plan = runMain("plan-refactor", "--base", base, "--head", head, "--path", repository.toString(), "--json")
            assertContains(plan, "\"baseRevision\":\"$base\"")
            assertContains(plan, "\"externalVerificationRequired\":true")

            val verification = runMain("verify-change", "--base", base, "--head", head, "--path", repository.toString(), "--json")
            assertContains(verification, "\"baseRevision\":\"$base\"")
            assertContains(verification, "\"externalVerificationRequired\":true")
        } finally {
            Files.walk(repository).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun runMain(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        val previousOut = System.out
        try {
            System.setOut(PrintStream(output))
            main(arguments.toList().toTypedArray())
        } finally {
            System.setOut(previousOut)
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun runGit(repository: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}
