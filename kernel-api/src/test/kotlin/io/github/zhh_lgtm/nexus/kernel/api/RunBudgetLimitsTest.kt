package io.github.zhh_lgtm.nexus.kernel.api

import org.junit.Assert.*
import org.junit.Test

class RunBudgetLimitsTest {
    @Test fun differentPricedCurrenciesCannotBeCombinedWithoutExplicitConversion() {
        assertThrows(IllegalArgumentException::class.java) {
            RunBudget(costLimitMicros = 100, costCurrency = "USD").intersect(RunBudget(costLimitMicros = 100, costCurrency = "CNY"))
        }
        assertEquals("CNY", RunBudget(hardRounds = 3).intersect(RunBudget(costLimitMicros = 10, costCurrency = "CNY")).costCurrency)
    }

    @Test fun intersectionTightensEveryLimitAndCapsSoftThresholdsToHardLimits() {
        val parent = RunBudget(softRounds = 9, hardRounds = 12, softDurationMs = 90,
            hardDurationMs = 100, softToolCalls = 8, hardToolCalls = 10, costLimitMicros = 50)
        val child = RunBudget(hardRounds = 4, hardDurationMs = 20, hardToolCalls = 2, costLimitMicros = 30)
        val combined = parent.intersect(child)
        assertEquals(RunBudget(4, 4, 20, 20, 2, 2, 30), combined)
        assertEquals(combined, child.intersect(parent))
        assertEquals(combined, combined.intersect(parent))
    }

    @Test fun absentSoftThresholdDoesNotCreateAnArtificialPause() {
        val explicit = RunBudget(hardRounds = 3, hardToolCalls = 1)
        assertEquals(explicit, explicit.intersect(RunBudget()))
        assertNull(explicit.intersect(RunBudget(hardRounds = 2)).softRounds)
        assertEquals(RunBudget(), RunBudget().intersect(RunBudget()))
    }

    @Test fun approvedGoalIncreasePreservesIndependentNarrowerCapsAndCannotRemoveLimits() {
        val old = RunBudget(softRounds = 2, hardRounds = 4, hardDurationMs = 1_000, hardToolCalls = 10, costLimitMicros = 100)
        val next = old.copy(hardRounds = 8, hardDurationMs = 2_000, hardToolCalls = 20, costLimitMicros = 200)
        assertEquals(next, old.withIncreasedGoalBudget(old, next))
        val child = old.copy(hardRounds = 3, hardToolCalls = 2, hardDurationMs = 900)
        assertEquals(child.copy(costLimitMicros = 200), child.withIncreasedGoalBudget(old, next))
        assertThrows(IllegalArgumentException::class.java) { old.withIncreasedGoalBudget(old, next.copy(hardRounds = null)) }
        assertThrows(IllegalArgumentException::class.java) { old.withIncreasedGoalBudget(old, next.copy(costLimitMicros = 90)) }
        assertThrows(IllegalArgumentException::class.java) { old.withIncreasedGoalBudget(old, next.copy(costCurrency = "CNY")) }
        assertThrows(IllegalArgumentException::class.java) { old.withIncreasedGoalBudget(old, next.copy(softRounds = null)) }
    }
}
