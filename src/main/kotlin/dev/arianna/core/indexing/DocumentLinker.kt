package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin

/**
 * Creates deliberately weak links from source entities to textual documentation.
 * A textual mention is evidence, not proof of a runtime or structural dependency.
 */
object DocumentLinker {
    private const val ANALYZER_VERSION = "document-linker-0.1"
    private val excludedKinds = setOf("document", "file", "repository", "configuration_property", "qualifier")
    private val simpleNameKinds = setOf("class", "interface", "service", "repository", "controller", "configuration", "bean")
    private val tokenPattern = Regex("[A-Za-z0-9_$#.]+")

    fun link(documents: List<KnowledgeEntity>, entities: List<KnowledgeEntity>): List<KnowledgeRelation> {
        val candidates = entities.asSequence()
            .filter { it.kind !in excludedKinds }
            .flatMap { entity ->
                sequenceOf(entity.qualifiedName to entity).plus(
                    if (entity.kind in simpleNameKinds) {
                        sequenceOf(entity.qualifiedName.substringAfterLast('.').substringAfterLast('#') to entity)
                    } else emptySequence()
                )
            }
            .filter { it.first.length >= 3 }
            .distinctBy { "${it.first}|${it.second.id.value}" }
            .sortedByDescending { it.first.length }
            .fold(linkedMapOf<String, KnowledgeEntity>()) { result, (name, entity) ->
                result.putIfAbsent(name, entity)
                result
            }

        return documents.flatMap { document ->
            val evidence = document.evidence ?: return@flatMap emptyList()
            document.content.orEmpty().lineSequence().mapIndexedNotNull { index, line ->
                findCandidate(line, candidates)?.let { target ->
                    KnowledgeRelation(
                        source = target.id,
                        type = "documented_by",
                        target = document.id,
                        origin = Origin.INFERRED,
                        confidence = Confidence.LOW,
                        evidence = Evidence(
                            evidence.repository,
                            evidence.revision,
                            evidence.file,
                            index + 1,
                            index + 1,
                            ANALYZER_VERSION
                        )
                    )
                }
            }.toList()
        }.distinctBy { Triple(it.source, it.target, it.evidence?.startLine) }
    }

    /**
     * Find the longest candidate mentioned in a line in roughly O(line length),
     * rather than testing every candidate with a newly compiled regular expression.
     * A token is split at `.` and `#` boundaries so both `PaymentService` and
     * `com.example.PaymentService` retain the old boundary semantics.
     */
    private fun findCandidate(line: String, candidates: Map<String, KnowledgeEntity>): KnowledgeEntity? {
        var bestName: String? = null
        tokenPattern.findAll(line).forEach { match ->
            val token = match.value
            val boundaries = buildList {
                add(0)
                token.forEachIndexed { index, character ->
                    if (character == '.' || character == '#') {
                        add(index)
                        add(index + 1)
                    }
                }
                add(token.length)
            }.distinct().sorted()
            for (startIndex in boundaries.dropLast(1)) {
                for (endIndex in boundaries.drop(1)) {
                    if (endIndex <= startIndex) continue
                    val name = token.substring(startIndex, endIndex)
                    if (name.length >= 3 && candidates.containsKey(name) && name.length > (bestName?.length ?: 0)) {
                        bestName = name
                    }
                }
            }
        }
        return bestName?.let(candidates::get)
    }
}
