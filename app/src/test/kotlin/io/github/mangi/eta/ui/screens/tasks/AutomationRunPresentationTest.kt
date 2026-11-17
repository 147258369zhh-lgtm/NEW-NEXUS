package io.github.mangi.eta.ui.screens.tasks

import io.github.mangi.eta.fork.tasks.AgentTaskRunEntity
import io.github.mangi.eta.fork.tasks.AgentTaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRunPresentationTest {
    private fun run(state: AgentTaskState, createdAt: Long, scheduledFor: Long = createdAt) = AgentTaskRunEntity(
        id = "run", definitionId = "task", scheduleId = null, scheduledFor = scheduledFor, attempt = 1,
        idempotencyKey = "run", state = state, createdAt = createdAt, updatedAt = createdAt,
    )

    @Test fun queuedRunUsesItsScheduledTimeInsteadOfCreationTime() {
        val created = 1_000L
        val due = 3_600_000L
        val summary = automationRunTimeSummary(run(AgentTaskState.QUEUED, created, due), now = 2_000L)
        assertTrue(summary.startsWith("计划 "))
        assertFalse(summary.contains("等待执行"))
    }

    @Test fun overdueRunExplainsThatItIsWaitingForExecution() {
        val summary = automationRunTimeSummary(run(AgentTaskState.QUEUED, 1_000L), now = 2_000L)
        assertTrue(summary.startsWith("等待执行 · "))
    }

    @Test fun runningRunKeepsItsStartTime() {
        val summary = automationRunTimeSummary(run(AgentTaskState.RUNNING, 1_000L), now = 2_000L)
        assertTrue(summary.endsWith("开始"))
    }
}
