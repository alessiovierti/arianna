package dev.arianna.core.query

import dev.arianna.core.api.QueryEngine
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Page

class KnowledgeQueryEngine(
    private val store: dev.arianna.core.api.Store
) : QueryEngine {
    override fun searchKnowledge(
        query: String,
        offset: Int,
        limit: Int,
        repository: String?,
        file: String?,
        kind: String?
    ): Page<KnowledgeEntity> = store.findEntitiesPage(query, offset, limit, repository, file, kind)

    override fun getDocument(path: String): KnowledgeEntity? =
        store.findEntitiesPage(path, 0, 100).items.firstOrNull {
            it.kind == "document" && (it.qualifiedName == path || it.id.value == "document:$path")
        }

    override fun findSymbol(query: String, limit: Int): List<KnowledgeEntity> =
        findSymbolsPage(query, 0, limit).items

    override fun findRelationships(entityId: String, limit: Int): List<KnowledgeRelation> =
        store.findRelations(entityId, limit)

    override fun findReferences(query: String, limit: Int): List<KnowledgeRelation> =
        relationsForSymbols(query, setOf("references", "calls")).take(limit)

    override fun findImplementations(query: String, limit: Int): List<KnowledgeRelation> =
        relationsForSymbols(query, setOf("implements", "overrides")).take(limit)

    override fun findSymbolsPage(query: String, offset: Int, limit: Int, revision: String?): Page<KnowledgeEntity> {
        val normalizedQuery = query.trim()
        val all = store.findEntitiesPage(normalizedQuery, 0, MAX_SYMBOL_SEARCH, revision = revision).items
            .filterNot { it.kind == "file" || it.kind == "document" }
            .sortedWith(
                compareBy<KnowledgeEntity> {
                    when {
                        it.qualifiedName == normalizedQuery || it.id.value == normalizedQuery || it.id.value.endsWith(":$normalizedQuery") -> 0
                        it.qualifiedName.substringAfterLast('.') == normalizedQuery -> 1
                        else -> 2
                    }
                }
                    .thenBy { it.qualifiedName }
                    .thenBy { it.kind }
                    .thenBy { it.id.value }
            )
        return Page(all.drop(offset).take(limit), all.size, offset, limit)
    }

    override fun findRelationshipsPage(entityId: String, offset: Int, limit: Int, revision: String?, confidence: String?): Page<KnowledgeRelation> =
        store.findRelationsPage(entityId, offset, limit, revision, confidence)

    override fun findReferencesPage(query: String, offset: Int, limit: Int, revision: String?, confidence: String?): Page<KnowledgeRelation> =
        relationPage(query, setOf("references", "calls"), offset, limit, revision, confidence)

    override fun findImplementationsPage(query: String, offset: Int, limit: Int, revision: String?, confidence: String?): Page<KnowledgeRelation> =
        relationPage(query, setOf("implements", "overrides"), offset, limit, revision, confidence)

    private fun relationsForSymbols(query: String, relationTypes: Set<String>, revision: String? = null, confidence: String? = null): List<KnowledgeRelation> {
        val symbols = store.findEntitiesPage(query, 0, MAX_RELATION_SEARCH, revision = revision).items
        val targetIds = symbols.map { it.id.value }.toSet()
        return symbols.flatMap { store.findRelationsPage(it.id.value, 0, MAX_RELATION_SEARCH, revision = revision, confidence = confidence).items }
            .filter { it.type in relationTypes && it.target.value in targetIds }
            .distinctBy { Triple(it.source, it.type, it.target) }
            .sortedWith(compareBy({ it.source.value }, { it.type }, { it.target.value }, { it.evidence?.file.orEmpty() }, { it.evidence?.startLine ?: 0 }))
    }

    private fun relationPage(query: String, relationTypes: Set<String>, offset: Int, limit: Int, revision: String? = null, confidence: String? = null): Page<KnowledgeRelation> {
        val all = relationsForSymbols(query, relationTypes, revision, confidence)
        return Page(all.drop(offset).take(limit), all.size, offset, limit)
    }

    private companion object {
        const val MAX_RELATION_SEARCH = 10_000
        const val MAX_SYMBOL_SEARCH = 10_000
    }
}
