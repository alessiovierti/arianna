package dev.arianna.core.indexing

import dev.arianna.core.api.IndexResult
import dev.arianna.core.api.Indexer
import dev.arianna.core.api.SnapshotStore
import dev.arianna.core.api.Source
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.source.RepositoryPathFilter
import java.nio.file.Files
import java.nio.file.Path

class RepositoryFileIndexer(
    private val analyzerVersion: String = "file-indexer-0.1"
) : Indexer {
    override fun index(source: Source, store: dev.arianna.core.api.Store): IndexResult {
        val prepared = prepare(source)
        if (store is SnapshotStore) {
            store.replaceSnapshot(source.repositoryStatus().root, prepared.revision, prepared.entities.asSequence(), prepared.relations.asSequence())
        } else {
            store.saveEntities(prepared.entities.asSequence())
            store.saveRelations(prepared.relations.asSequence())
        }
        return prepared.result
    }

    fun indexOverlay(source: Source, store: dev.arianna.core.api.Store): IndexResult {
        val snapshotStore = store as? SnapshotStore
            ?: throw IllegalArgumentException("L’overlay richiede uno SnapshotStore")
        val overlayRevision = source.workingTreeRevision() ?: "WORKING_TREE:${source.repositoryStatus().head}"
        val prepared = prepare(source, overlayRevision)
        snapshotStore.replaceOverlaySnapshot(
            source.repositoryStatus().root,
            overlayRevision,
            prepared.entities.asSequence(),
            prepared.relations.asSequence()
        )
        return prepared.result.copy(revision = overlayRevision)
    }

    private fun prepare(source: Source, revisionOverride: String? = null): PreparedIndex {
        val status = source.repositoryStatus()
        val root = Path.of(status.root)
        val revision = revisionOverride ?: status.head
        val files = Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }

        val repositoryEvidence = Evidence(status.root, revision, "", analyzerVersion = analyzerVersion)
        val documents = DocumentIndexer.analyze(root = root, repository = status.root, revision = revision).entities
        val entities = buildList {
            add(KnowledgeEntity(EntityId("repository:${status.root}"), "repository", status.root, repositoryEvidence))
            files.forEach { file ->
                add(KnowledgeEntity(EntityId("file:$file"), "file", file, repositoryEvidence.copy(file = file)))
            }
            addAll(documents)
        }
        val relations = DocumentLinker.link(documents, entities)

        return PreparedIndex(
            revision,
            entities,
            relations,
            IndexResult(files.size + 1, files.size + 1 + documents.size, relations.size, revision)
        )
    }

    private data class PreparedIndex(
        val revision: String,
        val entities: List<KnowledgeEntity>,
        val relations: List<dev.arianna.core.model.KnowledgeRelation>,
        val result: IndexResult
    )
}
