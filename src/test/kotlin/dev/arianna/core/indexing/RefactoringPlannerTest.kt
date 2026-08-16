package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.ImpactCertainty
import dev.arianna.core.model.ImpactFinding
import dev.arianna.core.model.ImpactReport
import dev.arianna.core.model.ImpactSeverity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefactoringPlannerTest {
    @Test
    fun `orders refactoring actions and always requires external verification`() {
        val evidence = Evidence("repo", "HEAD", "src/Payment.kt", 4, 4, "test")
        val findings = listOf(
            ImpactFinding(ImpactSeverity.POSSIBLE, ImpactCertainty.CONFIRMED, "tests_and_mocks", "update test", EntityId("test:PaymentTest"), evidence = evidence),
            ImpactFinding(ImpactSeverity.POSSIBLE, ImpactCertainty.CONFIRMED, "direct_callers", "update caller", EntityId("method:Caller.run"), evidence = evidence),
            ImpactFinding(ImpactSeverity.POSSIBLE, ImpactCertainty.LIKELY, "spring_wiring", "update bean", EntityId("class:Payment"), evidence = evidence)
        )

        val plan = RefactoringPlanner.plan(ImpactReport("base", "head", emptyList(), findings))

        assertEquals(listOf("callers", "spring", "tests", "verification"), plan.steps.map { it.category })
        assertTrue(plan.externalVerificationRequired)
        assertTrue(plan.note.contains("compilation"))
    }
}
