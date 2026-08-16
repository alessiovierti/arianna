package dev.arianna.core.source

import dev.arianna.core.api.Source
import dev.arianna.core.error.RepositoryException
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.core.model.FileChange
import dev.arianna.core.model.FileChangeKind
import dev.arianna.core.model.RevisionPair
import dev.arianna.core.model.WorkingTreeDiff
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import java.nio.file.Path
import java.security.MessageDigest

interface GitRepository {
    fun status(): RepositoryStatus
    fun workingTreeDiff(): WorkingTreeDiff
}

class GitCommandException(message: String, cause: Throwable? = null) : RepositoryException(message, cause)

class LocalGitRepository(
    private val workingDirectory: Path,
    private val commandRunner: GitCommandRunner = ProcessGitCommandRunner()
) : GitRepository, Source {
    override val root: Path
        get() = workingDirectory

    override fun status(): RepositoryStatus {
        val root = commandRunner.run(workingDirectory, "rev-parse", "--show-toplevel")
            .trim()
            .takeIf(String::isNotEmpty)
            ?: throw GitCommandException("The directory is not part of a Git repository: $workingDirectory")

        val repositoryRoot = Path.of(root)
        val head = commandRunner.run(repositoryRoot, "rev-parse", "HEAD").trim()
            .takeIf(String::isNotEmpty)
            ?: throw GitCommandException("Unable to read HEAD from repository: $repositoryRoot")

        val branch = commandRunner.run(repositoryRoot, "branch", "--show-current")
            .trim()
            .takeIf(String::isNotEmpty)

        val porcelain = commandRunner.run(repositoryRoot, "status", "--porcelain=v1")
        val paths = parsePorcelainStatus(porcelain)

        return RepositoryStatus(
            root = repositoryRoot.toAbsolutePath().normalize().toString(),
            head = head,
            branch = branch,
            indexedRevision = null,
            stagedFiles = paths.staged,
            modifiedFiles = paths.modified,
            untrackedFiles = paths.untracked,
            deletedFiles = paths.deleted
        )
    }

    override fun repositoryStatus(): RepositoryStatus = status()

    override fun workingTreeRevision(): String {
        val repositoryStatus = status()
        val repositoryRoot = Path.of(repositoryStatus.root)
        val trackedDiff = commandRunner.run(repositoryRoot, "diff", "HEAD", "--binary", "--no-ext-diff", "--")
        val untracked = commandRunner.run(repositoryRoot, "ls-files", "--others", "--exclude-standard")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != ".arianna" && !it.startsWith(".arianna/") }
            .sorted()
            .toList()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(repositoryStatus.head.toByteArray())
        digest.update(trackedDiff.toByteArray())
        untracked.forEach { relative ->
            val file = repositoryRoot.resolve(relative).normalize()
            if (file.startsWith(repositoryRoot) && Files.isRegularFile(file)) {
                digest.update(relative.toByteArray())
                digest.update(Files.readAllBytes(file))
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        return "WORKING_TREE:${repositoryStatus.head}:$fingerprint"
    }

    override fun workingTreeDiff(): WorkingTreeDiff {
        val repositoryRoot = Path.of(status().root)
        val base = commandRunner.run(repositoryRoot, "rev-parse", "HEAD").trim()
        val changes = parseNameStatus(
            commandRunner.run(repositoryRoot, "diff", "HEAD", "--name-status", "--find-renames", "--")
        ).toMutableList()
        val trackedUntracked = commandRunner.run(repositoryRoot, "ls-files", "--others", "--exclude-standard")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != ".arianna" && !it.startsWith(".arianna/") }
            .map { FileChange(it, FileChangeKind.ADDED) }
        changes += trackedUntracked
        return WorkingTreeDiff(RevisionPair(base, "WORKING_TREE"), changes.distinctBy { it.path }.sortedBy { it.path })
    }

    private fun parseNameStatus(output: String): List<FileChange> = output.lineSequence()
        .map(String::trimEnd)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = line.split('\t')
            val status = fields.firstOrNull()?.trim() ?: return@mapNotNull null
            when {
                status.startsWith("R") && fields.size >= 3 -> FileChange(fields[2], FileChangeKind.RENAMED, fields[1])
                status.startsWith("A") && fields.size >= 2 -> FileChange(fields[1], FileChangeKind.ADDED)
                status.startsWith("D") && fields.size >= 2 -> FileChange(fields[1], FileChangeKind.DELETED)
                status.startsWith("M") && fields.size >= 2 -> FileChange(fields[1], FileChangeKind.MODIFIED)
                else -> null
            }
        }
        .toList()

    private fun parsePorcelainStatus(output: String): ParsedPaths {
        val staged = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        output.lineSequence()
            .filter(String::isNotEmpty)
            .forEach { line ->
                if (line.length < 3) return@forEach
                val indexStatus = line[0]
                val workTreeStatus = line[1]
                val path = line.substring(3)

                if (path == ".arianna" || path.startsWith(".arianna/")) return@forEach

                if (indexStatus == '?') {
                    untracked += path
                    return@forEach
                }
                if (indexStatus != ' ') staged += path
                if (workTreeStatus != ' ') modified += path
                if (indexStatus == 'D' || workTreeStatus == 'D') deleted += path
            }

        return ParsedPaths(staged, modified, untracked, deleted)
    }

    private data class ParsedPaths(
        val staged: List<String>,
        val modified: List<String>,
        val untracked: List<String>,
        val deleted: List<String>
    )
}

interface GitCommandRunner {
    fun run(directory: Path, vararg arguments: String): String
}

class ProcessGitCommandRunner : GitCommandRunner {
    override fun run(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments.toList())
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            val detail = stderr.trim().ifEmpty { "exit code $exitCode" }
            throw GitCommandException("git ${arguments.joinToString(" ")} failed: $detail")
        }
        return stdout
    }
}

data class MaterializedRevision(
    val revision: String,
    override val root: Path,
    private val repositoryId: String
) : Source, AutoCloseable {
    override fun repositoryStatus(): RepositoryStatus = RepositoryStatus(
        root = root.toAbsolutePath().normalize().toString(),
        head = revision,
        branch = null,
        indexedRevision = null,
        stagedFiles = emptyList(),
        modifiedFiles = emptyList(),
        untrackedFiles = emptyList(),
        deletedFiles = emptyList()
    )

    fun repository(): String = repositoryId

    override fun close() {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }
}

class GitRevisionMaterializer(
    private val repositoryRoot: Path,
    private val commandRunner: GitCommandRunner = ProcessGitCommandRunner()
) {
    fun materialize(revision: String): MaterializedRevision {
        val root = Files.createTempDirectory("arianna-revision-")
        try {
            val paths = commandRunner.run(repositoryRoot, "ls-tree", "-r", "--name-only", revision)
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .toList()
            paths.forEach { relative ->
                val destination = root.resolve(relative).normalize()
                if (!destination.startsWith(root)) return@forEach
                Files.createDirectories(destination.parent)
                Files.writeString(destination, commandRunner.run(repositoryRoot, "show", "$revision:$relative"))
            }
            return MaterializedRevision(revision, root, repositoryRoot.toAbsolutePath().normalize().toString())
        } catch (error: Exception) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
            throw GitCommandException("Unable to materialize revision $revision: ${error.message}", error)
        }
    }

    fun materializePair(pair: RevisionPair): Pair<MaterializedRevision, MaterializedRevision> =
        materialize(pair.base) to materialize(pair.head)
}
