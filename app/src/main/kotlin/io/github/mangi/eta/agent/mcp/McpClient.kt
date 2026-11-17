package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject
import org.json.JSONArray

internal interface McpClient : AutoCloseable {
    val protocolVersion: String?
    fun discoverTools(): McpHttpClient.Discovery
    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject
    /** Only a previously returned opaque MRTR state may reach this entry; no original-call replay. */
    fun resumeRequest(method: String, params: JSONObject): JSONObject = error("MCP_CONTINUATION_UNSUPPORTED")
    fun discoverCatalog(): McpHttpClient.Catalog
    fun readResource(uri: String): String
    fun readResourceContents(uri: String): JSONArray = error("MCP_RESOURCE_READ_UNSUPPORTED")
    fun readResourcePreview(uri: String): McpHttpClient.ContentPreview
    fun getPrompt(
        prompt: McpHttpClient.PromptSummary,
        arguments: Map<String, String>,
    ): McpHttpClient.ContentPreview
}

internal object McpClientFactory {
    fun create(server: McpServerSetting, bearerToken: String?, elicitation: ((JSONObject) -> JSONObject)? = null, recovery: McpRecoveryContext? = null): McpClient = when (server.transportType) {
        io.github.mangi.eta.data.model.McpTransportType.STDIO -> McpStdioClient(server, elicitation, recovery)
        else -> McpHttpClient(server, bearerToken, elicitation, recovery)
    }
}
