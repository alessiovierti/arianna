package dev.arianna.core.indexing

import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.ImpactCertainty
import dev.arianna.core.model.ImpactSeverity
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.SnapshotChangeKind
import dev.arianna.core.model.SnapshotDiff
import dev.arianna.core.model.EntityChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpactAnalyzerTest {
    @Test
    fun `classifies removed target and static caller as confirmed breaking`() {
        val evidence = Evidence("repo", "base", "src/Caller.kt", 8, 8, "scip")
        val target = KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", evidence)
        val caller = KnowledgeEntity(EntityId("method:Caller.run"), "method", "Caller.run", evidence)
        val relation = KnowledgeRelation(caller.id, "calls", target.id, Origin.STATIC, Confidence.HIGH, evidence)
        val diff = SnapshotDiff(
            "base",
            "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.REMOVED, target.id, before = target)),
            emptyList()
        )

        val report = ImpactAnalyzer.analyze(diff, listOf(target, caller), listOf(caller), listOf(relation), emptyList())

        assertTrue(report.findings.any { it.category == "removed_entity" && it.severity == ImpactSeverity.BREAKING })
        val callerFinding = report.findings.first { it.category == "direct_callers" }
        assertEquals(ImpactSeverity.BREAKING, callerFinding.severity)
        assertEquals(ImpactCertainty.CONFIRMED, callerFinding.certainty)
        assertEquals("src/Caller.kt", callerFinding.evidence?.file)
    }

    @Test
    fun `covers spring endpoints tests documents and unresolved runtime relations`() {
        val evidence = Evidence("repo", "base", "src/Payment.kt", 10, 10, "fixture")
        val changed = KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", evidence)
        val controller = KnowledgeEntity(EntityId("method:PaymentController.get"), "method", "PaymentController.get", evidence)
        val endpoint = KnowledgeEntity(EntityId("endpoint:get:/payments"), "endpoint", "GET /payments", evidence)
        val test = KnowledgeEntity(EntityId("test:PaymentTest"), "test", "PaymentTest", evidence.copy(file = "src/test/PaymentTest.kt"))
        val document = KnowledgeEntity(EntityId("document:README.md"), "document", "README.md", evidence.copy(file = "README.md"), "Payment.process")
        val relations = listOf(
            KnowledgeRelation(controller.id, "exposes_endpoint", changed.id, Origin.FRAMEWORK, Confidence.HIGH, evidence),
            KnowledgeRelation(test.id, "calls", changed.id, Origin.STATIC, Confidence.HIGH, evidence.copy(file = "src/test/PaymentTest.kt")),
            KnowledgeRelation(changed.id, "documented_by", document.id, Origin.INFERRED, Confidence.LOW, evidence.copy(file = "README.md")),
            KnowledgeRelation(EntityId("runtime:unknown"), "calls", changed.id, Origin.RUNTIME, Confidence.LOW, evidence)
        )
        val diff = SnapshotDiff(
            "base", "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, changed.id, before = changed, after = changed.copy(content = "new signature"))),
            emptyList()
        )

        val report = ImpactAnalyzer.analyze(diff, listOf(changed, controller, endpoint, test, document), listOf(changed, controller, endpoint, test, document), relations, relations)

        assertTrue(report.findings.any { it.category == "spring_wiring" && it.certainty == ImpactCertainty.LIKELY })
        assertTrue(report.findings.any { it.category == "tests_and_mocks" })
        assertTrue(report.findings.any { it.category == "documents" && it.certainty == ImpactCertainty.POSSIBLE })
        assertTrue(report.findings.any { it.certainty == ImpactCertainty.UNRESOLVED })
    }

    @Test
    fun `reports dependent modules when a changed entity belongs to a module`() {
        val baseEvidence = Evidence("repo", "base", "api/src/PaymentService.kt", 4, 4, "jvm")
        val changed = KnowledgeEntity(EntityId("method:PaymentService.process"), "method", "PaymentService.process", baseEvidence)
        val serviceFile = KnowledgeEntity(EntityId("file:service/src/Service.kt"), "file", "service/src/Service.kt", baseEvidence.copy(file = "service/src/Service.kt"))
        val diff = SnapshotDiff(
            "base",
            "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, changed.id, before = changed, after = changed.copy(content = "new"))),
            emptyList()
        )
        val moduleRelations = listOf(
            KnowledgeRelation(EntityId("module:api"), "contains", EntityId("file:api/src/PaymentService.kt"), Origin.DECLARED, Confidence.HIGH, baseEvidence),
            KnowledgeRelation(EntityId("module:service"), "depends_on", EntityId("module:api"), Origin.DECLARED, Confidence.HIGH, baseEvidence.copy(file = "service/build.gradle.kts"))
        )

        val report = ImpactAnalyzer.analyze(diff, listOf(changed, serviceFile), listOf(changed, serviceFile), moduleRelations, moduleRelations)

        assertTrue(report.findings.any { it.category == "module_consumers" && it.entityId?.value == "module:service" })
    }

    @Test
    fun `includes changed spring qualifier in wiring impact`() {
        val evidence = Evidence("repo", "base", "src/PaymentService.kt", 5, 5, "spring")
        val qualifier = KnowledgeEntity(EntityId("qualifier:primary"), "qualifier", "primary", evidence)
        val service = KnowledgeEntity(EntityId("class:PaymentService"), "class", "PaymentService", evidence)
        val relation = KnowledgeRelation(service.id, "qualified_by", qualifier.id, Origin.FRAMEWORK, Confidence.HIGH, evidence)
        val diff = SnapshotDiff(
            "base",
            "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, qualifier.id, before = qualifier, after = qualifier.copy(qualifiedName = "secondary"))),
            emptyList()
        )

        val report = ImpactAnalyzer.analyze(diff, listOf(qualifier, service), listOf(qualifier, service), listOf(relation), listOf(relation))

        assertTrue(report.findings.any { it.category == "spring_wiring" && it.relation?.type == "qualified_by" })
    }

    @Test
    fun `does not report unchanged semantic relations as new when evidence revision changes`() {
        val baseEvidence = Evidence("repo", "base", "src/Payment.kt", 4, 4, "jvm")
        val overlayEvidence = baseEvidence.copy(revision = "WORKING_TREE:base")
        val changed = KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", baseEvidence, "new signature")
        val caller = KnowledgeEntity(EntityId("method:Caller.run"), "method", "Caller.run", baseEvidence)
        val baseRelation = KnowledgeRelation(caller.id, "calls", changed.id, Origin.STATIC, Confidence.MEDIUM, baseEvidence)
        val overlayRelation = baseRelation.copy(evidence = overlayEvidence)
        val diff = SnapshotDiff(
            "base",
            "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, changed.id, before = changed, after = changed.copy(evidence = overlayEvidence))),
            emptyList()
        )

        val report = ImpactAnalyzer.analyze(
            diff,
            listOf(changed, caller),
            listOf(changed.copy(evidence = overlayEvidence), caller.copy(evidence = overlayEvidence)),
            listOf(baseRelation),
            listOf(overlayRelation)
        )

        assertTrue(report.findings.any { it.category == "direct_callers" })
        assertTrue(report.findings.none { it.category == "new_relation" })
    }

    @Test
    fun `does not expose structural defines and contains relations as impact findings`() {
        val evidence = Evidence("repo", "base", "src/Payment.kt", 4, 4, "jvm")
        val changed = KnowledgeEntity(EntityId("method:Payment.process"), "method", "Payment.process", evidence, "new signature")
        val owner = KnowledgeEntity(EntityId("class:Payment"), "class", "Payment", evidence)
        val diff = SnapshotDiff(
            "base",
            "WORKING_TREE:base",
            listOf(EntityChange(SnapshotChangeKind.MODIFIED, changed.id, before = changed, after = changed.copy(content = "changed again"))),
            emptyList()
        )

        val report = ImpactAnalyzer.analyze(
            diff,
            listOf(changed, owner),
            listOf(changed, owner),
            listOf(KnowledgeRelation(owner.id, "defines", changed.id, Origin.STATIC, Confidence.HIGH, evidence)),
            emptyList()
        )

        assertTrue(report.findings.none { it.relation?.type == "defines" || it.relation?.type == "contains" })
    }
}
