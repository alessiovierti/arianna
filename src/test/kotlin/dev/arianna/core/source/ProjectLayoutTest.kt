package dev.arianna.core.source

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectLayoutTest {
    @Test
    fun `detects Gradle Kotlin projects`() {
        val root = createTempDirectory("arianna-gradle-")
        root.resolve("build.gradle.kts").createFile()

        val layout = ProjectLayout.detect(root)

        assertEquals(setOf(BuildSystem.GRADLE), layout.buildSystems)
        assertTrue(layout.isJvmProject)
    }

    @Test
    fun `detects Maven and Gradle when both descriptors exist`() {
        val root = createTempDirectory("arianna-mixed-")
        root.resolve("pom.xml").createFile()
        root.resolve("settings.gradle").createFile()

        assertEquals(setOf(BuildSystem.MAVEN, BuildSystem.GRADLE), ProjectLayout.detect(root).buildSystems)
    }

    @Test
    fun `does not classify arbitrary directory as JVM project`() {
        val root = createTempDirectory("arianna-empty-")
        root.resolve("nested").createDirectories()

        assertEquals(emptySet(), ProjectLayout.detect(root).buildSystems)
    }

    @Test
    fun `finds a nested Gradle build root when the repository wrapper is not a build`() {
        val root = createTempDirectory("arianna-nested-gradle-")
        root.resolve("settings.gradle").createFile()
        val backend = root.resolve("backend").createDirectories()
        backend.resolve("settings.gradle").createFile()
        backend.resolve("build.gradle").createFile()

        assertEquals(backend.toAbsolutePath().normalize(), ProjectLayout.scipBuildRoot(root))
    }

    @Test
    fun `keeps a normal Gradle root when it has its own build file`() {
        val root = createTempDirectory("arianna-root-gradle-")
        root.resolve("settings.gradle").createFile()
        root.resolve("build.gradle").createFile()
        root.resolve("backend").createDirectories().resolve("build.gradle").createFile()

        assertEquals(root.toAbsolutePath().normalize(), ProjectLayout.scipBuildRoot(root))
    }
}
