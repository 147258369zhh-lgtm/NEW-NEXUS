package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject

internal interface McpClient : AutoCloseable {
    fun discoverTools(): McpHttpClient.Discovery
    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject
    fun discoverCatalog(): McpHttpClient.Catalog
    fun readResource(uri: String): String
    fun readResourcePreview(uri: String): McpHttpClient.ContentPreview
    fun getPrompt(
        prompt: McpHttpClient.PromptSummary,
        arguments: Map<String, String>,
    ): McpHttpClient.ContentPreview
}

internal object McpClientFactory {
    fun create(server: McpServerSetting, bearerToken: String?): McpClient = when (server.transportType) {
        io.github.mangi.eta.data.model.McpTransportType.STDIO -> McpStdioClient(server)
        else -> McpHttpClient(server, bearerToken)
    }
}
