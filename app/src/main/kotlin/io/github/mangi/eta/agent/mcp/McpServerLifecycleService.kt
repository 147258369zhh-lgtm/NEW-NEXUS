package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.repository.McpServerRepository

/** Coordinates the app-owned process with the durable MCP record and encrypted credential. */
internal class McpServerLifecycleService(
    private val stopProcess: suspend (String) -> Boolean = McpProcessManager::stopAndAwait,
    private val deleteRecord: suspend (String) -> Unit = McpServerRepository::delete,
) {
    suspend fun delete(serverId: String) {
        check(stopProcess(serverId)) { "MCP 本地服务未能停止，已保留连接配置供重试" }
        deleteRecord(serverId)
    }
}
