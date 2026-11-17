package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingWorkflowInputsTest {
    @Test fun everyNonSkippedInputMustHaveACompletedResult() {
        val inputs = listOf("plan", "build", "verify")
        assertFalse(meetingWorkflowInputsReady(inputs, emptySet(), mapOf("plan" to listOf(true), "build" to listOf(true))))
        assertFalse(meetingWorkflowInputsReady(inputs, emptySet(), mapOf("plan" to listOf(true), "build" to listOf(false), "verify" to listOf(true))))
        assertTrue(meetingWorkflowInputsReady(inputs, emptySet(), inputs.associateWith { listOf(true) }))
    }

    @Test fun intentionallySkippedReworkDoesNotBlockDelivery() {
        val inputs = listOf("plan", "build", "verify", "repair", "reverify")
        val completed = mapOf("plan" to listOf(true), "build" to listOf(true), "verify" to listOf(true))
        assertTrue(meetingWorkflowInputsReady(inputs, setOf("repair", "reverify"), completed))
        assertFalse(meetingWorkflowInputsReady(inputs, setOf("reverify"), completed))
    }

    @Test fun partialStageDeliveryCanContinueButFullyFailedStageCannot() {
        assertTrue(meetingWorkflowInputsReady(listOf("ideas"), emptySet(), mapOf("ideas" to listOf(false, true))))
        assertFalse(meetingWorkflowInputsReady(listOf("ideas"), emptySet(), mapOf("ideas" to listOf(false, false))))
        assertFalse(meetingWorkflowInputsReady(listOf("ideas"), emptySet(), mapOf("ideas" to emptyList())))
    }

    @Test fun finalHostCanReportMissingInputsButRoundRemainsIncomplete() {
        val inputs = listOf("plan", "build", "verify")
        val results = mapOf("plan" to listOf(true), "build" to listOf(false))
        val work = meetingWorkflowInputState(false, inputs, emptySet(), results)
        assertFalse(work.canRun)
        assertTrue(work.incomplete)
        val report = meetingWorkflowInputState(true, inputs, emptySet(), results)
        assertTrue(report.canRun)
        assertTrue(report.incomplete)
        val complete = meetingWorkflowInputState(true, inputs, emptySet(), inputs.associateWith { listOf(true) })
        assertTrue(complete.canRun)
        assertFalse(complete.incomplete)
    }

}
