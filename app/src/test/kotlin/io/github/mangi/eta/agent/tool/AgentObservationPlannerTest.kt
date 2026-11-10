package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentObservationPlannerTest {
    @Test
    fun prefersCacheUntilExpiredAndBreaksRepeatedNoopLoop() {
        var now = 100L
        val planner = AgentObservationPlanner(cacheTtlMs = 500, clockMs = { now })
        assertEquals(AgentObservationPlanner.Level.STRUCTURED, planner.decide(false).level)
        planner.record("a", changed = true)
        assertEquals(AgentObservationPlanner.Level.CACHED, planner.decide(false).level)
        now += 501
        assertEquals(AgentObservationPlanner.Level.STRUCTURED, planner.decide(false).level)
        planner.record("a", changed = false)
        planner.record("a", changed = false)
        assertTrue(planner.shouldBreakLoop())
        planner.clear()
        assertFalse(planner.shouldBreakLoop())
    }

    @Test
    fun freshAndScreenshotAlwaysWin() {
        val planner = AgentObservationPlanner()
        assertEquals(AgentObservationPlanner.Level.STRUCTURED, planner.decide(requiresFresh = true).level)
        assertEquals(AgentObservationPlanner.Level.SCREENSHOT, planner.decide(false, screenshotRequested = true).level)
    }
}
