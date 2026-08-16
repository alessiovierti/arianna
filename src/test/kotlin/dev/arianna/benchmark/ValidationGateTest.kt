package dev.arianna.benchmark

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ValidationGateTest {
    @Test
    fun `does not pass without a real direct baseline`() {
        val report = ValidationGateEvaluator.evaluate(
            listOf(
                ValidationObservation("B01", ValidationCondition.AGENT_ARIANNA, 20, 3, 2, 0, true, true, true, true)
            )
        )

        assertFalse(report.passed)
        assertTrue(report.criteria["baseline_present"] == false)
        assertTrue(report.missingTasks.contains("B01"))
    }

    @Test
    fun `passes only when baseline, Arianna evidence and improvement are recorded`() {
        val report = ValidationGateEvaluator.evaluate(
            listOf(
                ValidationObservation("B01", ValidationCondition.DEVELOPER_DIRECT, 100, 20, 0, 3, false, false, false, true),
                ValidationObservation("B01", ValidationCondition.AGENT_ARIANNA_WITH_TESTS, 80, 8, 4, 1, true, true, true, true)
            )
        )

        assertTrue(report.passed)
        assertTrue(report.criteria.values.all { it })
    }

    @Test
    fun `does not combine validation criteria across different tasks`() {
        val report = ValidationGateEvaluator.evaluate(
            listOf(
                ValidationObservation("B01", ValidationCondition.DEVELOPER_DIRECT, 100, 20, 0, 3, false, false, false, true),
                ValidationObservation("B01", ValidationCondition.AGENT_ARIANNA, 80, 8, 4, 1, true, true, false, true),
                ValidationObservation("B02", ValidationCondition.DEVELOPER_DIRECT, 100, 20, 0, 3, false, false, false, true),
                ValidationObservation("B02", ValidationCondition.AGENT_ARIANNA, 80, 8, 4, 1, false, false, true, true)
            )
        )

        assertFalse(report.passed)
        assertFalse(report.criteria.getValue("report_before_compile"))
        assertFalse(report.criteria.getValue("evidence_verified"))
        assertFalse(report.criteria.getValue("uncertainty_declared"))
    }

    @Test
    fun `reads observations from JSON file`() {
        val file = createTempDirectory("arianna-validation-").resolve("observations.json")
        file.writeText(
            """
            [{
              "taskId":"B01",
              "condition":"DEVELOPER_DIRECT",
              "durationMillis":100,
              "exploredFiles":20,
              "queryCount":0,
              "correctionCycles":3,
              "reportUsedBeforeCompile":false,
              "evidenceVerified":false,
              "unresolvedDeclared":false,
              "completed":true
            }]
            """.trimIndent()
        )

        val observations = ValidationObservationFile.read(file)

        assertEquals("B01", observations.single().taskId)
        assertEquals(ValidationCondition.DEVELOPER_DIRECT, observations.single().condition)
        assertEquals(100, observations.single().durationMillis)
    }

    @Test
    fun `rejects observations with missing typed fields`() {
        val file = createTempDirectory("arianna-validation-invalid-").resolve("observations.json")
        file.writeText(
            """
            [{
              "taskId":"B01",
              "condition":"DEVELOPER_DIRECT",
              "durationMillis":100,
              "exploredFiles":20,
              "queryCount":0,
              "correctionCycles":3,
              "reportUsedBeforeCompile":false,
              "evidenceVerified":false,
              "completed":true
            }]
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> { ValidationObservationFile.read(file) }
    }
}
