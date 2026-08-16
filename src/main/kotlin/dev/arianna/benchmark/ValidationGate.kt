package dev.arianna.benchmark

enum class ValidationCondition {
    DEVELOPER_DIRECT,
    AGENT_DIRECT,
    AGENT_ARIANNA,
    AGENT_ARIANNA_WITH_TESTS
}

data class ValidationObservation(
    val taskId: String,
    val condition: ValidationCondition,
    val durationMillis: Long,
    val exploredFiles: Int,
    val queryCount: Int,
    val correctionCycles: Int,
    val reportUsedBeforeCompile: Boolean,
    val evidenceVerified: Boolean,
    val unresolvedDeclared: Boolean,
    val completed: Boolean
)

data class ValidationGateReport(
    val criteria: Map<String, Boolean>,
    val passed: Boolean,
    val missingTasks: Set<String>
)

object ValidationGateEvaluator {
    fun evaluate(observations: List<ValidationObservation>): ValidationGateReport {
        val byTask = observations.groupBy { it.taskId }
        val comparableTasks = byTask.filterValues { taskObservations ->
            taskObservations.any { it.isBaseline() } && taskObservations.any { it.condition.isArianna() }
        }.keys
        val gateTasks = comparableTasks.filter { taskId ->
            val taskObservations = byTask.getValue(taskId)
            hasImprovement(taskObservations) && taskObservations.any { it.condition.isArianna() && it.evidenceVerified } &&
                taskObservations.any { it.condition.isArianna() && it.unresolvedDeclared } &&
                taskObservations.any { it.condition.isArianna() && it.reportUsedBeforeCompile } &&
                taskObservations.any { it.condition.isArianna() && it.completed }
        }
        val criteria = linkedMapOf(
            "baseline_present" to gateTasks.isNotEmpty(),
            "arianna_present" to gateTasks.isNotEmpty(),
            "evidence_verified" to gateTasks.isNotEmpty(),
            "uncertainty_declared" to gateTasks.isNotEmpty(),
            "report_before_compile" to gateTasks.isNotEmpty(),
            "completed_task" to gateTasks.isNotEmpty(),
            "measurable_improvement" to gateTasks.isNotEmpty()
        )
        val missingTasks = byTask.filterValues { taskObservations ->
            taskObservations.none { it.condition.isArianna() } || taskObservations.none { it.condition == ValidationCondition.DEVELOPER_DIRECT || it.condition == ValidationCondition.AGENT_DIRECT }
        }.keys
        return ValidationGateReport(criteria, criteria.values.all { it } && missingTasks.isEmpty(), missingTasks)
    }

    private fun hasImprovement(observations: List<ValidationObservation>): Boolean {
        val baseline = observations.filter { it.isBaseline() }.minByOrNull { it.durationMillis }
            ?: return false
        val arianna = observations.filter { it.condition.isArianna() && it.completed }.minByOrNull { it.durationMillis }
            ?: return false
        return arianna.durationMillis < baseline.durationMillis || arianna.exploredFiles < baseline.exploredFiles || arianna.correctionCycles < baseline.correctionCycles
    }

    private fun ValidationObservation.isBaseline(): Boolean =
        condition == ValidationCondition.DEVELOPER_DIRECT || condition == ValidationCondition.AGENT_DIRECT

    private fun ValidationCondition.isArianna(): Boolean = this == ValidationCondition.AGENT_ARIANNA || this == ValidationCondition.AGENT_ARIANNA_WITH_TESTS
}
