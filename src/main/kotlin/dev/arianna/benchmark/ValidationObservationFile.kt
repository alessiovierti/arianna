package dev.arianna.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.readText

object ValidationObservationFile {
    fun read(path: Path, mapper: ObjectMapper = ObjectMapper()): List<ValidationObservation> {
        val root = mapper.readTree(path.readText())
        val observations = if (root.isArray) root else root.path("observations")
        require(observations.isArray) { "The file must contain an observation array or the observations field" }
        return observations.map { node ->
            ValidationObservation(
                taskId = node.text("taskId"),
                condition = runCatching { ValidationCondition.valueOf(node.text("condition")) }
                    .getOrElse { error("condition non valido: ${node.path("condition").asText()}") },
                durationMillis = node.long("durationMillis"),
                exploredFiles = node.int("exploredFiles"),
                queryCount = node.int("queryCount"),
                correctionCycles = node.int("correctionCycles"),
                reportUsedBeforeCompile = node.boolean("reportUsedBeforeCompile"),
                evidenceVerified = node.boolean("evidenceVerified"),
                unresolvedDeclared = node.boolean("unresolvedDeclared"),
                completed = node.boolean("completed")
            )
        }
    }

    private fun JsonNode.text(name: String): String = path(name).asText().takeIf(String::isNotBlank)
        ?: error("campo obbligatorio mancante: $name")

    private fun JsonNode.long(name: String): Long = when {
        !has(name) || path(name).isNull -> error("campo obbligatorio mancante: $name")
        !path(name).isNumber -> error("$name deve essere numerico")
        else -> path(name).asLong()
    }
    private fun JsonNode.int(name: String): Int = long(name).toInt()
    private fun JsonNode.boolean(name: String): Boolean = when {
        !has(name) || path(name).isNull -> error("campo obbligatorio mancante: $name")
        !path(name).isBoolean -> error("$name deve essere booleano")
        else -> path(name).asBoolean()
    }
}
