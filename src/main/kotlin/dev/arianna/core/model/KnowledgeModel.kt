package dev.arianna.core.model

enum class Origin {
    STATIC,
    FRAMEWORK,
    DECLARED,
    INFERRED,
    RUNTIME
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

data class Evidence(
    val repository: String,
    val revision: String,
    val file: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val analyzerVersion: String
)

data class EntityId(val value: String)

data class KnowledgeEntity(
    val id: EntityId,
    val kind: String,
    val qualifiedName: String,
    val evidence: Evidence?,
    val content: String? = null
)

data class KnowledgeRelation(
    val source: EntityId,
    val type: String,
    val target: EntityId,
    val origin: Origin,
    val confidence: Confidence,
    val evidence: Evidence?
)
