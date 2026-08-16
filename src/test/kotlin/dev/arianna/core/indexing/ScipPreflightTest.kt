package dev.arianna.core.indexing

import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScipPreflightTest {
    @Test
    fun `reports both tools available when version checks succeed`() {
        val runner = ExternalCommandRunner { _, _, _, _ -> ExternalCommandResult("version", "", 0) }

        val status = ScipPreflight(runner).check(Path.of("/tmp"), ScipIndexerConfig(timeout = Duration.ofSeconds(1)))

        assertTrue(status.ready)
        assertTrue(status.diagnostics.isEmpty())
    }

    @Test
    fun `accepts a launcher that exposes help but not version`() {
        val runner = ExternalCommandRunner { _, executable, arguments, _ ->
            if (executable == "scip-java" && arguments == listOf("--version")) {
                ExternalCommandResult("", "unknown option", 2)
            } else {
                ExternalCommandResult("help", "", 0)
            }
        }

        val status = ScipPreflight(runner).check(Path.of("/tmp"), ScipIndexerConfig(timeout = Duration.ofSeconds(1)))

        assertTrue(status.ready)
        assertTrue(status.diagnostics.isEmpty())
    }

    @Test
    fun `checks the Maven tool for Maven projects`() {
        val root = Files.createTempDirectory("arianna-maven-preflight")
        Files.createFile(root.resolve("pom.xml"))
        val executables = mutableListOf<String>()
        val runner = ExternalCommandRunner { _, executable, _, _ ->
            executables += executable
            ExternalCommandResult("version", "", 0)
        }

        val status = ScipPreflight(runner).check(root, ScipIndexerConfig(timeout = Duration.ofSeconds(1)))

        assertTrue(status.ready)
        assertTrue(status.buildToolAvailable)
        assertContains(executables, "mvn")
    }

    @Test
    fun `prefers the Gradle wrapper when present`() {
        val root = Files.createTempDirectory("arianna-gradle-preflight")
        Files.createFile(root.resolve("build.gradle.kts"))
        Files.createFile(root.resolve("gradlew"))
        val executables = mutableListOf<String>()
        val runner = ExternalCommandRunner { _, executable, _, _ ->
            executables += executable
            ExternalCommandResult("version", "", 0)
        }

        val status = ScipPreflight(runner).check(root, ScipIndexerConfig(timeout = Duration.ofSeconds(1)))

        assertTrue(status.ready)
        assertEquals("./gradlew", executables.last())
    }

    @Test
    fun `is not ready when the detected build tool fails`() {
        val root = Files.createTempDirectory("arianna-gradle-missing-preflight")
        Files.createFile(root.resolve("settings.gradle"))
        val runner = ExternalCommandRunner { _, executable, _, _ ->
            if (executable == "gradle") ExternalCommandResult("", "command not found", 127)
            else ExternalCommandResult("version", "", 0)
        }

        val status = ScipPreflight(runner).check(root, ScipIndexerConfig(timeout = Duration.ofSeconds(1)))

        assertFalse(status.ready)
        assertFalse(status.buildToolAvailable)
        assertTrue(status.diagnostics.any { it.contains("gradle") })
    }
}
