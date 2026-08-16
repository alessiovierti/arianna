package dev.arianna.core.indexing

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentIndexerTest {
    @Test
    fun `indexes supported documents with bounded content and evidence`() {
        val root = createTempDirectory("arianna-docs-")
        root.resolve("README.md").writeText("# Arianna\n\nThe payment flow is documented here.\n")
        root.resolve("docs").createDirectories()
        root.resolve("docs/adr-001.adr").writeText("# Decision\nUse SQLite locally.\n")
        root.resolve("build/generated").createDirectories()
        root.resolve("build/generated/Generated.md").writeText("must not be indexed")
        root.resolve("src").createDirectories()
        root.resolve("src/Main.kt").writeText("class Main")

        val result = DocumentIndexer.analyze(root, "repo", "HEAD")

        assertEquals(2, result.entities.size)
        assertTrue(result.entities.any { it.id.value == "document:README.md" && it.content!!.contains("payment flow") })
        assertTrue(result.entities.any { it.id.value == "document:docs/adr-001.adr" && it.evidence?.revision == "HEAD" })
        assertTrue(result.entities.none { it.id.value == "document:build/generated/Generated.md" })
    }
}
