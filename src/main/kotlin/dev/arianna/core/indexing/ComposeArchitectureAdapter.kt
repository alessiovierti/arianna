package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.source.TextFileReader
import java.nio.file.Files
import java.nio.file.Path

data class ComposeArchitectureAnalysis(
    val entities: List<KnowledgeEntity>,
    val relations: List<KnowledgeRelation>
)

/** Reads the declarative runtime topology from root docker-compose files. */
object ComposeArchitectureAdapter {
    private val composeFilePattern = Regex("docker-compose(?:\\.[A-Za-z0-9_.-]+)?\\.(?:yml|yaml)")
    private val servicePattern = Regex("^  ([A-Za-z0-9_.-]+):\\s*$")
    private val dependencyPattern = Regex("^\\s{6}(?:-\\s*)?([A-Za-z0-9_.-]+):?(?:\\s|$)")

    fun analyze(root: Path, repository: String, revision: String): ComposeArchitectureAnalysis {
        if (!Files.isDirectory(root)) return ComposeArchitectureAnalysis(emptyList(), emptyList())
        val services = linkedMapOf<String, ServiceDefinition>()
        Files.list(root).use { files ->
            files.filter { composeFilePattern.matches(it.fileName.toString()) }
                .sorted()
                .forEach { file -> parse(file, root, repository, revision, services) }
        }
        val entities = services.values.map { service ->
            KnowledgeEntity(
                EntityId("runtime:${service.name}"),
                "runtime_service",
                service.name,
                service.evidence,
                service.summary
            )
        }
        val knownModules = setOf("backend")
        val relations = services.values.flatMap { service ->
            val dependencies = service.dependencies.map { dependency ->
                KnowledgeRelation(
                    EntityId("runtime:${service.name}"),
                    "depends_on",
                    EntityId("runtime:$dependency"),
                    Origin.DECLARED,
                    Confidence.HIGH,
                    service.evidence
                )
            }
            val implementation = service.buildContext?.trim('/', '.')
                ?.takeIf { it in knownModules }
                ?.let { module ->
                    KnowledgeRelation(
                        EntityId("runtime:${service.name}"),
                        "implemented_by",
                        EntityId("module:$module"),
                        Origin.DECLARED,
                        Confidence.HIGH,
                        service.evidence
                    )
                }
            dependencies + listOfNotNull(implementation)
        }
        return ComposeArchitectureAnalysis(entities, relations.distinctBy { Triple(it.source, it.type, it.target) })
    }

    private fun parse(
        file: Path,
        root: Path,
        repository: String,
        revision: String,
        services: MutableMap<String, ServiceDefinition>
    ) {
        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
        val lines = TextFileReader.readLines(file)
        var inServices = false
        var current: ServiceDefinition? = null
        var inDependsOn = false
        var buildIndent = -1
        lines.forEachIndexed { index, line ->
            if (line == "services:") {
                inServices = true
                return@forEachIndexed
            }
            if (!inServices) return@forEachIndexed
            servicePattern.matchEntire(line)?.let { match ->
                current = ServiceDefinition(
                    match.groupValues[1],
                    Evidence(repository, revision, relative, index + 1, index + 1, "compose-adapter-0.1")
                ).also { services.putIfAbsent(it.name, it) }
                inDependsOn = false
                buildIndent = -1
                return@forEachIndexed
            }
            val service = current ?: return@forEachIndexed
            if (line.matches(Regex("^    depends_on:\\s*$"))) {
                inDependsOn = true
                buildIndent = -1
                return@forEachIndexed
            }
            if (line.matches(Regex("^    build:\\s*$"))) {
                inDependsOn = false
                buildIndent = 6
                return@forEachIndexed
            }
            if (line.trimStart().startsWith("image:") || line.trimStart().startsWith("environment:") || line.trimStart().startsWith("ports:")) {
                inDependsOn = false
            }
            if (inDependsOn) {
                dependencyPattern.find(line)?.groupValues?.get(1)?.let(service.dependencies::add)
            }
            if (buildIndent == 6 && line.startsWith("      context:")) {
                service.buildContext = line.substringAfter(':').trim().trim('"', '\'')
                buildIndent = -1
            }
        }
    }

    private class ServiceDefinition(
        val name: String,
        val evidence: Evidence,
        val dependencies: MutableList<String> = mutableListOf(),
        var buildContext: String? = null
    ) {
        val summary: String get() = buildContext?.let { "build context $it" } ?: "declared compose service"
    }
}
