package dev.arianna.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import java.nio.file.Path
import dev.arianna.core.model.FileChangeKind
import kotlin.io.path.readText
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class LocalGitRepositoryTest {
    @Test
    fun `parses repository metadata and porcelain status`() {
        val runner = FakeGitCommandRunner(
            mapOf(
                listOf("rev-parse", "--show-toplevel") to "/tmp/project\n",
                listOf("rev-parse", "HEAD") to "abc123\n",
                listOf("branch", "--show-current") to "main\n",
                listOf("status", "--porcelain=v1") to """M  src/Main.kt
 M src/Other.kt
?? README.md
D  deleted.txt
?? .arianna/knowledge.db
"""
            )
        )

        val status = LocalGitRepository(Path.of("/tmp/project/subdir"), runner).status()

        assertEquals("/tmp/project", status.root)
        assertEquals("abc123", status.head)
        assertEquals("main", status.branch)
        assertEquals(listOf("src/Main.kt", "deleted.txt"), status.stagedFiles)
        assertEquals(listOf("src/Other.kt"), status.modifiedFiles)
        assertEquals(listOf("README.md"), status.untrackedFiles)
        assertEquals(listOf("deleted.txt"), status.deletedFiles)
    }

    @Test
    fun `fails with a useful error outside a git repository`() {
        val runner = FakeGitCommandRunner(
            failures = mapOf(listOf("rev-parse", "--show-toplevel") to "not a git repository")
        )

        val error = assertFailsWith<GitCommandException> {
            LocalGitRepository(Path.of("/tmp/project"), runner).status()
        }

        assertTrue(error.message!!.contains("not a git repository"))
    }

    @Test
    fun `builds a base to working tree diff including rename and untracked files`() {
        val runner = FakeGitCommandRunner(
            mapOf(
                listOf("rev-parse", "--show-toplevel") to "/tmp/project\n",
                listOf("rev-parse", "HEAD") to "abc123\n",
                listOf("branch", "--show-current") to "main\n",
                listOf("status", "--porcelain=v1") to " M src/Payment.kt\n",
                listOf("diff", "HEAD", "--name-status", "--find-renames", "--") to "M\tsrc/Payment.kt\nR100\tsrc/Old.kt\tsrc/New.kt\nD\tsrc/Deleted.kt\n",
                listOf("ls-files", "--others", "--exclude-standard") to "README.md\n.arianna/knowledge.db\n"
            )
        )

        val diff = LocalGitRepository(Path.of("/tmp/project"), runner).workingTreeDiff()

        assertEquals("abc123", diff.revisions.base)
        assertEquals("WORKING_TREE", diff.revisions.head)
        assertEquals(FileChangeKind.MODIFIED, diff.files.first { it.path == "src/Payment.kt" }.kind)
        assertEquals("src/Old.kt", diff.files.first { it.path == "src/New.kt" }.previousPath)
        assertEquals(FileChangeKind.DELETED, diff.files.first { it.path == "src/Deleted.kt" }.kind)
        assertEquals(FileChangeKind.ADDED, diff.files.first { it.path == "README.md" }.kind)
        assertTrue(diff.files.none { it.path.startsWith(".arianna") })
    }

    @Test
    fun `materializes a revision into an isolated temporary source`() {
        val runner = FakeGitCommandRunner(
            mapOf(
                listOf("ls-tree", "-r", "--name-only", "abc123") to "README.md\nsrc/Main.kt\n",
                listOf("show", "abc123:README.md") to "# PaymentService\n",
                listOf("show", "abc123:src/Main.kt") to "class Main"
            )
        )

        val materialized = GitRevisionMaterializer(Path.of("/tmp/project"), runner).materialize("abc123")
        try {
            assertEquals("abc123", materialized.repositoryStatus().head)
            assertEquals("# PaymentService\n", materialized.root.resolve("README.md").readText())
            assertEquals("class Main", materialized.root.resolve("src/Main.kt").readText())
        } finally {
            materialized.close()
        }
        assertTrue(!java.nio.file.Files.exists(materialized.root))
    }

    @Test
    fun `working tree revision changes when tracked diff or untracked content changes`() {
        val root = createTempDirectory("arianna-working-tree-fingerprint-")
        val untracked = root.resolve("notes.txt")
        untracked.writeText("one")
        val runner = FakeGitCommandRunner(
            mapOf(
                listOf("rev-parse", "--show-toplevel") to "${root.toAbsolutePath()}\n",
                listOf("rev-parse", "HEAD") to "abc123\n",
                listOf("branch", "--show-current") to "main\n",
                listOf("status", "--porcelain=v1") to "?? notes.txt\n",
                listOf("diff", "HEAD", "--binary", "--no-ext-diff", "--") to "",
                listOf("ls-files", "--others", "--exclude-standard") to "notes.txt\n"
            )
        )
        val repository = LocalGitRepository(root, runner)

        val first = repository.workingTreeRevision()
        untracked.writeText("two")
        val second = repository.workingTreeRevision()

        assertTrue(first.startsWith("WORKING_TREE:abc123:"))
        assertNotEquals(first, second)
    }

    private class FakeGitCommandRunner(
        private val responses: Map<List<String>, String> = emptyMap(),
        private val failures: Map<List<String>, String> = emptyMap()
    ) : GitCommandRunner {
        override fun run(directory: Path, vararg arguments: String): String {
            val key = arguments.toList()
            failures[key]?.let { throw GitCommandException(it) }
            return responses[key] ?: error("Unexpected git command: $key")
        }
    }
}
