package dev.arianna.core.source

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalDirectorySourceTest {
    @Test
    fun `provides deterministic local revisions and ignores Arianna data`() {
        val root = createTempDirectory("arianna-local-source-")
        val source = LocalDirectorySource(root)
        root.resolve("src").createDirectories()
        root.resolve("src/Main.kt").writeText("class Main")
        root.resolve(".arianna").createDirectories()
        root.resolve(".arianna/knowledge.db").writeText("ignored")
        root.resolve(".gradle-local/cache").createDirectories()
        root.resolve(".gradle-local/cache/state.bin").writeText("ignored")
        root.resolve("build/generated").createDirectories()
        root.resolve("build/generated/Generated.kt").writeText("generated")

        val first = source.repositoryStatus().head
        val firstWorkingTree = source.workingTreeRevision()
        root.resolve(".arianna/knowledge.db").writeText("ignored but changed")
        root.resolve(".gradle-local/cache/state.bin").writeText("ignored but changed")
        root.resolve("build/generated/Generated.kt").writeText("generated but changed")
        assertEquals(first, source.repositoryStatus().head)
        assertEquals(firstWorkingTree, source.workingTreeRevision())

        root.resolve("src/Main.kt").writeText("class Main2")
        assertNotEquals(first, source.repositoryStatus().head)
        assertNotEquals(firstWorkingTree, source.workingTreeRevision())
        assertTrue(source.repositoryStatus().stagedFiles.isEmpty())
    }
}
