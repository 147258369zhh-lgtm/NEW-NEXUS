package io.github.zhh_lgtm.nexus.kernel.api

import org.junit.Assert.*
import org.junit.Test

class PlanDependenciesTest {
    private fun step(id: String, vararg dependencies: String) = PlanStep(id, id, "", dependencies.toSet(),
        effectClass = EffectClass.READ, verification = "inspect output", reversible = true)

    @Test fun independentAndDiamondDependenciesAreAccepted() {
        val plan = PlanDocument(PlanId("plan"), null, 1, listOf(step("a"), step("b", "a"), step("c", "a"), step("d", "b", "c")))
        assertEquals(4, plan.steps.size)
    }

    @Test fun selfDependencyAndIndirectCyclesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlanDocument(PlanId("plan"), null, 1, listOf(step("a", "a"))) }
        assertThrows(IllegalArgumentException::class.java) {
            PlanDocument(PlanId("plan"), null, 1, listOf(step("a", "b"), step("b", "c"), step("c", "a")))
        }
    }
}
