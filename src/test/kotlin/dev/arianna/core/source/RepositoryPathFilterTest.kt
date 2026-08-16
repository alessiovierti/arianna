package dev.arianna.core.source

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryPathFilterTest {
    @Test
    fun `bootstraps arianna ignore from gitignore`() {
        val root = createTempDirectory("arianna-ignore-bootstrap-")
        root.resolve(".gitignore").writeText("generated/\n*.generated\n!generated/keep.txt\n")

        RepositoryPathFilter.ensureIgnoreFile(root)

        assertTrue(root.resolve(".arianna/ignore").exists())
        assertTrue(RepositoryPathFilter.isIgnored(root, root.resolve("generated/output.txt")))
        assertTrue(RepositoryPathFilter.isIgnored(root, root.resolve("src/Thing.generated")))
        assertFalse(RepositoryPathFilter.isIgnored(root, root.resolve("generated/keep.txt")))
    }

    @Test
    fun `uses only arianna ignore and supports gitignore basename patterns`() {
        val root = createTempDirectory("arianna-ignore-rules-")
        root.resolve(".arianna").createDirectories()
        root.resolve(".arianna/ignore").writeText("node_modules/\n*.secret\n!frontend/public/keep.secret\n")
        root.resolve("arianna").createDirectories()
        root.resolve("arianna/.ignore").writeText("src/test/\n")

        assertTrue(RepositoryPathFilter.isIgnored(root, root.resolve("frontend/node_modules/pkg/index.js")))
        assertTrue(RepositoryPathFilter.isIgnored(root, root.resolve("backend/config.secret")))
        assertFalse(RepositoryPathFilter.isIgnored(root, root.resolve("frontend/public/keep.secret")))
        assertFalse(RepositoryPathFilter.isIgnored(root, root.resolve("src/test/Fixture.kt")))
    }
}
