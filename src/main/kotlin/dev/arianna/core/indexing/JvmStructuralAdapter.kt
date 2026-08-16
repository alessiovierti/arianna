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

data class JvmStructuralAnalysis(
    val entities: List<KnowledgeEntity>,
    val relations: List<KnowledgeRelation>,
    val files: Int
)

/**
 * Conservative Java/Kotlin fallback used when SCIP is unavailable.
 * It deliberately emits medium-confidence relations for syntax that is locally recognisable.
 */
class JvmStructuralAdapter(
    private val analyzerVersion: String = "jvm-structural-adapter-0.1"
) {
    fun analyze(root: Path, repository: String, revision: String): JvmStructuralAnalysis {
        val entities = linkedMapOf<String, KnowledgeEntity>()
        val relations = linkedMapOf<String, KnowledgeRelation>()
        val parentTypes = mutableMapOf<String, List<String>>()
        val overrideCandidates = mutableListOf<OverrideCandidate>()
        val methodNamesByOwner = discoverMethodNames(root)
        var files = 0
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .forEach { file ->
                    files++
                    analyzeFile(root, file, repository, revision, entities, relations, parentTypes, overrideCandidates, methodNamesByOwner)
                }
        }
        overrideCandidates.forEach { candidate ->
            parentTypes[candidate.owner].orEmpty().forEach { parent ->
                addRelation(
                    relations,
                    KnowledgeRelation(
                        candidate.methodId,
                        "overrides",
                        EntityId("method:$parent.${candidate.methodName}"),
                        Origin.STATIC,
                        Confidence.MEDIUM,
                        candidate.evidence
                    )
                )
            }
        }
        relations.values.toList()
            .filter { it.type in setOf("calls", "references") && isTestFile(it.evidence?.file.orEmpty()) }
            .forEach { relation ->
                addRelation(
                    relations,
                    KnowledgeRelation(
                        source = relation.target,
                        type = "tested_by",
                        target = relation.source,
                        origin = Origin.STATIC,
                        confidence = relation.confidence,
                        evidence = relation.evidence
                    )
                )
            }
        return JvmStructuralAnalysis(entities.values.toList(), relations.values.toList(), files)
    }

    private fun analyzeFile(
        root: Path,
        file: Path,
        repository: String,
        revision: String,
        entities: MutableMap<String, KnowledgeEntity>,
        relations: MutableMap<String, KnowledgeRelation>,
        parentTypes: MutableMap<String, List<String>>,
        overrideCandidates: MutableList<OverrideCandidate>,
        methodNamesByOwner: Map<String, Set<String>>
    ) {
        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
        val testFile = isTestFile(relative)
        val lines = Files.readAllLines(file)
        val packageName = lines.asSequence().mapNotNull { packagePattern.matcher(it).takeIf { match -> match.find() }?.group(1) }.firstOrNull()
        var currentType: String? = null
        var currentMethod: EntityId? = null
        var pendingOverride = false
        val variableTypes = mutableMapOf<String, String>()
        val fieldsByOwner = mutableMapOf<String, MutableSet<String>>()
        var pendingMethodText: String? = null
        var pendingMethodStartLine: Int? = null

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val evidence = Evidence(repository, revision, relative, lineNumber, lineNumber, analyzerVersion)
            val declarationText: String
            val declarationEvidence: Evidence
            if (pendingMethodText != null) {
                pendingMethodText = "${pendingMethodText!!.trim()} ${line.trim()}"
                if (!line.contains(")")) return@forEachIndexed
                declarationText = pendingMethodText!!
                declarationEvidence = evidence.copy(startLine = pendingMethodStartLine, endLine = lineNumber)
                pendingMethodText = null
                pendingMethodStartLine = null
            } else if (methodStartPattern.matcher(line).find() && !line.contains(")")) {
                pendingMethodText = line
                pendingMethodStartLine = lineNumber
                return@forEachIndexed
            } else {
                declarationText = line
                declarationEvidence = evidence
            }
            val typeMatch = typePattern.matcher(line).takeIf { it.find() }
            typeMatch?.let { match ->
                val kind = match.group(1).lowercase().let { if (it == "interface") "interface" else "class" }
                val simpleName = match.group(2)
                val qualifiedName = listOfNotNull(packageName, simpleName).joinToString(".")
                currentType = simpleName
                currentMethod = null
                entities.putIfAbsent(
                    "class:$simpleName",
                    KnowledgeEntity(EntityId("class:$simpleName"), if (testFile && kind == "class") "test" else kind, qualifiedName, evidence)
                )
                packageName?.let { packageQualifiedName ->
                    val packageId = EntityId("package:$packageQualifiedName")
                    entities.putIfAbsent(packageId.value, KnowledgeEntity(packageId, "package", packageQualifiedName, evidence))
                    addRelation(
                        relations,
                        KnowledgeRelation(packageId, "contains", EntityId("class:$simpleName"), Origin.STATIC, Confidence.HIGH, evidence)
                    )
                }
                val tail = match.group(3).orEmpty()
                val inheritanceTail = tail.substringBefore("(")
                val parents = mutableListOf<String>()
                inheritancePattern.matcher(inheritanceTail).results().forEach { inheritance ->
                    val relationType = if (inheritance.group(1).equals("implements", ignoreCase = true)) "implements" else "extends"
                    val target = inheritance.group(2).substringBefore(',').trim().substringBefore('<').substringAfterLast('.')
                    parents += target
                    addRelation(
                        relations,
                        KnowledgeRelation(EntityId("class:$simpleName"), relationType, EntityId("class:$target"), Origin.STATIC, Confidence.HIGH, evidence)
                    )
                }
                kotlinInheritancePattern.matcher(inheritanceTail).takeIf { it.find() }?.group(1)?.split(',')?.map { it.trim().substringBefore('<').substringAfterLast('.') }?.filter(String::isNotEmpty)?.forEach { target ->
                    parents += target
                    addRelation(relations, KnowledgeRelation(EntityId("class:$simpleName"), "extends", EntityId("class:$target"), Origin.STATIC, Confidence.HIGH, evidence))
                }
                parentTypes[simpleName] = parents.distinct()
                constructorParameters(match.group(0), variableTypes)
            }

            if (line.contains("@Override")) pendingOverride = true

            val owner = currentType ?: return@forEachIndexed
            val methodMatch = methodPattern.matcher(declarationText).takeIf { it.find() }
            methodMatch?.let { match ->
                val methodName = match.group(1)
                if (methodName !in controlWords && methodName != owner) {
                    val methodId = EntityId("method:$owner.$methodName")
                    currentMethod = methodId
                    entities.putIfAbsent(methodId.value, KnowledgeEntity(methodId, "method", "$owner.$methodName", declarationEvidence, declarationText.trim()))
                    addRelation(
                        relations,
                        KnowledgeRelation(
                            EntityId("class:$owner"),
                            "defines",
                            methodId,
                            Origin.STATIC,
                            Confidence.HIGH,
                            declarationEvidence
                        )
                    )
                    if (pendingOverride || line.contains("override")) {
                        overrideCandidates += OverrideCandidate(owner, methodName, methodId, declarationEvidence)
                    }
                    pendingOverride = false
                    constructorParameters(match.group(2), variableTypes)
                }
            }

            variableDeclarationPattern.matcher(line).takeIf { it.find() }?.let { match ->
                variableTypes[match.group(2)] = match.group(1).substringAfterLast('.')
                if (currentMethod == null && fieldModifierPattern.matcher(line).find()) {
                    addField(owner, match.group(2), evidence, entities, relations, fieldsByOwner)
                }
            }
            kotlinVariablePattern.matcher(line).takeIf { it.find() }?.let { match ->
                variableTypes[match.group(1)] = match.group(2).substringAfterLast('.')
                if (currentMethod == null) addField(owner, match.group(1), evidence, entities, relations, fieldsByOwner)
            }

            val source = currentMethod ?: return@forEachIndexed
            fieldsByOwner[owner].orEmpty().forEach { fieldName ->
                if (Regex("\\b${Regex.escape(fieldName)}\\b").containsMatchIn(line)) {
                    addRelation(
                        relations,
                        KnowledgeRelation(source, "references", EntityId("field:$owner.$fieldName"), Origin.STATIC, Confidence.MEDIUM, evidence)
                    )
                }
            }
            callPattern.matcher(line).results().forEach { call ->
                val receiver = call.group(1)
                val name = call.group(2)
                val targetOwner = variableTypes[receiver] ?: receiver
                addRelation(
                    relations,
                    KnowledgeRelation(source, "calls", EntityId("method:$targetOwner.$name"), Origin.STATIC, Confidence.MEDIUM, evidence)
                )
            }
            unqualifiedCallPattern.matcher(line).results().forEach { call ->
                val name = call.group(1)
                if (name in controlWords || name == methodMatch?.group(1) || name !in methodNamesByOwner[owner].orEmpty()) return@forEach
                addRelation(
                    relations,
                    KnowledgeRelation(source, "calls", EntityId("method:$owner.$name"), Origin.STATIC, Confidence.MEDIUM, evidence)
                )
            }
        }
    }

    private fun discoverMethodNames(root: Path): Map<String, Set<String>> {
        val methods = mutableMapOf<String, MutableSet<String>>()
        Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .forEach { file ->
                    var owner: String? = null
                    var pendingMethodText: String? = null
                    Files.readAllLines(file).forEach { line ->
                        typePattern.matcher(line).takeIf { it.find() }?.let { owner = it.group(2) }
                        val candidate = if (pendingMethodText != null) {
                            pendingMethodText = "${pendingMethodText!!.trim()} ${line.trim()}"
                            if (line.contains(")")) pendingMethodText.also { pendingMethodText = null } else null
                        } else if (methodStartPattern.matcher(line).find() && !line.contains(")")) {
                            pendingMethodText = line
                            null
                        } else {
                            line
                        }
                        val method = candidate?.let { methodPattern.matcher(it).takeIf { matcher -> matcher.find() }?.group(1) }
                        if (owner != null && method != null && method !in controlWords && method != owner) {
                            methods.getOrPut(requireNotNull(owner)) { mutableSetOf() } += method
                        }
                    }
                }
        }
        return methods.mapValues { it.value.toSet() }
    }

    private fun constructorParameters(text: String, variableTypes: MutableMap<String, String>) {
        parameterPattern.matcher(text).results().forEach { match ->
            variableTypes[match.group(2)] = match.group(1).substringAfterLast('.')
        }
    }

    private data class OverrideCandidate(
        val owner: String,
        val methodName: String,
        val methodId: EntityId,
        val evidence: Evidence
    )

    private fun addField(
        owner: String,
        name: String,
        evidence: Evidence,
        entities: MutableMap<String, KnowledgeEntity>,
        relations: MutableMap<String, KnowledgeRelation>,
        fieldsByOwner: MutableMap<String, MutableSet<String>>
    ) {
        val fieldId = EntityId("field:$owner.$name")
        entities.putIfAbsent(fieldId.value, KnowledgeEntity(fieldId, "field", "$owner.$name", evidence))
        fieldsByOwner.getOrPut(owner) { mutableSetOf() } += name
        addRelation(relations, KnowledgeRelation(EntityId("class:$owner"), "contains", fieldId, Origin.STATIC, Confidence.HIGH, evidence))
    }

    private fun addRelation(relations: MutableMap<String, KnowledgeRelation>, relation: KnowledgeRelation) {
        val key = "${relation.source.value}|${relation.type}|${relation.target.value}|${relation.evidence?.file}:${relation.evidence?.startLine}"
        relations.putIfAbsent(key, relation)
    }

    private fun isTestFile(file: String): Boolean =
        file.contains("/test/") ||
            file.startsWith("test/") ||
            Regex("(^|/)[^/]*(Test|Tests)\\.(java|kt)$").containsMatchIn(file)

    companion object {
        private val packagePattern = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)")
        private val typePattern = Pattern.compile("\\b(class|interface|object|enum\\s+class|record)\\s+([A-Za-z_][A-Za-z0-9_]*)([^\\{]*)")
        private val inheritancePattern = Pattern.compile("\\b(implements|extends)\\s+([A-Za-z_][A-Za-z0-9_.<>]*)")
        private val kotlinInheritancePattern = Pattern.compile(":\\s*([A-Za-z_][A-Za-z0-9_.<>?, ]*)")
        private val methodPattern = Pattern.compile("(?:^|\\s)(?:public|private|protected|internal|static|final|suspend|override|open|abstract|fun|synchronized|inline|operator|infix|tailrec|(?:[A-Za-z_][A-Za-z0-9_<>?.\\[\\], ]*)\\s+)+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
        private val methodStartPattern = Pattern.compile("\\b(?:fun|public|private|protected|internal|override|abstract|static|final|suspend)\\b[^;=]*\\(")
        private val parameterPattern = Pattern.compile("(?:^|[,<(])\\s*([A-Z][A-Za-z0-9_.<>?]*)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        private val variableDeclarationPattern = Pattern.compile("\\b([A-Z][A-Za-z0-9_.<>?]*)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*(?:=|;|,)")
        private val kotlinVariablePattern = Pattern.compile("\\b(?:val|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*:\\s*([A-Z][A-Za-z0-9_.<>?]*)")
        private val callPattern = Pattern.compile("\\b([a-zA-Z_][A-Za-z0-9_]*)\\s*\\.\\s*([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(")
        private val unqualifiedCallPattern = Pattern.compile("(?<![.\\w])([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(")
        private val fieldModifierPattern = Pattern.compile("\\b(private|protected|public|static|final)\\b")
        private val controlWords = setOf("if", "for", "while", "switch", "when", "catch", "return")
    }
}
