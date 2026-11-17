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
        val availableNames = discovery.tools.groupingBy { it.name }.eachCount().filterValues { it == 1 }.keys
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
        val connection = requireNotNull(McpServerRepository.connectionSnapshot(serverId)) {
            "MCP 服务器不存在"
        }
        val server = connection.server
        val token = connection.token
        val refreshed = try {
            discover(server, token)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunPauseException) throw failure
            runCatching {
                McpServerRepository.recordDiscoveryFailure(server, token, McpFailureDiagnostics.describe(server, failure))
            }
            throw failure
        }
        return McpServerRepository.saveDiscovery(server, refreshed, token)
            ?: error("MCP 配置已更改，请按当前配置重新刷新")
    }

    /** Explicit, user initiated tool probe used by the MCP detail screen. */
    suspend fun callTool(serverId: String, toolName: String, arguments: JSONObject): JSONObject {
        val connection = requireNotNull(McpServerRepository.connectionSnapshot(serverId)) { "MCP 服务器不存在" }
        val server = connection.server
        val tool = server.tools.singleOrNull { it.name == toolName }
            ?: error("工具不存在或名称重复，请修正服务后刷新")
        require(tool.name in server.enabledToolNames) { "请先启用此工具" }
        return withClient(server, connection.token, retryReadOnly = false) { client ->
            client.callTool(tool, arguments)
        }
    }

    suspend fun stopLocal(serverId: String): Boolean = McpProcessManager.stopAndAwait(serverId)

    private suspend fun <T> withClient(
        server: McpServerSetting,
        bearerToken: String?,
        retryReadOnly: Boolean = true,
        operation: (McpClient) -> T,
    ): T {
        if (server.launchCommand.isNotBlank()) {
            McpProcessManager.ensureReady(McpServerRepository.appContext(), server)
        }
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val client = runCatching { McpClientFactory.create(server, bearerToken) }.getOrElse { failure ->
                throw McpDiagnosticException(McpFailureDiagnostics.describe(server, failure), failure)
            }
            val controller = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller
            val cancellation = controller?.register(client::close)
            try {
                controller?.throwIfCancelled()
                return client.use(operation).also { controller?.throwIfCancelled() }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException ||
                    failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                    failure is io.github.mangi.eta.agent.runtime.AgentRunPauseException) throw failure
                controller?.throwIfCancelled()
                lastFailure = failure
                if (retryReadOnly && attempt == 0 && McpFailureDiagnostics.retryable(failure)) return@repeat
                throw McpDiagnosticException(McpFailureDiagnostics.describe(server, failure), failure)
            } finally {
                cancellation?.close()
            }
        }
        throw McpDiagnosticException(McpFailureDiagnostics.describe(server, lastFailure), lastFailure)
    }
}

internal class McpDiagnosticException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
