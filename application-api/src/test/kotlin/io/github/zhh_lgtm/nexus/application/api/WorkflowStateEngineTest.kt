package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.GoalSpec
import io.github.zhh_lgtm.nexus.kernel.api.GoalState
import io.github.zhh_lgtm.nexus.kernel.api.SessionId
import io.github.zhh_lgtm.nexus.kernel.api.GoalId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowStateEngineTest {
    @Test
    fun goalRequiresVerificationBeforeCompletion() {
        val goal = GoalSpec(
            goalId = GoalId("goal-1"),
            sessionId = SessionId("session-1"),
            objective = "Ship the feature",
            state = GoalState.WAITING_PLAN_APPROVAL,
        )
        val running = WorkflowStateEngine.transition(goal, GoalTransition.StartRunning)
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowStateEngine.transition(running, GoalTransition.Complete)
        }
        val verifying = WorkflowStateEngine.transition(running, GoalTransition.StartVerification)
        assertEquals(GoalState.COMPLETED, WorkflowStateEngine.transition(verifying, GoalTransition.Complete).state)
    }

    @Test
    fun notepadRevisionIsMonotonic() {
        val first = WorkflowStateEngine.appendNotepadRevision(null, "a", WorkflowActor.USER, 1)
        val second = WorkflowStateEngine.appendNotepadRevision(first, "b", WorkflowActor.AGENT, 2)
        assertEquals(1, first.revision)
        assertEquals(2, second.revision)
        assertEquals("agent", second.author)
    }
}
