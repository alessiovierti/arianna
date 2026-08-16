package dev.arianna.core.indexing

import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.source.RepositoryPathFilter
import java.nio.file.Files
import java.nio.file.Path

data class DocumentIndex(
    val entities: List<KnowledgeEntity>
)

/** Static, bounded document extraction. It deliberately does not infer semantic relations. */
object DocumentIndexer {
    private const val MAX_DOCUMENT_BYTES = 1_000_000L
    private val supportedNames = setOf("README.md", "README.markdown")
    private val supportedExtensions = setOf("md", "markdown", "adr", "yaml", "yml", "json", "properties")

    fun analyze(root: Path, repository: String, revision: String, analyzerVersion: String = "document-indexer-0.1"): DocumentIndex {
        if (!Files.exists(root)) return DocumentIndex(emptyList())
        val entities = Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .filter(::isSupported)
                .mapNotNull { file ->
                    if (Files.size(file) > MAX_DOCUMENT_BYTES) return@mapNotNull null
                    val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
                    val content = runCatching { Files.readString(file) }.getOrNull() ?: return@mapNotNull null
                    val evidence = Evidence(repository, revision, relative, analyzerVersion = analyzerVersion)
                    KnowledgeEntity(EntityId("document:$relative"), "document", relative, evidence, content)
                }
                .toList()
        }
        return DocumentIndex(entities)
    }

    private fun isSupported(path: Path): Boolean {
        val name = path.fileName.toString()
        if (name in supportedNames) return true
        return name.substringAfterLast('.', "").lowercase() in supportedExtensions
    }
}
