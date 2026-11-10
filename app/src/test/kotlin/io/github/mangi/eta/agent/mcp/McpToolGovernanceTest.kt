package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolGovernanceTest {
    @Test
    fun boundsAndClassifiesRemoteTools() {
        val safe = McpToolDefinition(name = "lookup", description = "查资料", inputSchemaJson = "{\"type\":\"object\"}")
        assertTrue(McpToolGovernance.assess(safe).accepted)
        assertEquals(McpRisk.SAFE, McpToolGovernance.assess(safe).risk)
        val risky = McpToolDefinition(name = "delete_file", description = "delete a file", inputSchemaJson = "{\"type\":\"object\"}")
        assertEquals(McpRisk.REVIEW, McpToolGovernance.assess(risky).risk)
        val invalid = McpToolDefinition(name = "bad name", inputSchemaJson = "{}")
        assertFalse(McpToolGovernance.assess(invalid).accepted)
    }
}
