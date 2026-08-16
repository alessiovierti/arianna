package dev.arianna.web

import dev.arianna.core.config.AppConfig
import com.fasterxml.jackson.databind.ObjectMapper
import dev.arianna.core.model.Confidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.RepositoryStatus
import dev.arianna.storage.SQLiteKnowledgeStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates the two-file portable representation of a published baseline snapshot. */
object SnapshotExporter {
    fun export(repositoryRoot: Path, output: Path) {
        val root = repositoryRoot.toAbsolutePath().normalize()
        val database = AppConfig.forRepository(root).databaseFile
        require(Files.isRegularFile(database)) { "Index not found: run 'learn $root' first" }
        val payload = SQLiteKnowledgeStore(database).use { store ->
            val snapshot = store.getCurrentSnapshot(root.toString())
                ?: error("Baseline snapshot not found: run 'learn $root' first")
            val entities = store.entitiesForSnapshot(snapshot.id)
            val relations = store.relationsForSnapshot(snapshot.id)
            val repository = RepositoryDto(
                RepositoryStatus(snapshot.repository, snapshot.revision, null, snapshot.revision, emptyList(), emptyList(), emptyList(), emptyList()),
                emptyList(), snapshot.revision, snapshot.kind.name.lowercase(), true, snapshot = true
            )
            val entityDtos = entities.map(::entityDto)
            val relationDtos = relations.map { relationDto(it, entities) }
            val overview = OverviewDto(
                repository = repository,
                entities = entities.groupingBy { it.kind }.eachCount().toSortedMap(),
                relations = relations.groupingBy { it.type }.eachCount().toSortedMap(),
                modules = entityDtos.filter { it.kind == "module" },
                springComponents = entityDtos.filter { it.kind in setOf("component", "service", "repository", "controller", "configuration") },
                endpoints = entityDtos.filter { it.kind == "endpoint" },
                events = entityDtos.filter { it.kind == "event" },
                configurations = entityDtos.filter { it.kind == "configuration_property" },
                moduleDependencies = relationDtos.filter { it.type == "depends_on" },
                unresolvedRelations = relations.count { it.type == "unresolved" || it.confidence == Confidence.LOW },
                lowConfidenceRelations = relations.count { it.confidence == Confidence.LOW },
                analyzers = (entities.mapNotNull { it.evidence?.analyzerVersion } + relations.mapNotNull { it.evidence?.analyzerVersion }).distinct().sorted(),
                architecture = ArchitectureAggregator.build(entities, relations)
            )
            SnapshotPayload(overview, entityDtos, relationDtos)
        }
        val snapshotHtml = standaloneHtml(payload)
        output.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("index.html"))
            zip.write(snapshotHtml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("knowledge.db"))
            Files.newInputStream(database).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun standaloneHtml(payload: SnapshotPayload): String {
        val loader = SnapshotExporter::class.java.classLoader
        val index = loader.getResource("web/index.html")?.readText() ?: error("Web Explorer asset is not available")
        val style = loader.getResource("web/style.css")?.readText() ?: error("Web Explorer asset is not available")
        val markdown = loader.getResource("web/vendor/markdown-it.min.js")?.readText() ?: error("Web Explorer asset is not available")
        val mermaid = loader.getResource("web/vendor/mermaid.min.js")?.readText() ?: error("Web Explorer asset is not available")
        val app = loader.getResource("web/app.js")?.readText() ?: error("Web Explorer asset is not available")
        val json = ObjectMapper().writeValueAsString(payload).replace("<", "\\u003c")
        val shim = offlineFetchShim(json)
        return index
            .replace(Regex("<link rel=\"stylesheet\" href=\"/style\\.css\">")) { "<style>\n$style\n</style>" }
            .replace(Regex("<script src=\"/vendor/markdown-it\\.min\\.js[^>]*></script>")) { "<script>\n$markdown\n</script>" }
            .replace(Regex("<script src=\"/vendor/mermaid\\.min\\.js[^>]*></script>")) { "<script>\n$mermaid\n</script>" }
            .replace(Regex("<script src=\"/app\\.js[^>]*></script>")) { "<script>\n$shim\n</script>\n<script>\n$app\n</script>" }
    }

    private fun offlineFetchShim(json: String): String = """
        window.__ARIANNA_SNAPSHOT_DATA__ = $json;
        (() => {
          const data = window.__ARIANNA_SNAPSHOT_DATA__;
          const response = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});
          const page = (items, params) => { const offset = Math.max(0, Number(params.get('offset') || 0)); const limit = Math.min(200, Math.max(1, Number(params.get('limit') || 50))); return {items: items.slice(offset, offset + limit), total: items.length, offset, limit, nextOffset: offset + limit < items.length ? offset + limit : null}; };
          const findEntity = id => data.entities.find(item => item.id === id);
          const apiFetch = async (input, options) => {
            const url = new URL(typeof input === 'string' ? input : input.url, location.href);
            if (!url.pathname.startsWith('/api/')) return window.__ariannaOriginalFetch(input, options);
            const p = url.searchParams;
            if (url.pathname === '/api/repository') return response(data.overview.repository);
            if (url.pathname === '/api/overview') return response(data.overview);
            if (url.pathname === '/api/index' || url.pathname.includes('/api/index/jobs')) return response({code:'snapshot_read_only', message:'This snapshot is read-only'}, 409);
            if (url.pathname === '/api/search') {
              const q = (p.get('q') || '').trim().toLowerCase(); const scope = (p.get('scope') || '').replace(/^\/+|\/+$/g, ''); const kind = p.get('kind');
              const items = data.entities.filter(item => (!kind || item.kind === kind) && (!scope || (item.evidence?.file || '') === scope || (item.evidence?.file || '').startsWith(scope + '/')) && (!q || (item.qualifiedName + ' ' + (item.content || '')).toLowerCase().includes(q)));
              return response(page(items, p));
            }
            if (url.pathname === '/api/entities') { const entity = findEntity(p.get('id')); if (!entity) return response({message:'Entity not found'},404); return response({entity, relationships:data.relations.filter(r => r.source === entity.id || r.target === entity.id)}); }
            if (url.pathname === '/api/entities/relationships') return response(page(data.relations.filter(r => !p.get('entityId') || r.source === p.get('entityId') || r.target === p.get('entityId')), p));
            if (url.pathname === '/api/entities/neighborhood') {
              const center = findEntity(p.get('entityId')); if (!center) return response({message:'Entity not found'},404); const depth = Math.max(0, Math.min(4, Number(p.get('depth') || 2))); const selected = new Set([center.id]);
              for (let i = 0; i < depth; i++) data.relations.filter(r => selected.has(r.source) || selected.has(r.target)).forEach(r => { if (selected.size < 100) selected.add(selected.has(r.source) ? r.target : r.source); });
              return response({center, entities:data.entities.filter(e => selected.has(e.id)), relationships:data.relations.filter(r => selected.has(r.source) && selected.has(r.target)), depth});
            }
            if (url.pathname === '/api/documents/index') return response({items:data.entities.filter(e => e.kind === 'document').map(e => ({id:e.id,path:e.qualifiedName,revision:e.evidence?.revision || null,bytes:(e.content || '').length})).sort((a,b) => a.path.localeCompare(b.path))});
            if (url.pathname === '/api/documents') { const document = data.entities.find(e => e.kind === 'document' && e.qualifiedName === p.get('path')); return document ? response(document) : response({message:'Document not found'},404); }
            return response({message:'This snapshot feature requires the repository'},409);
          };
          window.__ariannaOriginalFetch = window.fetch.bind(window);
          window.fetch = apiFetch;
        })();
    """.trimIndent()

    private fun addResource(zip: ZipOutputStream, name: String, resource: String) {
        zip.putNextEntry(ZipEntry(name))
        SnapshotExporter::class.java.classLoader.getResourceAsStream(resource)?.use { it.copyTo(zip) }
            ?: error("Web Explorer asset is not available: $resource")
        zip.closeEntry()
    }
}

private data class SnapshotPayload(
    val overview: OverviewDto,
    val entities: List<EntityDto>,
    val relations: List<RelationDto>
)
