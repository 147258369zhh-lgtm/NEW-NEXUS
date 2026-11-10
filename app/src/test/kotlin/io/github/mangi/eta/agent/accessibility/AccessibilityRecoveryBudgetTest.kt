package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityRecoveryBudgetTest {
    @Test
    fun attemptsUseBackoffAndStopAfterThreeUntilExplicitReset() {
        val budget = AccessibilityRecoveryBudget()
        assertEquals(0L, budget.acquire(0))
        assertEquals(5_000L, budget.acquire(0))
        assertEquals(0L, budget.acquire(5_000))
        assertEquals(20_000L, budget.acquire(5_000))
        assertEquals(0L, budget.acquire(25_000))
        assertNull(budget.acquire(10_000_000))
        budget.reset()
        assertEquals(0L, budget.acquire(10_000_001))
    }

    @Test
    fun rapidCrashRebindCyclesDoNotResetTheAttemptLimit() {
        val budget = AccessibilityRecoveryBudget()
        assertEquals(0L, budget.acquire(0))
        budget.recordConnection(1_000, 2_000, 5_000, false)
        assertEquals(0L, budget.acquire(5_000))
        budget.recordConnection(6_000, 7_000, 25_000, false)
        assertEquals(0L, budget.acquire(25_000))
        budget.recordConnection(26_000, 27_000, 200_000, false)
        assertNull(budget.acquire(200_000))
    }

    @Test
    fun stableConnectionResetsOnceAndOldHistoryCannotResetEveryFailure() {
        val budget = AccessibilityRecoveryBudget()
        budget.acquire(0)
        budget.acquire(5_000)
        budget.acquire(25_000)
        budget.recordConnection(30_000, 100_000, 100_001, false)
        assertEquals(0L, budget.acquire(100_001))
        budget.recordConnection(30_000, 100_000, 105_001, false)
        assertEquals(0L, budget.acquire(105_001))
        budget.recordConnection(30_000, 100_000, 125_001, false)
        assertEquals(0L, budget.acquire(125_001))
        budget.recordConnection(30_000, 100_000, 999_999, false)
        assertNull(budget.acquire(999_999))
    }
}
