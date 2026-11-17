package io.github.mangi.eta.agent.model

import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*

class AgentRunBudgetGuardTest {
    @Test fun hostedToolAccountingDefersSoftConfirmationUntilTheNextSafeBoundary() {
        val confirmations = AtomicInteger()
        val guard = AgentRunBudgetGuard(RunBudget(softToolCalls = 1, hardToolCalls = 3), approveSoftLimit = {
            confirmations.incrementAndGet(); true
        })
        guard.beforeHostedToolCall(); guard.beforeHostedToolCall()
        assertEquals(2, guard.toolCallsUsed())
        assertEquals(0, confirmations.get())
        guard.checkRound(2)
        assertEquals(1, confirmations.get())
    }

    @Test fun softToolApprovalHappensOnceBeforeReservingAndNeverRaisesTheHardLimit() {
        val approvals = AtomicInteger()
        lateinit var guard: AgentRunBudgetGuard
        guard = AgentRunBudgetGuard(RunBudget(softToolCalls = 1, hardToolCalls = 3), approveSoftLimit = {
            assertEquals(1, guard.toolCallsUsed())
            assertEquals(RunBudgetDimension.TOOL_CALLS, it.dimension)
            approvals.incrementAndGet(); true
        })
        guard.beforeToolCall(); guard.beforeToolCall(); guard.beforeToolCall()
        assertEquals(1, approvals.get())
        assertEquals(3, guard.toolCallsUsed())
        assertThrows(AgentModelFailure::class.java) { guard.beforeToolCall() }
        assertEquals(1, approvals.get())
    }

    @Test fun rejectingASoftBudgetDoesNotConsumeTheNextToolOrRound() {
        val guard = AgentRunBudgetGuard(RunBudget(softRounds = 1, hardRounds = 3, softToolCalls = 1, hardToolCalls = 3))
        guard.checkRound(1); guard.beforeToolCall()
        val tool = assertThrows(AgentModelFailure::class.java) { guard.beforeToolCall() }
        assertEquals("RUN_SOFT_BUDGET_NOT_APPROVED_TOOL_CALLS", tool.code)
        assertEquals(1, guard.toolCallsUsed())
        assertThrows(AgentModelFailure::class.java) { guard.checkRound(2) }
        assertEquals(1L, guard.roundsUsed())
    }

    @Test fun softDurationIsCheckedAtTheNextExecutionBoundary() {
        var time = 0L
        val approvals = AtomicInteger()
        val guard = AgentRunBudgetGuard(RunBudget(softDurationMs = 10, hardDurationMs = 50), clockNanos = { time },
            approveSoftLimit = { assertEquals(RunBudgetDimension.DURATION, it.dimension); approvals.incrementAndGet(); true })
        time = 12_000_000
        guard.checkRound(1); guard.beforeToolCall()
        assertEquals(1, approvals.get())
        time = 50_000_000
        assertThrows(AgentModelFailure::class.java) { guard.checkRound(2) }
    }

    @Test fun restrictingBudgetRetainsConsumedCountersAndCannotWidenLimits() {
        val guard = AgentRunBudgetGuard(RunBudget(hardRounds = 9, hardToolCalls = 10))
        guard.checkRound(3); guard.reserveToolCalls(3)
        guard.restrictBudget(RunBudget(hardRounds = 3, hardToolCalls = 3))
        guard.restrictBudget(RunBudget(hardRounds = 100, hardToolCalls = 100))
        assertEquals(3, guard.effectiveBudget().hardToolCalls)
        assertEquals(3, guard.toolCallsUsed())
        assertEquals(3L, guard.roundsUsed())
        assertThrows(AgentModelFailure::class.java) { guard.beforeToolCall() }
        assertThrows(AgentModelFailure::class.java) { guard.checkRound(4) }
    }

    @Test fun shorteningTheDeadlineRearmsTheLiveWatchdog() {
        val controller = AgentRunController()
        val cancelled = CountDownLatch(1)
        val binding = controller.register { cancelled.countDown() }
        val guard = AgentRunBudgetGuard(RunBudget(hardDurationMs = 60_000))
        try { guard.watch(controller).use {
            guard.restrictBudget(RunBudget(hardDurationMs = 20))
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertFalse(controller.isUserCancelled)
            assertEquals("RUN_BUDGET_EXCEEDED_DURATION", (controller.abortFailure as AgentModelFailure).code)
        } } finally { binding.close() }
    }

    @Test fun concurrentToolReservationsShareOneSoftConfirmationAndRespectTheAggregateLimit() {
        val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val approvals = AtomicInteger(); val successes = AtomicInteger(); val failures = AtomicInteger()
        val guard = AgentRunBudgetGuard(RunBudget(softToolCalls = 1, hardToolCalls = 4), approveSoftLimit = {
            approvals.incrementAndGet(); requested.countDown(); release.await(2, TimeUnit.SECONDS)
        })
        guard.beforeToolCall()
        val workers = (1..5).map { thread {
            try { guard.beforeToolCall(); successes.incrementAndGet() }
            catch (_: AgentModelFailure) { failures.incrementAndGet() }
        } }
        assertTrue(requested.await(1, TimeUnit.SECONDS))
        assertEquals(1, guard.toolCallsUsed())
        release.countDown()
        workers.forEach { it.join(3_000); assertFalse(it.isAlive) }
        assertEquals(1, approvals.get())
        assertEquals(3, successes.get())
        assertEquals(2, failures.get())
        assertEquals(4, guard.toolCallsUsed())
    }

    @Test fun hardRoundAndToolLimitsTerminateTheRun() {
        val guard = AgentRunBudgetGuard(RunBudget(hardRounds = 2, hardToolCalls = 1))
        guard.checkRound(1)
        guard.beforeToolCall()
        assertEquals(1, guard.toolCallsUsed())

        val toolFailure = assertThrows(AgentModelFailure::class.java) { guard.beforeToolCall() }
        assertEquals("RUN_BUDGET_EXCEEDED_TOOL_CALLS", toolFailure.code)

        val roundGuard = AgentRunBudgetGuard(RunBudget(hardRounds = 2))
        roundGuard.checkRound(2)
        val roundFailure = assertThrows(AgentModelFailure::class.java) { roundGuard.checkRound(3) }
        assertEquals("RUN_BUDGET_EXCEEDED_ROUNDS", roundFailure.code)
    }

    @Test fun hardDurationUsesInjectedClock() {
        var now = 0L
        val guard = AgentRunBudgetGuard(
            budget = RunBudget(hardDurationMs = 1_000L),
            clockNanos = { now },
        )
        now = 900_000_000L
        guard.checkDuration()
        now = 1_100_000_000L
        val failure = assertThrows(AgentModelFailure::class.java) { guard.checkDuration() }
        assertEquals("RUN_BUDGET_EXCEEDED_DURATION", failure.code)
    }

    @Test fun rejectedParallelBatchReservesNothingAndBoundaryIsInclusive() {
        val guard = AgentRunBudgetGuard(RunBudget(hardToolCalls = 2))
        assertThrows(AgentModelFailure::class.java) { guard.reserveToolCalls(3) }
        assertEquals(0, guard.toolCallsUsed())
        guard.reserveToolCalls(2)
        assertEquals(2, guard.toolCallsUsed())
        assertThrows(AgentModelFailure::class.java) { guard.beforeToolCall() }
        assertEquals(2, guard.toolCallsUsed())
    }

    @Test fun exactDeadlineIsExpired() {
        var now = 0L
        val guard = AgentRunBudgetGuard(RunBudget(hardDurationMs = 1), clockNanos = { now })
        now = 1_000_000L
        assertThrows(AgentModelFailure::class.java) { guard.checkDuration() }
    }

    @Test fun closedDeadlineCannotCancelAFollowingTurn() {
        val controller = AgentRunController()
        val cancelled = CountDownLatch(1)
        val resource = controller.register { cancelled.countDown() }
        try {
            AgentRunBudgetGuard(RunBudget(hardDurationMs = 100)).watch(controller).close()
            org.junit.Assert.assertFalse(cancelled.await(250, TimeUnit.MILLISECONDS))
            org.junit.Assert.assertFalse(controller.isCancelled)
        } finally { resource.close() }
    }
}
