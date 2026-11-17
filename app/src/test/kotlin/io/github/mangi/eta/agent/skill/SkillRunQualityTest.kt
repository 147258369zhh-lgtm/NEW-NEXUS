package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class SkillRunQualityTest {
    @Test fun interruptionAndMissingTransportResultNeverBecomeSkillFailureEvidence() {
        val failedTool = AgentEvent.ToolFinished(1, "tool", "read_file", "failed", 0, 0, false)
        assertNull(SkillRunQuality.outcome(false, true, listOf(failedTool)))
        assertNull(SkillRunQuality.outcome(false, false, emptyList()))
        assertEquals(false, SkillRunQuality.outcome(false, false, listOf(failedTool)))
        assertEquals(true, SkillRunQuality.outcome(true, false, listOf(failedTool)))
    }
}
