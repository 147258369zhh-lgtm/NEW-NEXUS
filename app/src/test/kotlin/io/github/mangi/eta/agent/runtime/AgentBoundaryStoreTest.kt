package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.config.Prefs
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentBoundaryStoreTest {
    @Before fun setup() { Prefs.initLocal(RuntimeEnvironment.getApplication()); Prefs.localAgentPreferences()!!.edit().clear().commit() }
    @Test fun missingBoundaryKeepsDefaultsButCorruptionFailsClosed() {
        assertTrue(AgentBoundaryStore.load().isAllowed("read_file"))
        Prefs.localAgentPreferences()!!.edit().putString("agent_tool_boundary_v1", "broken json").commit()
        assertFalse(AgentBoundaryStore.load().isAllowed("read_file"))
        assertFalse(AgentBoundaryStore.load().isAllowed("terminal"))
    }
    @Test fun wildcardDenyOverridesAllowAndSurvivesStorage() {
        val policy = AgentToolPolicy(listOf("mcp_*", "read_file"), listOf("mcp_admin*"))
        assertTrue(AgentBoundaryStore.save(policy))
        val actual = AgentBoundaryStore.load()
        assertTrue(actual.isAllowed("mcp_calendar")); assertFalse(actual.isAllowed("mcp_admin_delete")); assertFalse(actual.isAllowed("write_file"))
    }
    @Test fun behaviorHistoryRetainsFivePriorVersionsAndCanBeRestored() {
        repeat(8) { assertTrue(AgentBoundaryStore.saveBehavior("行为习惯$it")) }
        assertEquals(5, AgentBoundaryStore.behaviorHistory().size)
        assertEquals("行为习惯6", AgentBoundaryStore.behaviorHistory().first().second)
        AgentBoundaryStore.saveBehavior(AgentBoundaryStore.behaviorHistory().first().second)
        assertEquals("行为习惯6", AgentBoundaryStore.behavior())
    }
}
