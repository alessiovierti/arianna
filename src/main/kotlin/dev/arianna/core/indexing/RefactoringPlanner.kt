package dev.arianna.core.indexing

import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.RefactoringPlan
import dev.arianna.core.model.RefactoringStep

object RefactoringPlanner {
    private val order = listOf(
        "api" to "Interfaces and APIs",
        "implementations" to "Implementations and overrides",
        "callers" to "Callers and references",
        "spring" to "Spring wiring",
        "tests" to "Tests and mocks",
        "consumers" to "Consumers and documentation",
        "verification" to "Compilation and external tests"
    )

    fun plan(report: ImpactReport): RefactoringPlan {
        val grouped = report.findings.groupBy { categoryFor(it.category) }
        val steps = order.mapIndexedNotNull { index, (category, title) ->
            if (category == "verification") {
                RefactoringStep(index + 1, category, title, listOf("Run repository compilation, unit tests, and integration tests."), emptyList(), 0)
            } else {
                val findings = grouped[category].orEmpty()
                if (findings.isEmpty()) null else RefactoringStep(
                    index + 1,
                    category,
                    title,
                    actionsFor(category, findings),
                    findings.mapNotNull { it.entityId }.distinct(),
                    findings.size,
                    findings.mapNotNull { it.evidence }.distinct()
                )
            }
        }
        return RefactoringPlan(report.baseRevision, report.overlayRevision, steps)
    }

    private fun categoryFor(category: String): String = when (category) {
        "changed_entity", "removed_entity", "added_entity" -> "api"
        "implementations_and_overrides" -> "implementations"
        "direct_callers", "related_usage" -> "callers"
        "spring_wiring" -> "spring"
        "tests_and_mocks" -> "tests"
        "module_consumers" -> "consumers"
        "documents", "new_relation" -> "consumers"
        else -> "consumers"
    }

    private fun actionsFor(category: String, findings: List<dev.arianna.core.model.ImpactFinding>): List<String> = when (category) {
        "api" -> listOf("Confirm the new API signature or symbol and update interfaces/contracts first.")
        "implementations" -> listOf("Update implementations, overrides, and concrete classes compatible with the contract.")
        "callers" -> listOf("Update the listed callers and verify unresolved references.")
        "spring" -> listOf("Verify the affected Spring injections, beans, endpoints, and events.")
        "tests" -> listOf("Update tests, fixtures, and mocks while preserving coverage for the changed behavior.")
        else -> listOf("Verify consumers, documentation, and low-confidence relations before changing code.")
    } + findings.mapNotNull { it.evidence?.let { evidence -> "Check ${evidence.file}:${evidence.startLine ?: "?"}." } }.distinct()
}
