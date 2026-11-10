package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import org.json.JSONObject

internal object McpServerManager {
    suspend fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = withClient(server, bearerToken) { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastCheckedAt = refreshedAt,
            lastHealthyAt = refreshedAt,
            lastError = null,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun discoverCatalog(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpHttpClient.Catalog = withClient(server, bearerToken) { client ->
        client.discoverCatalog()
    }

    suspend fun readResource(
        server: McpServerSetting,
        bearerToken: String?,
        uri: String,
    ): String = withClient(server, bearerToken) { client ->
        client.readResource(uri)
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = try {
            discover(server, McpServerRepository.bearerToken(server))
        } catch (failure: Throwable) {
            runCatching {
                McpServerRepository.update(
                    server.copy(
                        lastCheckedAt = System.currentTimeMillis(),
                        lastError = McpFailureDiagnostics.describe(server, failure).take(500),
                    ),
                )
            }
            throw failure
        }
        McpServerRepository.update(refreshed)
        return refreshed
    }

    /** Explicit, user initiated tool probe used by the MCP detail screen. */
    suspend fun callTool(serverId: String, toolName: String, arguments: JSONObject): JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) { "MCP 服务器不存在" }
        val tool = server.tools.firstOrNull { it.name == toolName } ?: error("MCP 工具不存在")
        require(tool.name in server.enabledToolNames) { "请先启用此工具" }
        return withClient(server, McpServerRepository.bearerToken(server)) { client ->
            client.callTool(tool, arguments)
        }
    }

    suspend fun stopLocal(serverId: String) = McpProcessManager.stop(serverId)

    private suspend fun <T> withClient(
        server: McpServerSetting,
        bearerToken: String?,
        operation: (McpClient) -> T,
    ): T {
        if (server.launchCommand.isNotBlank()) {
            McpProcessManager.ensureStarted(McpServerRepository.appContext(), server)
            McpProcessManager.awaitReady(server)
        }
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val client = runCatching { McpClientFactory.create(server, bearerToken) }.getOrElse { failure ->
                if (server.launchCommand.isNotBlank()) McpProcessManager.stop(server.id)
                throw McpDiagnosticException(McpFailureDiagnostics.describe(server, failure), failure)
            }
            try {
                return client.use(operation)
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt == 0 && McpFailureDiagnostics.retryable(failure)) return@repeat
                if (server.launchCommand.isNotBlank()) McpProcessManager.stop(server.id)
                throw McpDiagnosticException(McpFailureDiagnostics.describe(server, failure), failure)
            }
        }
        throw McpDiagnosticException(McpFailureDiagnostics.describe(server, lastFailure), lastFailure)
    }
}

internal class McpDiagnosticException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
