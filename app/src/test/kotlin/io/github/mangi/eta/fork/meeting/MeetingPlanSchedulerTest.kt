package io.github.mangi.eta.fork.meeting

import org.junit.Assert.*
import org.junit.Test

class MeetingPlanSchedulerTest {
    @Test fun dependencyValidationRejectsMissingRepeatedAndCyclicEdges() {
        assertFalse(MeetingPlanScheduler.validDependencies(mapOf("a" to listOf("missing"))))
        assertFalse(MeetingPlanScheduler.validDependencies(mapOf("a" to listOf("b", "b"), "b" to emptyList())))
        assertFalse(MeetingPlanScheduler.validDependencies(mapOf("a" to listOf("b"), "b" to listOf("a"))))
        assertTrue(MeetingPlanScheduler.validDependencies(mapOf("a" to emptyList(), "b" to listOf("a"))))
    }
    @Test fun failedOrEmptyDependenciesNeverProvideInputs() {
        assertNull(MeetingPlanScheduler.inputs(listOf("a"), mapOf("a" to PlanStepResult("", "failed"))))
        assertNull(MeetingPlanScheduler.inputs(listOf("a"), mapOf("a" to PlanStepResult("  "))))
        assertNull(MeetingPlanScheduler.inputs(listOf("a"), emptyMap()))
    }

    @Test fun parallelStepsOnlyReceiveDeclaredInputs() {
        val completed = mapOf("a" to PlanStepResult("A"), "b" to PlanStepResult("B"))
        val pending = linkedMapOf("c" to listOf("a"), "d" to listOf("b"))
        assertEquals(listOf("c", "d"), MeetingPlanScheduler.ready(pending, completed))
        assertEquals(mapOf("a" to "A"), MeetingPlanScheduler.inputs(pending.getValue("c"), completed))
        assertEquals(mapOf("b" to "B"), MeetingPlanScheduler.inputs(pending.getValue("d"), completed))
    }

    @Test fun hostFailureCannotProduceSuccess() {
        assertEquals(MeetingTurnOutcome.PARTIAL, MeetingPlanScheduler.outcome(listOf(PlanStepResult("done")), false))
        assertEquals(MeetingTurnOutcome.PARTIAL, MeetingPlanScheduler.outcome(listOf(PlanStepResult("done"), PlanStepResult("", "failed")), true))
        assertEquals(MeetingTurnOutcome.SUCCEEDED, MeetingPlanScheduler.outcome(listOf(PlanStepResult("done")), true))
    }
}
