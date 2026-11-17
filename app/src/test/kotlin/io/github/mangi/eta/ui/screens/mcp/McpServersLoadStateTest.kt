package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServersLoadStateTest {
    @Test
    fun emptyStateRequiresSuccessfulEmptyLoad() {
        assertFalse(shouldShowMcpServersEmptyState(McpServersLoadState.Loading))
        assertFalse(shouldShowMcpServersEmptyState(McpServersLoadState.Failed))
        assertTrue(shouldShowMcpServersEmptyState(McpServersLoadState.Loaded(emptyList())))
        assertFalse(shouldShowMcpServersEmptyState(McpServersLoadState.Loaded(listOf(server()))))
    }

    private fun server() = McpServerSetting(
        id = "server",
        name = "MCP",
        url = "https://example.invalid/mcp",
    )
}
