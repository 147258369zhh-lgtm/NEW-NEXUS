package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRebindTransactionTest {
    private val target = "eta.app/eta.app.AccessibilityService"

    @Test
    fun rebindPreservesOtherServicesAndMergesNewEntriesFromTheDisabledInterval() {
        val settings = FakeSettings("other.app/.Reader:eta.app/.AccessibilityService")
        var journaled = false
        var completed = false
        val outcome = transaction(settings,
            begin = { journaled = true; true },
            complete = { completed = true },
            pause = {
                assertTrue(journaled)
                assertEquals("other.app/.Reader", settings.services)
                settings.services += ":new.app/.Helper"
            },
        ).execute()
        assertEquals(AccessibilityRebindOutcome.REQUESTED, outcome)
        assertEquals("other.app/.Reader:new.app/.Helper:$target", settings.services)
        assertTrue(completed)
        assertEquals(listOf("other.app/.Reader", "other.app/.Reader:new.app/.Helper:$target"), settings.writes)
    }

    @Test
    fun neverEnablesADisabledOrUnlistedService() {
        for (settings in listOf(FakeSettings("other/.Service"), FakeSettings(target, enabled = false))) {
            assertEquals(AccessibilityRebindOutcome.NOT_CONFIGURED, transaction(settings).execute())
            assertTrue(settings.writes.isEmpty())
        }
    }

    @Test
    fun cancelledAuthorizationDoesNotTouchSettings() {
        val settings = FakeSettings(target)
        assertEquals(AccessibilityRebindOutcome.CANCELLED, transaction(settings, allowed = { false }).execute())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun failedJournalCommitPreventsMutation() {
        val settings = FakeSettings("other/.Reader:$target")
        assertEquals(AccessibilityRebindOutcome.CANCELLED, transaction(settings, begin = { false }).execute())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun serviceTurnedOffBetweenChecksIsNotReenabled() {
        val settings = FakeSettings("other/.Reader:$target")
        val outcome = transaction(settings, begin = { settings.services = "other/.Reader"; true }).execute()
        assertEquals(AccessibilityRebindOutcome.NOT_CONFIGURED, outcome)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun rereadsBeforeRemovingSoConcurrentAdditionsAreNotOverwrittenWithAnOldList() {
        val settings = FakeSettings("first/.Reader:$target")
        transaction(settings, begin = { settings.services += ":other/.Reader"; true }).execute()
        assertEquals("first/.Reader:other/.Reader", settings.writes.first())
        assertEquals("first/.Reader:other/.Reader:$target", settings.services)
    }

    @Test
    fun interruptedWaitRestoresTheTargetAndPreservesConcurrentChanges() {
        val settings = FakeSettings("other/.Reader:$target")
        var complete = false
        val outcome = transaction(settings, complete = { complete = true }, pause = {
            settings.services += ":new/.Reader"
            throw InterruptedException()
        }).execute()
        assertEquals(AccessibilityRebindOutcome.FAILED, outcome)
        assertEquals("other/.Reader:new/.Reader:$target", settings.services)
        assertTrue(complete)
    }

    @Test
    fun writeFailureAfterMutationStillAttemptsToRestoreInsteadOfTrustingTheException() {
        val settings = FakeSettings("other/.Reader:$target").apply { failWriteOnce = true }
        var complete = false
        assertEquals(AccessibilityRebindOutcome.FAILED, transaction(settings, complete = { complete = true }).execute())
        assertEquals("other/.Reader:$target", settings.services)
        assertTrue(complete)
    }

    @Test
    fun closingRecoveryDuringTheGapRestoresTheOriginalServiceButStartsNoNewOperation() {
        val settings = FakeSettings("other/.Reader:$target")
        var enabled = true
        var complete = false
        val outcome = transaction(settings, allowed = { enabled }, restoreAllowed = { true },
            complete = { complete = true }, pause = { enabled = false },
        ).execute()
        assertEquals(AccessibilityRebindOutcome.REQUESTED, outcome)
        assertEquals("other/.Reader:$target", settings.services)
        assertTrue(complete)
    }

    @Test
    fun rootRevocationStopsFurtherWritesAndLeavesTheJournalForManualInspection() {
        val settings = FakeSettings("other/.Reader:$target")
        var granted = true
        var complete = false
        transaction(settings, allowed = { granted }, complete = { complete = true }, pause = { granted = false }).execute()
        assertEquals(listOf("other/.Reader"), settings.writes)
        assertFalse(complete)
    }

    @Test
    fun masterSwitchOffDuringTheGapIsRespectedAndNoMasterWriteExists() {
        val settings = FakeSettings("other/.Reader:$target")
        var complete = false
        transaction(settings, complete = { complete = true }, pause = { settings.enabled = false }).execute()
        assertFalse(settings.enabled)
        assertEquals(listOf("other/.Reader"), settings.writes)
        assertFalse(complete)
    }

    @Test
    fun failedRestorationRemainsUnconfirmedAndDoesNotClearTheJournal() {
        val settings = FakeSettings("other/.Reader:$target")
        var complete = false
        val outcome = transaction(settings, complete = { complete = true }, pause = { settings.blockWrites = true }).execute()
        assertEquals(AccessibilityRebindOutcome.UNCONFIRMED, outcome)
        assertFalse(complete)
    }

    @Test
    fun readFailureBeforeMutationDoesNotWriteOrClaimSuccess() {
        val settings = FakeSettings(target).apply { blockReads = true }
        assertEquals(AccessibilityRebindOutcome.FAILED, transaction(settings).execute())
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun systemReenableDuringTheGapDoesNotDuplicateTheTarget() {
        val settings = FakeSettings("other/.Reader:$target")
        val outcome = transaction(settings, pause = { settings.services = "new/.Reader:eta.app/.AccessibilityService" }).execute()
        assertEquals(AccessibilityRebindOutcome.REQUESTED, outcome)
        assertEquals("new/.Reader:eta.app/.AccessibilityService", settings.services)
        assertEquals(1, settings.writes.size)
    }

    @Test
    fun lastEnabledServiceIsNeverTemporarilyRemoved() {
        for (services in listOf(target, "eta.app/.AccessibilityService:$target")) {
            val settings = FakeSettings(services)
            var journaled = false
            assertEquals(AccessibilityRebindOutcome.MANUAL_REQUIRED,
                transaction(settings, begin = { journaled = true; true }).execute())
            assertFalse(journaled)
            assertTrue(settings.writes.isEmpty())
        }
    }

    @Test
    fun lastOtherServiceRemovedDuringPreflightAlsoPreventsMutation() {
        val settings = FakeSettings("other/.Reader:$target")
        var completed = false
        val result = transaction(settings, begin = { settings.services = target; true },
            complete = { completed = true }).execute()
        assertEquals(AccessibilityRebindOutcome.MANUAL_REQUIRED, result)
        assertTrue(completed)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun failedJournalCleanupDoesNotClaimACompletedRebind() {
        val settings = FakeSettings("other/.Reader:$target")
        val result = AccessibilityRebindTransaction(settings, target, { true }, { true }, { false }, {}).execute()
        assertEquals(AccessibilityRebindOutcome.UNCONFIRMED, result)
        assertEquals("other/.Reader:$target", settings.services)
    }

    private fun transaction(
        settings: FakeSettings,
        allowed: () -> Boolean = { true },
        begin: () -> Boolean = { true },
        complete: () -> Unit = {},
        pause: () -> Unit = {},
        restoreAllowed: () -> Boolean = allowed,
    ) = AccessibilityRebindTransaction(settings, target, allowed, begin, { complete(); true }, pause, restoreAllowed)

    private class FakeSettings(var services: String, var enabled: Boolean = true) : AccessibilitySettingsAccess {
        val writes = mutableListOf<String>()
        var failWriteOnce = false
        var blockWrites = false
        var blockReads = false
        override fun read(): AccessibilitySettingsSnapshot {
            check(!blockReads)
            return AccessibilitySettingsSnapshot(enabled, services)
        }
        override fun writeServices(value: String) {
            check(!blockWrites)
            services = value
            writes += value
            if (failWriteOnce) { failWriteOnce = false; error("write response lost") }
        }
    }
}
