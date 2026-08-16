package dev.arianna.core.source

import dev.arianna.core.api.Source
import dev.arianna.core.model.RepositoryStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Source for a local directory that is not backed by Git. */
class LocalDirectorySource(
    override val root: Path
) : Source {
    init {
        require(Files.isDirectory(root)) { "The directory does not exist or is invalid: $root" }
    }

    override fun repositoryStatus(): RepositoryStatus = RepositoryStatus(
        root = root.toAbsolutePath().normalize().toString(),
        head = localRevision(),
        branch = null,
        indexedRevision = null,
        stagedFiles = emptyList(),
        modifiedFiles = emptyList(),
        untrackedFiles = emptyList(),
        deletedFiles = emptyList()
    )

    override fun workingTreeRevision(): String = "WORKING_TREE:${fingerprint()}"

    private fun localRevision(): String = "LOCAL:${fingerprint()}"

    private fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .map { it to root.relativize(it).toString().replace(it.fileSystem.separator, "/") }
                .sortedBy { (_, relative) -> relative }
                .forEach { (file, relative) ->
                    digest.update(relative.toByteArray())
                    digest.update(0)
                    digest.update(Files.readAllBytes(file))
                    digest.update(0)
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}

/** Selects Git when metadata is present in the path or one of its parents. */
fun openRepositorySource(path: Path): Source {
    val normalized = path.toAbsolutePath().normalize()
    var current: Path? = normalized
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) return LocalGitRepository(normalized)
        current = current.parent
    }
    return LocalDirectorySource(normalized)
}
