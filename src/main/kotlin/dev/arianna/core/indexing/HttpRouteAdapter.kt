package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.source.RepositoryPathFilter
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

data class HttpRouteAnalysis(val entities: List<KnowledgeEntity>, val relations: List<KnowledgeRelation>)

/** Recognizes explicit Ktor-style HTTP route DSL calls without executing application code. */
class HttpRouteAdapter(private val analyzerVersion: String = "http-route-adapter-0.1") {
    fun analyze(root: Path, repository: String, revision: String): HttpRouteAnalysis {
        val entities = linkedMapOf<String, KnowledgeEntity>()
        val relations = linkedMapOf<String, KnowledgeRelation>()
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .forEach { file -> analyzeFile(root, file, repository, revision, entities, relations) }
        }
        return HttpRouteAnalysis(entities.values.toList(), relations.values.toList())
    }

    private fun analyzeFile(root: Path, file: Path, repository: String, revision: String, entities: MutableMap<String, KnowledgeEntity>, relations: MutableMap<String, KnowledgeRelation>) {
        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
        var currentFunction: FunctionContext? = null
        Files.readAllLines(file).forEachIndexed { index, line ->
            val lineNumber = index + 1
            functionPattern.matcher(line).takeIf { it.find() }?.let { match ->
                currentFunction = FunctionContext(match.group(1))
                val id = EntityId("function:${match.group(1)}")
                val evidence = Evidence(repository, revision, relative, lineNumber, lineNumber, analyzerVersion)
                entities.putIfAbsent(id.value, KnowledgeEntity(id, "function", match.group(1), evidence))
                val file = EntityId("file:$relative")
                relations.putIfAbsent(
                    "${file.value}|contains|${id.value}",
                    KnowledgeRelation(file, "contains", id, Origin.STATIC, Confidence.HIGH, evidence)
                )
            }
            routePattern.matcher(line).takeIf { it.find() }?.let { match ->
                val method = match.group(1).lowercase()
                val path = match.group(2)
                val evidence = Evidence(repository, revision, relative, lineNumber, lineNumber, analyzerVersion)
                val endpoint = EntityId("endpoint:$method:$path")
                entities.putIfAbsent(endpoint.value, KnowledgeEntity(endpoint, "endpoint", "${method.uppercase()} $path", evidence))
                val source = currentFunction?.let { EntityId("function:${it.name}") } ?: EntityId("file:$relative")
                relations.putIfAbsent("${source.value}|exposes_endpoint|${endpoint.value}", KnowledgeRelation(source, "exposes_endpoint", endpoint, Origin.FRAMEWORK, Confidence.HIGH, evidence))
            }
        }
    }

    private data class FunctionContext(val name: String)

    companion object {
        private val functionPattern = Pattern.compile("\\bfun\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*\\([^)]*\\)")
        private val routePattern = Pattern.compile("\\b(get|post|put|patch|delete|head|options)\\s*\\(\\s*[\"']([^\"']+)[\"']")
    }
}
