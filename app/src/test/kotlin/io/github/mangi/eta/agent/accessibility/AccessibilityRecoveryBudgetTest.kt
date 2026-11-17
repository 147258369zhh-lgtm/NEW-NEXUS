package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class AccessibilityRecoveryBudgetTest {
    private class Store(
        var value: AccessibilityRecoveryBudgetRecord = AccessibilityRecoveryBudgetRecord(),
        var savesSucceed: Boolean = true,
    ) : AccessibilityRecoveryBudgetStore {
        override fun load() = value
        override fun save(record: AccessibilityRecoveryBudgetRecord): Boolean {
            if (!savesSucceed) return false
            value = record
            return true
        }
    }

    @Test
    fun attemptsUseBackoffAndStopAfterThreeUntilExplicitReset() {
        val budget = AccessibilityRecoveryBudget()
        val base = 1_700_000_000_000L
        assertEquals(0L, budget.acquire(base))
        assertEquals(5_000L, budget.acquire(base))
        assertEquals(0L, budget.acquire(base + 5_000))
        assertEquals(20_000L, budget.acquire(base + 5_000))
        assertEquals(0L, budget.acquire(base + 25_000))
        assertNull(budget.acquire(base + 10_000_000))
        assertTrue(budget.reset())
        assertEquals(0L, budget.acquire(base + 10_000_001))
    }

    @Test
    fun rapidCrashRebindCyclesDoNotResetTheAttemptLimit() {
        val budget = AccessibilityRecoveryBudget()
        val base = 1_700_000_000_000L
        assertEquals(0L, budget.acquire(base))
        budget.recordConnection(1_000, 2_000, 5_000, false)
        assertEquals(0L, budget.acquire(base + 5_000))
        budget.recordConnection(6_000, 7_000, 25_000, false)
        assertEquals(0L, budget.acquire(base + 25_000))
        budget.recordConnection(26_000, 27_000, 200_000, false)
        assertNull(budget.acquire(base + 200_000))
    }

    @Test
    fun stableConnectionResetsOnceAndOldHistoryCannotResetEveryFailure() {
        val budget = AccessibilityRecoveryBudget()
        val base = 1_700_000_000_000L
        budget.acquire(base)
        budget.acquire(base + 5_000)
        budget.acquire(base + 25_000)
        budget.recordConnection(30_000, 100_000, 100_001, false)
        assertEquals(0L, budget.acquire(base + 100_001))
        budget.recordConnection(30_000, 100_000, 105_001, false)
        assertEquals(0L, budget.acquire(base + 105_001))
        budget.recordConnection(30_000, 100_000, 125_001, false)
        assertEquals(0L, budget.acquire(base + 125_001))
        budget.recordConnection(30_000, 100_000, 999_999, false)
        assertNull(budget.acquire(base + 999_999))
    }

    @Test
    fun processRestartPreservesTheAttemptCap() {
        val store = Store()
        val beforeRestart = AccessibilityRecoveryBudget(store)
        assertEquals(0L, beforeRestart.acquire(1_000, bootCount = 7))
        assertEquals(0L, beforeRestart.acquire(7_000, bootCount = 7))
        assertEquals(0L, beforeRestart.acquire(30_000, bootCount = 7))
        assertNull(beforeRestart.acquire(60_000, bootCount = 7))

        val afterRestart = AccessibilityRecoveryBudget(store)
        assertNull(afterRestart.acquire(60_000, bootCount = 7))
        assertEquals(3, store.value.attempts)
    }

    @Test
    fun rebootChargesTheNextBackoffWithoutResettingTheCap() {
        val store = Store()
        val budget = AccessibilityRecoveryBudget(store)
        val base = 1_700_000_000_000L
        assertEquals(0L, budget.acquire(base, bootCount = 3))

        assertEquals(5_000L, AccessibilityRecoveryBudget(store).acquire(1_000, bootCount = 4))
        assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(6_001, bootCount = 4))
        assertEquals(20_000L, AccessibilityRecoveryBudget(store).acquire(6_001, bootCount = 4))
        assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(26_001, bootCount = 4))
        assertNull(AccessibilityRecoveryBudget(store).acquire(100_000, bootCount = 4))
    }

    @Test
    fun backwardsOrUnknownClockCannotBypassTheCooldown() {
        val store = Store()
        assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(50_000, bootCount = 7))
        assertEquals(5_000L, AccessibilityRecoveryBudget(store).acquire(40_000, bootCount = 7))
        assertEquals(5_000L, AccessibilityRecoveryBudget(store).acquire(0, bootCount = 7))
        assertEquals(5_000L, AccessibilityRecoveryBudget(store).acquire(50_001, bootCount = null))
    }

    @Test
    fun corruptRecordIsSanitizedInsteadOfOpeningADefaultBudget() {
        val store = Store(AccessibilityRecoveryBudgetRecord(attempts = 99, lastAttemptAtEpochMs = -1, lastAttemptBootCount = 7))
        assertNull(AccessibilityRecoveryBudget(store).acquire(1_000, bootCount = 7))

        store.value = AccessibilityRecoveryBudgetRecord(attempts = 2, lastAttemptAtEpochMs = 0, lastAttemptBootCount = 7)
        assertNull(AccessibilityRecoveryBudget(store).acquire(1_000, bootCount = 7))

        store.value = AccessibilityRecoveryBudgetRecord(attempts = Long.MAX_VALUE.toInt(), lastAttemptAtEpochMs = Long.MAX_VALUE, lastAttemptBootCount = -4)
        assertNull(AccessibilityRecoveryBudget(store).acquire(1_000, bootCount = 7))
    }

    @Test
    fun persistenceFailureFailsClosedAndDoesNotReopenTheBudget() {
        val store = Store(savesSucceed = false)
        assertNull(AccessibilityRecoveryBudget(store).acquire(1_000, bootCount = 7))
        assertEquals(0, store.value.attempts)
    }

    @Test
    fun stableConnectionAcrossBootsDoesNotReuseAnElapsedTimestamp() {
        val store = Store()
        val budget = AccessibilityRecoveryBudget(store)
        budget.acquire(1_000, bootCount = 7)
        // The caller filters the whole elapsed pair when its recorded boot differs.
        budget.recordConnection(null, null, 200_001, connected = false, bootCount = 8)
        assertEquals(5_000L, AccessibilityRecoveryBudget(store).acquire(200_001, bootCount = 8))
    }

    @Test
    fun persistenceFailureDuringStableResetKeepsTheAttemptCap() {
        val store = Store()
        val budget = AccessibilityRecoveryBudget(store)
        assertEquals(0L, budget.acquire(1_000, bootCount = 7))
        assertEquals(0L, budget.acquire(7_000, bootCount = 7))
        store.savesSucceed = false
        budget.recordConnection(10_000, 200_000, 200_001, connected = false, bootCount = 7)
        assertEquals(2, store.value.attempts)
        assertNull(AccessibilityRecoveryBudget(store).acquire(200_001, bootCount = 7))
    }

    @Test
    fun explicitResetRequiresASuccessfulPersistentWrite() {
        val store = Store()
        val budget = AccessibilityRecoveryBudget(store)
        assertEquals(0L, budget.acquire(1_000, bootCount = 7))
        store.savesSucceed = false
        assertFalse(budget.reset())
        store.savesSucceed = true
        assertTrue(budget.reset())
        assertEquals(AccessibilityRecoveryBudgetRecord(), store.value)
    }

    @Test
    fun sharedPreferencesStoreSurvivesANewBudgetInstanceAndCorruptTypesFailClosed() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eta_root_accessibility_recovery", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val store = PrefsAccessibilityRecoveryBudgetStore(prefs)
            assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(100_000, bootCount = 11))
            assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(105_000, bootCount = 11))
            assertEquals(0L, AccessibilityRecoveryBudget(store).acquire(125_000, bootCount = 11))
            assertNull(AccessibilityRecoveryBudget(PrefsAccessibilityRecoveryBudgetStore(prefs)).acquire(125_001, bootCount = 11))

            prefs.edit().putString(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPTS, "corrupt").commit()
            assertNull(AccessibilityRecoveryBudget(PrefsAccessibilityRecoveryBudgetStore(prefs)).acquire(1_000, bootCount = 11))

            prefs.edit().clear()
                .putInt(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPTS, -1)
                .putLong(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPT_AT_EPOCH_MS, 100_000)
                .putInt(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPT_BOOT_COUNT, 11)
                .commit()
            assertNull(AccessibilityRecoveryBudget(PrefsAccessibilityRecoveryBudgetStore(prefs)).acquire(125_001, bootCount = 11))

            prefs.edit().clear()
                .putLong(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPTS, Long.MAX_VALUE)
                .putLong(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPT_AT_EPOCH_MS, 100_000)
                .putInt(MapAccessibilityRecoveryBudgetStore.KEY_ATTEMPT_BOOT_COUNT, 11)
                .commit()
            assertNull(AccessibilityRecoveryBudget(PrefsAccessibilityRecoveryBudgetStore(prefs)).acquire(125_001, bootCount = 11))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
