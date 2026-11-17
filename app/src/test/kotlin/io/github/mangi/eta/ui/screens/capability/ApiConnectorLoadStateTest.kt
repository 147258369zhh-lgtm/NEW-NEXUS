package io.github.mangi.eta.ui.screens.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConnectorLoadStateTest {
    @Test
    fun emptyStateIsShownOnlyAfterSuccessfulLoad() {
        assertFalse(apiConnectorEmptyStateVisible(connectorsEmpty = true, loading = true, loadError = null))
        assertFalse(apiConnectorEmptyStateVisible(connectorsEmpty = true, loading = false, loadError = "disk unavailable"))
        assertFalse(apiConnectorEmptyStateVisible(connectorsEmpty = false, loading = false, loadError = null))
        assertTrue(apiConnectorEmptyStateVisible(connectorsEmpty = true, loading = false, loadError = null))
    }
}
