package dev.arianna.core.indexing

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmModuleAdapterTest {
    @Test
    fun `parses nested groovy settings without swallowing following includes`() {
        val root = Files.createTempDirectory("arianna-gradle-settings-")
        root.resolve("settings.gradle").writeText("rootProject.name = 'collezionista'\ninclude 'backend'\n")
        root.resolve("backend/settings.gradle").apply {
            parent.createDirectories()
            writeText(
                """
                rootProject.name = 'backend'
                include 'common'
                include 'source'
                include(':db', ':api')
                include 'cache', 'auth'
                """.trimIndent()
            )
        }

        val analysis = JvmModuleAdapter().analyze(root, "repo", "HEAD")
        val names = analysis.entities.map { it.qualifiedName }.toSet()

        assertEquals(
            setOf("backend", "backend:common", "backend:source", "backend:db", "backend:api", "backend:cache", "backend:auth"),
            names
        )
        assertTrue(names.none { it.contains("include") || it.contains("\n") || it.contains("'") })
    }

    @Test
    fun `indexes declared gradle modules and their files`() {
        val root = createTempDirectory("arianna-modules-")
        root.resolve("settings.gradle.kts").writeText("include(\":api\", \":service\")")
        root.resolve("api/src/main/kotlin/Api.kt").also {
            it.parent.createDirectories()
            it.writeText("package fixture\nclass Api")
        }
        root.resolve("service/src/main/kotlin/Service.kt").also {
            it.parent.createDirectories()
            it.writeText("package fixture\nclass Service")
        }
        root.resolve("service/build.gradle.kts").writeText("dependencies { implementation(project(\":api\")) }")

        val analysis = JvmModuleAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "module:api" })
        assertTrue(analysis.entities.any { it.id.value == "module:service" })
        assertTrue(analysis.relations.any { it.source.value == "module:api" && it.target.value == "file:api/src/main/kotlin/Api.kt" })
        assertTrue(analysis.relations.any { it.type == "depends_on" && it.source.value == "module:service" && it.target.value == "module:api" })
        assertTrue(analysis.relations.all { it.origin == dev.arianna.core.model.Origin.DECLARED })
    }

    @Test
    fun `indexes declared maven module`() {
        val root = createTempDirectory("arianna-maven-module-")
        root.resolve("pom.xml").writeText("<project><modules><module>api</module></modules></project>")
        root.resolve("api/src/main/java/Api.java").also {
            it.parent.createDirectories()
            it.writeText("class Api {}")
        }

        val analysis = JvmModuleAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "module:api" })
        assertTrue(analysis.relations.any { it.target.value == "file:api/src/main/java/Api.java" })
    }

    @Test
    fun `indexes groovy includes and nested settings modules`() {
        val root = createTempDirectory("arianna-nested-modules-")
        root.resolve("settings.gradle").writeText("rootProject.name = 'app'\ninclude 'backend'")
        root.resolve("backend/settings.gradle").also {
            it.parent.createDirectories()
            it.writeText("rootProject.name = 'backend'\ninclude 'api', 'common'")
        }
        root.resolve("backend/api/src/main/kotlin/Api.kt").also {
            it.parent.createDirectories()
            it.writeText("class Api")
        }

        val analysis = JvmModuleAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "module:backend" })
        assertTrue(analysis.entities.any { it.id.value == "module:backend:api" })
        assertTrue(analysis.relations.any { it.source.value == "module:backend:api" && it.target.value == "file:backend/api/src/main/kotlin/Api.kt" })
    }

    @Test
    fun `links maven module dependency`() {
        val root = createTempDirectory("arianna-maven-dependency-")
        root.resolve("pom.xml").writeText(
            """
            <project><modules><module>api</module><module>service</module></modules></project>
            """.trimIndent()
        )
        root.resolve("api/pom.xml").also {
            it.parent.createDirectories()
            it.writeText("<project><artifactId>api</artifactId></project>")
        }
        root.resolve("service/pom.xml").also {
            it.parent.createDirectories()
            it.writeText("<project><artifactId>service</artifactId><dependencies><dependency><artifactId>api</artifactId></dependency></dependencies></project>")
        }

        val analysis = JvmModuleAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.relations.any {
            it.type == "depends_on" && it.source.value == "module:service" && it.target.value == "module:api"
        })
    }
}
