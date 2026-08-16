package dev.arianna.core.model

data class RepositoryStatus(
    val root: String,
    val head: String,
    val branch: String?,
    val indexedRevision: String?,
    val stagedFiles: List<String>,
    val modifiedFiles: List<String>,
    val untrackedFiles: List<String>,
    val deletedFiles: List<String>
)

enum class FileChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}

data class FileChange(
    val path: String,
    val kind: FileChangeKind,
    val previousPath: String? = null
)

data class RevisionPair(
    val base: String,
    val head: String
)

enum class SnapshotKind {
    BASELINE,
    WORKING_TREE
}

data class WorkingTreeDiff(
    val revisions: RevisionPair,
    val files: List<FileChange>
)
