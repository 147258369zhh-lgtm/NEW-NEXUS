package io.github.mangi.eta.agent.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRollbackCapabilityTest {
    @Test fun reversibleSettingsDeclareRollback() {
        assertTrue(EtaAppControlRegistry.canUndo("settings.memory.enabled"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.appearance"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.current_model"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.model_fallback"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.mcp_server"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.mcp_tool"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.skill.enabled"))
        assertTrue(EtaAppControlRegistry.canUndo("settings.learning"))
        assertFalse(EtaAppControlRegistry.canUndo("settings.memory.clear"))
    }
}
