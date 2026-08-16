package dev.arianna.web

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin

/** Builds a bounded, layered macro-level view without changing the canonical graph. */
internal object ArchitectureAggregator {
    private val architectureKinds = setOf("module", "package", "class", "interface", "component", "service", "repository", "controller", "configuration", "endpoint", "event", "method", "function", "file", "runtime_service")
    private val codeComponentKinds = setOf("component", "service", "repository", "controller", "configuration")
    private val infrastructureServices = setOf("db", "postgres", "postgresql", "redis", "opensearch", "vpn", "proxy", "tunnel", "caddy", "caddy_config", "caddy_data", "postgres_data", "redis_data", "opensearch_data", "opensearch_data_local", "redis_data_local")

    fun build(entities: List<KnowledgeEntity>, relations: List<KnowledgeRelation>): ArchitectureDto {
        val architectureEntities = entities.filter { it.kind in architectureKinds && (it.kind != "file" || isCodeFile(it.qualifiedName)) }
        val filesToModules = relations.filter { it.type == "contains" && it.source.value.startsWith("module:") }
            .groupBy({ it.target.value.removePrefix("file:") }, { it.source.value }).mapValues { (_, ids) -> ids.maxByOrNull(String::length) }
        val projectRoots = architectureEntities.mapNotNull { it.evidence?.file?.substringBefore('/') }.toSet()
        val hasApplicationRoots = "backend" in projectRoots || "frontend" in projectRoots
        val packages = architectureEntities.filter { it.kind == "package" }.map { it.qualifiedName }.distinct()
        val buckets = linkedMapOf<String, MutableBucket>()
        val entityBuckets = mutableMapOf<String, String>()

        fun bucket(id: String, label: String, kind: String, origin: Origin, confidence: Confidence, parentId: String? = null, scopePath: String? = null, layer: String = "code") =
            buckets.getOrPut(id) { MutableBucket(id, label, kind, origin, confidence, parentId, scopePath, layer) }

        fun applicationRoot(name: String) = bucket("macro:root:$name", name, "component", if (name == "backend") Origin.DECLARED else Origin.INFERRED, if (name == "backend") Confidence.HIGH else Confidence.MEDIUM, scopePath = name, layer = "application")

        fun moduleBucket(moduleName: String): MutableBucket {
            val rootName = moduleName.substringBefore(':')
            val root = applicationRoot(rootName)
            if (moduleName == rootName) return root
            val child = bucket("macro:module:${moduleName.replace(':', '/')}", moduleName.substringAfterLast(':'), "module", Origin.DECLARED, Confidence.HIGH, root.id, moduleName.replace(':', '/'), "code")
            root.children += child.id
            return child
        }

        fun runtimeBucket(entity: KnowledgeEntity): MutableBucket {
            val infrastructure = entity.qualifiedName.lowercase() in infrastructureServices
            val group = bucket(if (infrastructure) "macro:group:infrastructure" else "macro:group:runtime", if (infrastructure) "Infrastructure" else "Runtime topology", "architecture-group", Origin.DECLARED, Confidence.HIGH, layer = if (infrastructure) "infrastructure" else "runtime")
            val service = bucket("macro:runtime:${entity.qualifiedName}", entity.qualifiedName, "runtime-service", Origin.DECLARED, Confidence.HIGH, group.id, entity.qualifiedName, if (infrastructure) "infrastructure" else "runtime")
            group.add(entity)
            if (service.id !in group.children) group.children += service.id
            return service
        }

        fun bucketFor(entity: KnowledgeEntity): MutableBucket {
            if (entity.kind == "runtime_service") return runtimeBucket(entity).also { it.add(entity); entityBuckets[entity.id.value] = it.id }
            val moduleId = entity.evidence?.file?.let { filesToModules[it] }
            val rootName = entity.evidence?.file?.substringBefore('/')?.takeIf { hasApplicationRoots && it in setOf("backend", "frontend") }
            if (rootName != null) {
                val root = applicationRoot(rootName)
                val moduleName = (moduleId?.removePrefix("module:") ?: entity.takeIf { it.kind == "module" }?.qualifiedName)
                    ?.takeIf { it == rootName || it.startsWith("$rootName:") }
                val target = moduleName?.let(::moduleBucket) ?: root
                root.add(entity)
                if (target.id != root.id) target.add(entity)
                entityBuckets[entity.id.value] = target.id
                return target
            }
            val explicitModule = moduleId?.removePrefix("module:") ?: entity.takeIf { it.kind == "module" }?.qualifiedName
            if (explicitModule != null && explicitModule.substringBefore(':') in setOf("backend", "frontend")) {
                val target = moduleBucket(explicitModule)
                target.add(entity); entityBuckets[entity.id.value] = target.id; return target
            }
            val key = explicitModule ?: packageGroup(entity.qualifiedName, packages) ?: fileGroup(entity.evidence?.file) ?: "other"
            val target = bucket(if (explicitModule != null) "macro:module:$key" else "macro:package:$key", key.removePrefix("module:").substringAfterLast('.'), if (explicitModule != null) "module" else "inferred-package", if (explicitModule != null) Origin.DECLARED else Origin.INFERRED, if (explicitModule != null) Confidence.HIGH else Confidence.MEDIUM, scopePath = key, layer = if (explicitModule != null) "code" else "inferred")
            target.add(entity); entityBuckets[entity.id.value] = target.id; return target
        }

        architectureEntities.forEach(::bucketFor)
        val relationTriples = relations.asSequence().filter { it.type !in setOf("contains", "defines") }.mapNotNull { relation ->
            val source = entityBuckets[relation.source.value]; val target = entityBuckets[relation.target.value]
            if (source == null || target == null || source == target) null else Triple(source, target, relation)
        }.toMutableList<Triple<String, String, KnowledgeRelation?>>()
        if (hasApplicationRoots && projectRoots.containsAll(setOf("frontend", "backend"))) relationTriples += Triple("macro:root:frontend", "macro:root:backend", null)
        val architectureRelations = relationTriples.flatMap { (source, target, relation) ->
            val sourceParent = buckets[source]?.parentId
            val targetParent = buckets[target]?.parentId
            if (sourceParent != null && sourceParent == targetParent) {
                listOf(Quadruple(source, target, sourceParent, relation))
            } else {
                listOf(Quadruple(sourceParent ?: source, targetParent ?: target, null, relation))
            }
        }.filter { it.source != it.target }.groupBy { Triple(it.source, it.target, it.scopeId) }.map { (pair, grouped) ->
                val values = grouped.mapNotNull { it.relation }
                ArchitectureRelationDto(pair.first, pair.second, if (values.isEmpty()) "repository structure (inferred)" else values.map { it.type }.distinct().sorted().take(3).joinToString(", "), values.size.coerceAtLeast(1), if (values.isEmpty()) "inferred" else aggregateOrigin(values), if (values.isEmpty()) "low" else aggregateConfidence(values), pair.third)
            }.sortedWith(compareBy({ it.scopeId != null }, { it.source }, { it.target }))
        return ArchitectureDto(buckets.values.map { it.toDto() }.sortedWith(compareBy({ it.parentId != null }, { it.layer }, { it.label })), architectureRelations)
    }

    private fun isCodeFile(path: String) = listOf(".java", ".kt", ".scala", ".groovy", ".js", ".jsx", ".ts", ".tsx", ".vue").any(path::endsWith)
    private fun packageGroup(name: String, packages: List<String>): String? {
        val packageName = packages.filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length } ?: return null
        val segments = packageName.split('.'); return if (segments.size >= 3 && segments[0] in setOf("com", "dev", "io", "net", "org")) segments.take(3).joinToString(".") else segments.first()
    }
    private fun fileGroup(file: String?): String? {
        if (file == null) return null; val segments = file.split('/'); val sourceIndex = segments.indexOfFirst { it in setOf("java", "kotlin", "scala", "groovy") }
        if (sourceIndex >= 0 && segments.size > sourceIndex + 2) return segments.subList(sourceIndex + 1, minOf(sourceIndex + 4, segments.size - 1)).joinToString(".")
        return segments.firstOrNull { it !in setOf("src", "main", "test") }
    }
    private fun aggregateOrigin(relations: List<KnowledgeRelation>) = if (relations.map { it.origin }.distinct().size == 1) relations.first().origin.name.lowercase() else "inferred"
    private fun aggregateConfidence(relations: List<KnowledgeRelation>) = when { relations.any { it.confidence == Confidence.LOW } -> "low"; relations.any { it.confidence == Confidence.MEDIUM } -> "medium"; else -> "high" }

    private data class Quadruple(val source: String, val target: String, val scopeId: String?, val relation: KnowledgeRelation?)

    private class MutableBucket(val id: String, val label: String, val kind: String, val origin: Origin, val confidence: Confidence, val parentId: String? = null, val scopePath: String? = null, val layer: String = "code", val entityIds: MutableList<String> = mutableListOf(), val children: MutableList<String> = mutableListOf(), var entityCount: Int = 0, var endpointCount: Int = 0, var componentCount: Int = 0) {
        fun add(entity: KnowledgeEntity) { entityIds += entity.id.value; entityCount++; if (entity.kind == "endpoint") endpointCount++; if (entity.kind in codeComponentKinds) componentCount++ }
        fun toDto() = ArchitectureNodeDto(id, label, kind, origin.name.lowercase(), confidence.name.lowercase(), entityCount, endpointCount, componentCount, entityIds.distinct(), parentId, scopePath, layer)
    }
}

data class ArchitectureDto(val nodes: List<ArchitectureNodeDto>, val relations: List<ArchitectureRelationDto>)
data class ArchitectureNodeDto(val id: String, val label: String, val kind: String, val origin: String, val confidence: String, val entityCount: Int, val endpointCount: Int, val componentCount: Int, val entityIds: List<String>, val parentId: String? = null, val scopePath: String? = null, val layer: String = "code")
data class ArchitectureRelationDto(val source: String, val target: String, val label: String, val relationCount: Int, val origin: String, val confidence: String, val scopeId: String? = null)
