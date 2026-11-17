package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentContextLinkTest {
    private val summary = AgentModelClient.ConversationMessage(
        role = "assistant",
        content = "已完成前序操作。",
        contextSummary = true,
        compactedUserTurns = 2,
        summaryThroughUserTurn = 2,
    )

    private fun linkedSnapshot(
        conversationId: String = "conv-a",
        mode: String = "GOAL",
        goalId: String? = "goal-1",
        goalRevision: Long = 0,
        planRevision: Long = 0,
    ): AgentContextSnapshot {
        val snapshot = AgentContextSnapshot(
            operationId = "run-a",
            messages = listOf(summary),
            coveredUserTurns = 2,
            consumedUserTurns = 2,
        )
        return snapshot.copy(
            link = AgentContextLink.create(
                runId = "run-a",
                ledgerRunId = "run-a",
                conversationId = conversationId,
                scopeId = conversationId,
                compactedThrough = snapshot.coveredUserTurns,
                messages = snapshot.messages,
                checkpointRef = AgentContextLink.runtimeCheckpointRef("run-a"),
                mode = mode,
                goalId = goalId,
                goalRevision = goalRevision,
                planRevision = planRevision,
            ),
        )
    }

    private fun restore(
        snapshot: AgentContextSnapshot?,
        runId: String = "run-a",
        conversationId: String = "conv-a",
        mode: String? = "GOAL",
        goalId: String? = "goal-1",
        goalRevision: Long = 0,
        planRevision: Long = 0,
    ) = AgentContextRestore.decide(
        snapshot = snapshot,
        runId = runId,
        ledgerRunId = runId,
        conversationId = conversationId,
        scopeId = conversationId,
        mode = mode,
        goalId = goalId,
        goalRevision = goalRevision,
        planRevision = planRevision,
    )

    @Test fun linkSurvivesPersistedJsonAndRejectsAnotherSessionOrTask() {
        val decoded = checkNotNull(AgentContextSnapshot.decode(linkedSnapshot().encode()))
        assertNotNull(decoded.link)

        assertNotNull(restore(decoded).snapshot)

        val otherConversation = restore(decoded, conversationId = "conv-b")
        assertNull(otherConversation.snapshot)
        assertEquals("CONTEXT_LINK_SCOPE_MISMATCH", otherConversation.failure)

        val newTask = restore(decoded, goalId = "goal-2")
        assertNull(newTask.snapshot)
        assertEquals("CONTEXT_LINK_TASK_MISMATCH", newTask.failure)

        val otherRun = restore(decoded, runId = "run-b")
        assertNull(otherRun.snapshot)
        assertEquals("CONTEXT_LINK_RUN_MISMATCH", otherRun.failure)
    }

    @Test fun checkpointFromBeforeAGoalOrPlanRevisionIsRejected() {
        val decoded = checkNotNull(AgentContextSnapshot.decode(
            linkedSnapshot(goalRevision = 1, planRevision = 2).encode(),
        ))
        assertNotNull(restore(decoded, goalRevision = 1, planRevision = 2).snapshot)
        val goalChanged = restore(decoded, goalRevision = 2, planRevision = 2)
        assertNull(goalChanged.snapshot)
        assertEquals("CONTEXT_LINK_TASK_MISMATCH", goalChanged.failure)
        val planChanged = restore(decoded, goalRevision = 1, planRevision = 3)
        assertNull(planChanged.snapshot)
        assertEquals("CONTEXT_LINK_TASK_MISMATCH", planChanged.failure)
    }
}
