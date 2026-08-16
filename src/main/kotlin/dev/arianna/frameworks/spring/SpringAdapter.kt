package dev.arianna.frameworks.spring

import dev.arianna.core.api.IndexResult
import dev.arianna.core.api.Indexer
import dev.arianna.core.api.IndexProgress
import dev.arianna.core.api.IndexProgressListener
import dev.arianna.core.api.NoopIndexProgressListener
import dev.arianna.core.api.SnapshotStore
import dev.arianna.core.api.Source
import dev.arianna.core.api.Store
import dev.arianna.core.error.IndexingException
import dev.arianna.core.indexing.DocumentIndexer
import dev.arianna.core.indexing.DocumentLinker
import dev.arianna.core.indexing.ComposeArchitectureAdapter
import dev.arianna.core.indexing.JvmStructuralAdapter
import dev.arianna.core.indexing.JvmModuleAdapter
import dev.arianna.core.indexing.HttpRouteAdapter
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

data class SpringAnalysis(
    val entities: List<KnowledgeEntity>,
    val relations: List<KnowledgeRelation>,
    val files: Int
)

private data class BeanTypeInfo(val count: Int, val primaryCount: Int)

class SpringAdapter(
    private val analyzerVersion: String = "spring-adapter-0.1"
) {
    fun analyze(root: Path, repository: String, revision: String): SpringAnalysis {
        val entities = linkedMapOf<String, KnowledgeEntity>()
        val relations = linkedMapOf<String, KnowledgeRelation>()
        var fileCount = 0
        val sourceFiles = Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter {
                    it.toString().endsWith(".java") ||
                        it.toString().endsWith(".kt") ||
                        it.toString().endsWith(".yml") ||
                        it.toString().endsWith(".yaml") ||
                        it.toString().endsWith(".properties")
                }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .toList()
        }
        val beanTypeCounts = discoverBeanTypeCounts(sourceFiles)
        sourceFiles.forEach { file ->
            fileCount++
            if (file.toString().endsWith(".java") || file.toString().endsWith(".kt")) {
                analyzeFile(root, file, repository, revision, entities, relations, beanTypeCounts)
            } else {
                analyzeConfigurationFile(root, file, repository, revision, entities, relations)
            }
        }
        return SpringAnalysis(entities.values.toList(), relations.values.toList(), fileCount)
    }

    private fun analyzeFile(
        root: Path,
        file: Path,
        repository: String,
        revision: String,
        entities: MutableMap<String, KnowledgeEntity>,
        relations: MutableMap<String, KnowledgeRelation>,
        beanTypeCounts: Map<String, BeanTypeInfo>
    ) {
        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
        val lines = Files.readAllLines(file)
        var currentType: String? = null
        var pendingComponent: String? = null
        var pendingEndpoint: EndpointAnnotation? = null
        var controllerPath: String? = null
        var pendingEvent = false
        var pendingAutowired = false
        var pendingQualifier: String? = null
        val springManagedTypes = mutableSetOf<String>()

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val evidence = Evidence(repository, revision, relative, lineNumber, lineNumber, analyzerVersion)
            if (dynamicSpringPattern.matcher(line).find()) {
                val unresolvedId = EntityId("spring-unresolved:$relative:$lineNumber")
                entities.putIfAbsent(
                    unresolvedId.value,
                    KnowledgeEntity(unresolvedId, "unresolved_spring", line.trim(), evidence, line.trim())
                )
                addRelation(
                    relations,
                    KnowledgeRelation(
                        EntityId("file:$relative"),
                        "unresolved",
                        unresolvedId,
                        Origin.INFERRED,
                        Confidence.LOW,
                        evidence
                    )
                )
            }
            componentPattern.matcher(line).takeIf { it.find() }?.let {
                pendingComponent = it.group(1).lowercase()
            }
            endpointPattern.matcher(line).takeIf { it.find() }?.let {
                pendingEndpoint = EndpointAnnotation(httpMethod(it.group(1)), it.group(2).orEmpty())
            }
            if (eventListenerPattern.matcher(line).find()) pendingEvent = true
            if (line.contains("@Autowired")) pendingAutowired = true
            qualifierPattern.matcher(line).takeIf { it.find() }?.let { pendingQualifier = it.group(1) }

            typePattern.matcher(line).takeIf { it.find() }?.let { match ->
                val typeName = match.group(1)
                currentType = typeName
                val typeId = EntityId("class:$typeName")
                entities.putIfAbsent(typeId.value, KnowledgeEntity(typeId, "class", typeName, evidence))
                pendingComponent?.let { role ->
                    val componentId = EntityId("spring-component:$typeName")
                    entities[componentId.value] = KnowledgeEntity(componentId, role, typeName, evidence)
                    springManagedTypes += typeName
                    addRelation(relations, KnowledgeRelation(componentId, "component_managed_by_spring", typeId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                    addConstructorRelations(relations, entities, typeId, line, evidence, pendingQualifier, relative, beanTypeCounts)
                    if (constructorTypes(line).isNotEmpty()) pendingQualifier = null
                }
                pendingComponent = null
                pendingEndpoint?.let { endpoint ->
                    controllerPath = endpoint.path
                    pendingEndpoint = null
                }
            }

            if (currentType != null && currentType in springManagedTypes && constructorPattern.matcher(line).find()) {
                val typeId = EntityId("class:$currentType")
                addConstructorRelations(relations, entities, typeId, line, evidence, pendingQualifier, relative, beanTypeCounts)
                if (constructorTypes(line).isNotEmpty()) pendingQualifier = null
            }

            val methodMatch = methodPattern.matcher(line).takeIf { it.find() }
            if (methodMatch != null) {
                val methodName = methodMatch.group(1)
                val owner = currentType ?: "unknown"
                val methodId = EntityId("method:$owner.$methodName")
                entities.putIfAbsent(methodId.value, KnowledgeEntity(methodId, "method", "$owner.$methodName", evidence))

                if (pendingComponent == "bean") {
                    val beanId = EntityId("bean:$owner.$methodName")
                    entities.putIfAbsent(beanId.value, KnowledgeEntity(beanId, "bean", "$owner.$methodName", evidence))
                    addRelation(relations, KnowledgeRelation(EntityId("class:$owner"), "defines_bean", beanId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                    methodReturnType(line)?.let { returnType ->
                        val typeId = EntityId("class:$returnType")
                        entities.putIfAbsent(typeId.value, KnowledgeEntity(typeId, "class", returnType, evidence))
                        addRelation(relations, KnowledgeRelation(beanId, "provides_bean_type", typeId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                    }
                    pendingComponent = null
                }

                pendingEndpoint?.let { endpoint ->
                    val path = joinPaths(controllerPath, endpoint.path)
                    val endpointId = EntityId("endpoint:${endpoint.method}:$path")
                    entities.putIfAbsent(endpointId.value, KnowledgeEntity(endpointId, "endpoint", "${endpoint.method.uppercase()} $path", evidence))
                    addRelation(relations, KnowledgeRelation(methodId, "exposes_endpoint", endpointId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                }
                pendingEndpoint = null

                if (pendingEvent) {
                    val eventType = parameterType(methodMatch.group(0))
                    if (eventType != null) {
                        val eventId = EntityId("event:$eventType")
                        entities.putIfAbsent(eventId.value, KnowledgeEntity(eventId, "event", eventType, evidence))
                        addRelation(relations, KnowledgeRelation(methodId, "handles_event", eventId, Origin.FRAMEWORK, Confidence.MEDIUM, evidence))
                    }
                }
                pendingEvent = false
            }

            if (pendingAutowired) {
                injectionPattern.matcher(line).takeIf { it.find() }?.let { match ->
                    val owner = currentType ?: return@let
                    val dependency = match.group(1)
                    val source = EntityId("class:$owner")
                    val target = EntityId("class:$dependency")
                    addRelation(relations, KnowledgeRelation(source, "injects", target, Origin.FRAMEWORK, Confidence.MEDIUM, evidence))
                    pendingQualifier?.let { qualifier ->
                        val qualifierId = EntityId("qualifier:$qualifier")
                        entities.putIfAbsent(qualifierId.value, KnowledgeEntity(qualifierId, "qualifier", qualifier, evidence))
                        addRelation(relations, KnowledgeRelation(source, "qualified_by", qualifierId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                        pendingQualifier = null
                    }
                    pendingAutowired = false
                }
            }

            if (pendingAutowired && line.contains("(") && line.contains(")")) {
                val owner = currentType
                val dependency = parameterType(line)
                if (owner != null && dependency != null) {
                    addRelation(relations, KnowledgeRelation(EntityId("class:$owner"), "injects", EntityId("class:$dependency"), Origin.FRAMEWORK, Confidence.MEDIUM, evidence))
                    pendingQualifier?.let { qualifier ->
                        val qualifierId = EntityId("qualifier:$qualifier")
                        entities.putIfAbsent(qualifierId.value, KnowledgeEntity(qualifierId, "qualifier", qualifier, evidence))
                        addRelation(relations, KnowledgeRelation(EntityId("class:$owner"), "qualified_by", qualifierId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
                        pendingQualifier = null
                    }
                    pendingAutowired = false
                }
            }
        }
    }

    private fun analyzeConfigurationFile(
        root: Path,
        file: Path,
        repository: String,
        revision: String,
        entities: MutableMap<String, KnowledgeEntity>,
        relations: MutableMap<String, KnowledgeRelation>
    ) {
        val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
        val lines = Files.readAllLines(file)
        val yamlPath = ArrayDeque<Pair<Int, String>>()

        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---")) return@forEachIndexed

            val key = if (file.toString().endsWith(".properties")) {
                propertiesKeyPattern.matcher(rawLine).takeIf { it.find() }?.group(1)
            } else {
                val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                while (yamlPath.isNotEmpty() && yamlPath.last().first >= indent) yamlPath.removeLast()
                yamlKeyPattern.matcher(trimmed).takeIf { it.find() }?.group(1)?.also { yamlPath.addLast(indent to it) }
                    ?.let { yamlPath.joinToString(".") { part -> part.second } }
            } ?: return@forEachIndexed

            val evidence = Evidence(repository, revision, relative, lineNumber, lineNumber, analyzerVersion)
            val propertyId = EntityId("config:$key")
            entities.putIfAbsent(propertyId.value, KnowledgeEntity(propertyId, "configuration_property", key, evidence))
            addRelation(
                relations,
                KnowledgeRelation(
                    EntityId("file:$relative"),
                    "configures",
                    propertyId,
                    Origin.FRAMEWORK,
                    Confidence.MEDIUM,
                    evidence
                )
            )
        }
    }

    private fun addRelation(relations: MutableMap<String, KnowledgeRelation>, relation: KnowledgeRelation) {
        val key = "${relation.source.value}|${relation.type}|${relation.target.value}|${relation.evidence?.file}:${relation.evidence?.startLine}"
        relations.putIfAbsent(key, relation)
    }

    private fun joinPaths(base: String?, method: String): String {
        val parts = listOfNotNull(base?.takeIf { it.isNotBlank() }, method.takeIf { it.isNotBlank() })
            .map { "/${it.trim('/') }" }
        return parts.joinToString("").ifEmpty { "/" }
    }

    private fun httpMethod(annotation: String): String = when (annotation.lowercase()) {
        "getmapping" -> "get"
        "postmapping" -> "post"
        "putmapping" -> "put"
        "deletemapping" -> "delete"
        else -> "request"
    }

    private fun parameterType(text: String): String? {
        val kotlin = Regex("(?:private\\s+val|val|var)\\s+\\w+\\s*:\\s*([A-Za-z_][A-Za-z0-9_.]*)").find(text)?.groupValues?.get(1)
        if (kotlin != null) return kotlin.substringAfterLast('.')
        val kotlinParameter = Regex("\\b\\w+\\s*:\\s*([A-Z][A-Za-z0-9_.]*)").find(text)?.groupValues?.get(1)
        if (kotlinParameter != null) return kotlinParameter.substringAfterLast('.')
        return Regex("\\(([A-Za-z_][A-Za-z0-9_.]*)\\s+\\w+").find(text)?.groupValues?.get(1)?.substringAfterLast('.')
    }

    private fun methodReturnType(text: String): String? {
        val kotlin = Regex("\\)\\s*:\\s*([A-Z][A-Za-z0-9_.<>?]*)").find(text)?.groupValues?.get(1)
        if (kotlin != null) return kotlin.substringAfterLast('.')
        return Regex("\\b([A-Z][A-Za-z0-9_.<>?]*)\\s+\\w+\\s*\\(").find(text)?.groupValues?.get(1)?.substringAfterLast('.')
    }

    private fun addConstructorRelations(
        relations: MutableMap<String, KnowledgeRelation>,
        entities: MutableMap<String, KnowledgeEntity>,
        source: EntityId,
        text: String,
        evidence: Evidence,
        pendingQualifier: String?,
        relative: String,
        beanTypeCounts: Map<String, BeanTypeInfo>
    ) {
        val dependencies = constructorTypes(text)
        dependencies.forEach { dependency ->
            addRelation(
                relations,
                KnowledgeRelation(source, "injects", EntityId("class:$dependency"), Origin.FRAMEWORK, Confidence.MEDIUM, evidence)
            )
        }
        val qualifier = pendingQualifier ?: qualifierPattern.matcher(text).takeIf { it.find() }?.group(1)
        if (dependencies.isNotEmpty() && qualifier != null) {
            val qualifierId = EntityId("qualifier:$qualifier")
            entities.putIfAbsent(qualifierId.value, KnowledgeEntity(qualifierId, "qualifier", qualifier, evidence))
            addRelation(relations, KnowledgeRelation(source, "qualified_by", qualifierId, Origin.FRAMEWORK, Confidence.HIGH, evidence))
        }
        if (qualifier == null) {
            dependencies.filter {
                val candidates = beanTypeCounts[it]
                candidates != null && candidates.count > 1 && candidates.primaryCount != 1
            }.forEach { dependency ->
                val ambiguityId = EntityId("spring-ambiguity:$relative:${evidence.startLine}")
                entities.putIfAbsent(
                    ambiguityId.value,
                    KnowledgeEntity(ambiguityId, "ambiguous_spring", "${source.value} -> $dependency", evidence)
                )
                addRelation(
                    relations,
                    KnowledgeRelation(source, "ambiguous_injection", ambiguityId, Origin.INFERRED, Confidence.MEDIUM, evidence)
                )
            }
        }
    }

    private fun discoverBeanTypeCounts(files: List<Path>): Map<String, BeanTypeInfo> {
        val counts = mutableMapOf<String, BeanTypeInfo>()
        files.filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }.forEach { file ->
            var pendingBean = false
            var pendingPrimary = false
            Files.readAllLines(file).forEach { line ->
                if (line.contains("@Bean")) pendingBean = true
                if (line.contains("@Primary")) pendingPrimary = true
                if (!pendingBean) return@forEach
                val kotlinType = Regex("\\)\\s*:\\s*([A-Z][A-Za-z0-9_.<>?]*)").find(line)?.groupValues?.get(1)
                val javaType = Regex("\\b([A-Z][A-Za-z0-9_.<>?]*)\\s+\\w+\\s*\\(").find(line)?.groupValues?.get(1)
                val type = (kotlinType ?: javaType)?.substringAfterLast('.')
                if (type != null) {
                    val previous = counts[type] ?: BeanTypeInfo(0, 0)
                    counts[type] = BeanTypeInfo(previous.count + 1, previous.primaryCount + if (pendingPrimary) 1 else 0)
                    pendingBean = false
                    pendingPrimary = false
                }
            }
        }
        return counts
    }

    private fun constructorTypes(text: String): List<String> {
        val kotlinTypes = Regex(":\\s*([A-Z][A-Za-z0-9_.<>?]*)").findAll(text).map { it.groupValues[1].substringAfterLast('.') }
        val javaTypes = Regex("(?:\\(|,)\\s*(?:(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)]*\\))?)\\s*)*([A-Z][A-Za-z0-9_.<>?]*)\\s+\\w+").findAll(text).map { it.groupValues[1].substringAfterLast('.') }
        return (kotlinTypes + javaTypes).distinct().toList()
    }

    private data class EndpointAnnotation(val method: String, val path: String)

    companion object {
        private val componentPattern = Pattern.compile("@(Component|Service|Repository|Controller|Configuration|Bean)\\b")
        private val typePattern = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)")
        private val constructorPattern = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\s*\\([^)]*\\)")
        private val methodPattern = Pattern.compile("\\b(?:fun|void|public|private|protected|internal|suspend)\\s+(?:[A-Za-z_][A-Za-z0-9_<>?.]*\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)")
        private val endpointPattern = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)(?:\\(\\s*(?:[^\"')]*?\\b(?:path|value)\\s*=\\s*)?[\"']([^\"']*)[\"']\\s*\\))?")
        private val eventListenerPattern = Pattern.compile("^\\s*@EventListener\\b")
        private val injectionPattern = Pattern.compile("(?:@Autowired\\s+)?(?:private\\s+|protected\\s+|public\\s+)?(?:final\\s+)?([A-Z][A-Za-z0-9_]*)\\s+\\w+")
        private val qualifierPattern = Pattern.compile("@Qualifier\\(\\s*[\"']([^\"']+)[\"']\\s*\\)")
        private val dynamicSpringPattern = Pattern.compile("@(?:Conditional[A-Za-z0-9_]*|ComponentScan|Import)\\b|\\b(?:getBean|Class\\.forName)\\s*\\(")
        private val propertiesKeyPattern = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*[=:]")
        private val yamlKeyPattern = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*:")
    }
}

class SpringAwareIndexer(
    private val adapter: SpringAdapter = SpringAdapter(),
    private val jvmAdapter: JvmStructuralAdapter = JvmStructuralAdapter(),
    private val moduleAdapter: JvmModuleAdapter = JvmModuleAdapter(),
    private val httpRouteAdapter: HttpRouteAdapter = HttpRouteAdapter()
) : Indexer {
    override fun index(source: Source, store: Store): IndexResult {
        return indexSnapshot(source, store, overlay = false, progress = NoopIndexProgressListener)
    }

    fun index(source: Source, store: Store, progress: IndexProgressListener): IndexResult {
        return indexSnapshot(source, store, overlay = false, progress)
    }

    fun indexOverlay(source: Source, store: Store, progress: IndexProgressListener = NoopIndexProgressListener): IndexResult {
        return indexSnapshot(source, store, overlay = true, progress)
    }

    private fun indexSnapshot(source: Source, store: Store, overlay: Boolean, progress: IndexProgressListener): IndexResult {
        val snapshotStore = store as? SnapshotStore
            ?: throw IndexingException("Spring indexer richiede uno SnapshotStore")
        val status = source.repositoryStatus()
        val root = Path.of(status.root)
        val revision = if (overlay) source.workingTreeRevision() ?: "WORKING_TREE:${status.head}" else status.head
        val totalStages = 7
        progress.onProgress(IndexProgress("discover", 0, totalStages, "Discovering repository files"))
        val fileEntities = Files.walk(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filterNot { RepositoryPathFilter.isIgnored(root, it) }
                .map { path ->
                    val relative = root.relativize(path).toString().replace(path.fileSystem.separator, "/")
                    KnowledgeEntity(
                        EntityId("file:$relative"),
                        "file",
                        relative,
                        Evidence(status.root, revision, relative, analyzerVersion = "file-indexer-0.1")
                    )
                }
                .toList()
        }
        progress.onProgress(IndexProgress("discover", 1, totalStages, "Discovered ${fileEntities.size} files"))
        progress.onProgress(IndexProgress("spring", 1, totalStages, "Analyzing Spring components and configuration"))
        val analysis = adapter.analyze(root, status.root, revision)
        progress.onProgress(IndexProgress("spring", 2, totalStages, "Analyzed ${analysis.entities.size} Spring entities"))
        progress.onProgress(IndexProgress("jvm", 2, totalStages, "Analyzing JVM symbols and relationships"))
        val jvmAnalysis = jvmAdapter.analyze(root, status.root, revision)
        progress.onProgress(IndexProgress("jvm", 3, totalStages, "Analyzed ${jvmAnalysis.entities.size} JVM entities"))
        progress.onProgress(IndexProgress("modules", 3, totalStages, "Analyzing declared modules"))
        val moduleAnalysis = moduleAdapter.analyze(root, status.root, revision)
        progress.onProgress(IndexProgress("modules", 4, totalStages, "Analyzed ${moduleAnalysis.entities.size} modules"))
        progress.onProgress(IndexProgress("documents", 4, totalStages, "Indexing documents and configuration"))
        val documentEntities = DocumentIndexer.analyze(root, status.root, revision).entities
        progress.onProgress(IndexProgress("documents", 5, totalStages, "Indexed ${documentEntities.size} documents"))
        progress.onProgress(IndexProgress("routes", 5, totalStages, "Analyzing HTTP routes"))
        val httpAnalysis = httpRouteAdapter.analyze(root, status.root, revision)
        val composeAnalysis = ComposeArchitectureAdapter.analyze(root, status.root, revision)
        val allEntities = fileEntities + analysis.entities + jvmAnalysis.entities + moduleAnalysis.entities + httpAnalysis.entities + composeAnalysis.entities + documentEntities
        progress.onProgress(IndexProgress("relations", 5, totalStages, "Linking documents to indexed entities"))
        val documentRelations = DocumentLinker.link(documentEntities, allEntities)
        val relations = analysis.relations + jvmAnalysis.relations + moduleAnalysis.relations + httpAnalysis.relations + composeAnalysis.relations + documentRelations
        progress.onProgress(IndexProgress("relations", 6, totalStages, "Normalized ${relations.size} relationships"))
        progress.onProgress(IndexProgress("publish", 6, totalStages, "Publishing snapshot"))
        if (overlay) {
            snapshotStore.replaceOverlaySnapshot(
                status.root,
                revision,
                allEntities.asSequence(),
                relations.asSequence()
            )
        } else {
            snapshotStore.replaceSnapshot(
                status.root,
                revision,
                allEntities.asSequence(),
                relations.asSequence()
            )
        }
        progress.onProgress(IndexProgress("publish", 7, totalStages, "Index complete"))
        return IndexResult(fileEntities.size, allEntities.size, relations.size, revision)
    }
}
