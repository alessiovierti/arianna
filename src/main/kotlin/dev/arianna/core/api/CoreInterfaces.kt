package dev.arianna.core.api

import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Page
import dev.arianna.core.model.RepositoryStatus
import java.nio.file.Path

interface Source {
    val root: Path
    fun repositoryStatus(): RepositoryStatus
    fun workingTreeRevision(): String? = null
}

interface Store {
    fun saveEntities(entities: Sequence<KnowledgeEntity>)
    fun saveRelations(relations: Sequence<KnowledgeRelation>)
    fun findEntitiesPage(
        query: String,
        offset: Int = 0,
        limit: Int = 50,
        repository: String? = null,
        file: String? = null,
        kind: String? = null,
        revision: String? = null
    ): Page<KnowledgeEntity>
    fun findRelationsPage(
        entityId: String,
        offset: Int = 0,
        limit: Int = 100,
        revision: String? = null,
        confidence: String? = null
    ): Page<KnowledgeRelation>

    fun findEntities(query: String, limit: Int = 50): List<KnowledgeEntity> =
        findEntitiesPage(query, 0, limit).items

    fun findRelations(entityId: String, limit: Int = 100): List<KnowledgeRelation> =
        findRelationsPage(entityId, 0, limit).items
}

interface SnapshotStore : Store {
    fun replaceSnapshot(
        repository: String,
        revision: String,
        entities: Sequence<KnowledgeEntity>,
        relations: Sequence<KnowledgeRelation>
    )

    fun replaceOverlaySnapshot(
        repository: String,
        revision: String,
        entities: Sequence<KnowledgeEntity>,
        relations: Sequence<KnowledgeRelation>
    )

    fun entitiesForSnapshot(snapshotId: Long): List<KnowledgeEntity>
    fun relationsForSnapshot(snapshotId: Long): List<KnowledgeRelation>
}

interface Indexer {
    fun index(source: Source, store: Store): IndexResult
}

data class IndexResult(
    val indexedFiles: Int,
    val indexedEntities: Int,
    val indexedRelations: Int,
    val revision: String
)

interface QueryEngine {
    fun searchKnowledge(
        query: String,
        offset: Int = 0,
        limit: Int = 50,
        repository: String? = null,
        file: String? = null,
        kind: String? = null
    ): Page<KnowledgeEntity>
    fun getDocument(path: String): KnowledgeEntity?
    fun findSymbol(query: String, limit: Int = 50): List<KnowledgeEntity>
    fun findRelationships(entityId: String, limit: Int = 100): List<KnowledgeRelation>
    fun findReferences(query: String, limit: Int = 100): List<KnowledgeRelation>
    fun findImplementations(query: String, limit: Int = 100): List<KnowledgeRelation>
    fun findSymbolsPage(
        query: String,
        offset: Int = 0,
        limit: Int = 50,
        revision: String? = null
    ): Page<KnowledgeEntity>
    fun findRelationshipsPage(
        entityId: String,
        offset: Int = 0,
        limit: Int = 100,
        revision: String? = null,
        confidence: String? = null
    ): Page<KnowledgeRelation>
    fun findReferencesPage(
        query: String,
        offset: Int = 0,
        limit: Int = 100,
        revision: String? = null,
        confidence: String? = null
    ): Page<KnowledgeRelation>
    fun findImplementationsPage(
        query: String,
        offset: Int = 0,
        limit: Int = 100,
        revision: String? = null,
        confidence: String? = null
    ): Page<KnowledgeRelation>
}
