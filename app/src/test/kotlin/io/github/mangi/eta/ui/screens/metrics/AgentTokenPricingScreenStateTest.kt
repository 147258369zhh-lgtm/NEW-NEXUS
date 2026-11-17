package io.github.mangi.eta.ui.screens.metrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTokenPricingScreenStateTest {
    @Test fun emptyStateIsSuppressedWhileLoadingOrAfterLoadFailure() {
        assertFalse(shouldShowTokenPricingEmptyState(modelsEmpty = true, loading = true, failed = false))
        assertFalse(shouldShowTokenPricingEmptyState(modelsEmpty = true, loading = false, failed = true))
        assertFalse(shouldShowTokenPricingEmptyState(modelsEmpty = false, loading = false, failed = false))
        assertTrue(shouldShowTokenPricingEmptyState(modelsEmpty = true, loading = false, failed = false))
    }
}
