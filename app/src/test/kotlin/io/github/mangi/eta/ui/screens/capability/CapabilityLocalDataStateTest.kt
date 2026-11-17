package io.github.mangi.eta.ui.screens.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityLocalDataStateTest {
    @Test
    fun emptyLocalCollectionsAreAuthoritativeOnlyAfterSnapshotLoads() {
        assertFalse(hasCapabilitySnapshot(CapabilityLocalDataState.Loading))
        assertFalse(hasCapabilitySnapshot(CapabilityLocalDataState.Failed))
        assertTrue(hasCapabilitySnapshot(CapabilityLocalDataState.Loaded))
    }
}
