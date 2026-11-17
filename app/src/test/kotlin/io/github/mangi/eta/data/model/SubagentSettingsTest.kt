package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentSettingsTest {
    @Test
    fun defaultsAndOutOfRangeValuesUseConservativeConcurrency() {
        assertEquals(3, SubagentSettings().resolvedMaxParallelAgents)
        assertEquals(3, SubagentSettings(maxParallelAgents = 0).resolvedMaxParallelAgents)
        assertEquals(3, SubagentSettings(maxParallelAgents = -1).resolvedMaxParallelAgents)
        assertEquals(3, SubagentSettings(maxParallelAgents = 99).resolvedMaxParallelAgents)
    }

    @Test
    fun explicitSupportedConcurrencyIsPreserved() {
        assertEquals(1, SubagentSettings(maxParallelAgents = 1).resolvedMaxParallelAgents)
        assertEquals(8, SubagentSettings(maxParallelAgents = 8).resolvedMaxParallelAgents)
    }
}
