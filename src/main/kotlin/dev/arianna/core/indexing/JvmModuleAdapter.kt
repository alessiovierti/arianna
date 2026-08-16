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

data class JvmModuleAnalysis(
    val entities: List<KnowledgeEntity>,
    val relations: List<KnowledgeRelation>
)

/** Reads only explicitly declared Gradle/Maven modules; dynamic convention plugins remain unknown. */
class JvmModuleAdapter(
    private val analyzerVersion: String = "jvm-module-adapter-0.1"
) {
    fun analyze(root: Path, repository: String, revision: String): JvmModuleAnalysis {
        val modules = linkedMapOf<String, Evidence>()
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { it.fileName.toString() in setOf("settings.gradle", "settings.gradle.kts") }
                .filter(Files::isRegularFile)
                .forEach { file ->
                    val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
                    val parent = file.parent?.let { root.relativize(it).toString().replace(file.fileSystem.separator, "/") }.orEmpty()
                    val prefix = parent.takeIf { it.isNotEmpty() }?.replace('/', ':')
                    val evidence = Evidence(repository, revision, relative, analyzerVersion = analyzerVersion)
                    parseIncludedModules(Files.readString(file)).forEach { included ->
                        val moduleName = listOfNotNull(prefix, included).joinToString(":")
                        modules.putIfAbsent(moduleName, evidence)
                    }
                }
        }
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { it.fileName.toString() == "pom.xml" }
                .filter(Files::isRegularFile)
                .forEach { file ->
                    val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
                    val parent = file.parent?.let { root.relativize(it).toString().replace(file.fileSystem.separator, "/") }.orEmpty()
                    val prefix = parent.takeIf { it.isNotEmpty() }?.replace('/', ':')
                    val evidence = Evidence(repository, revision, relative, analyzerVersion = analyzerVersion)
                    modulePattern.matcher(Files.readString(file)).results().forEach { match ->
                        val moduleName = listOfNotNull(prefix, match.group(1).trim()).joinToString(":")
                        modules.putIfAbsent(moduleName, evidence)
                    }
                }
        }

        val entities = modules.map { (moduleName, evidence) ->
            KnowledgeEntity(EntityId("module:$moduleName"), "module", moduleName, evidence)
        }
        val relations = modules.flatMap { (moduleName, evidence) ->
            val modulePath = root.resolve(moduleName.replace(':', '/')).normalize()
            if (!modulePath.startsWith(root) || !Files.isDirectory(modulePath)) emptyList()
            else Files.walk(modulePath).use { stream ->
                stream.iterator().asSequence()
                    .filter(Files::isRegularFile)
                    .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                    .map { file ->
                        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
                        KnowledgeRelation(
                            EntityId("module:$moduleName"),
                            "contains",
                            EntityId("file:$relative"),
                            Origin.DECLARED,
                            Confidence.HIGH,
                            evidence
                        )
                    }
                    .toList()
            }
        }.toMutableList()
        val declaredModuleNames = modules.keys.toSet()
        modules.keys.forEach { moduleName ->
            val modulePath = root.resolve(moduleName.replace(':', '/')).normalize()
            if (!modulePath.startsWith(root) || !Files.isDirectory(modulePath)) return@forEach
            val buildFile = sequenceOf("build.gradle", "build.gradle.kts", "pom.xml")
                .map(modulePath::resolve)
                .firstOrNull(Files::isRegularFile)
                ?: return@forEach
            val relative = root.relativize(buildFile).toString().replace(buildFile.fileSystem.separator, "/")
            val evidence = Evidence(repository, revision, relative, analyzerVersion = analyzerVersion)
            val buildText = Files.readString(buildFile)
            val dependencies = buildList {
                projectDependencyPattern.matcher(buildText).results().forEach { add(it.group(1)) }
                if (buildFile.fileName.toString() == "pom.xml") {
                    mavenDependencyPattern.matcher(buildText).results().forEach { add(it.group(1)) }
                }
            }
            dependencies.forEach { rawTarget ->
                val targetName = rawTarget.removePrefix(":").trim()
                val parent = moduleName.substringBeforeLast(':', "")
                val target = when {
                    targetName in declaredModuleNames -> targetName
                    parent.isNotEmpty() && "$parent:$targetName" in declaredModuleNames -> "$parent:$targetName"
                    else -> targetName
                }
                if (target in declaredModuleNames) {
                    relations += KnowledgeRelation(
                        EntityId("module:$moduleName"),
                        "depends_on",
                        EntityId("module:$target"),
                        Origin.DECLARED,
                        Confidence.HIGH,
                        evidence
                    )
                }
            }
        }
        return JvmModuleAnalysis(entities, relations.distinctBy { Triple(it.source, it.type, it.target) })
    }

    companion object {
        /**
         * Match only the quoted arguments belonging to one include statement.
         * The previous `[^)]*` expression consumed the rest of a Groovy settings
         * file when the statement did not use parentheses.
         */
        private val includePattern = Pattern.compile(
            "(?m)^\\s*include\\s*(?:\\(\\s*)?((?:['\"][^'\"]+['\"]\\s*,?\\s*)+)(?:\\)|$)"
        )
        private val includeArgumentPattern = Pattern.compile("['\"]([^'\"]+)['\"]")
        private val modulePattern = Pattern.compile("<module>\\s*([^<]+)\\s*</module>")
        private val projectDependencyPattern = Pattern.compile("project\\s*\\([^)]*[\"'](:[^\"']+)[\"']")
        private val mavenDependencyPattern = Pattern.compile("(?s)<dependency>.*?<artifactId>\\s*([^<]+)\\s*</artifactId>.*?</dependency>")
    }

    private fun parseIncludedModules(text: String): List<String> = includePattern.matcher(text).results()
        .flatMap { match ->
            includeArgumentPattern.matcher(match.group(1)).results().map { it.group(1) }
        }
        .map { it.trim().removePrefix(":") }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}
