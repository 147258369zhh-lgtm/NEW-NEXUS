package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapabilitySnapshotTest {
    @Test
    fun serializesRedactedStableCapabilityContract() {
        val snapshot = AgentCapabilitySnapshot(
            sdk = 36,
            manufacturer = "OPPO",
            model = "Find X7 Ultra",
            root = true,
            lsposed = false,
            accessibility = true,
            accessibilityRecovery = true,
            notifications = true,
            usage = false,
            location = false,
            colorOs = true,
            calendarRead = true,
            calendarWrite = false,
            supported = setOf("observe_screen", "get_current_context"),
        )
        val json = snapshot.toJson()
        assertEquals(1, json.getInt("schema_version"))
        assertTrue(json.getBoolean("root"))
        assertEquals("OPPO", json.getString("manufacturer"))
        assertTrue(snapshot.compactSummary().contains("Root"))
    }
}
