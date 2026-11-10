package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.model.McpServerSetting
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object McpFailureDiagnostics {
    fun retryable(failure: Throwable): Boolean = failure is IOException &&
        failure !is McpHttpStatusException &&
        failure !is McpJsonRpcException

    fun describe(server: McpServerSetting, failure: Throwable?): String {
        val detail = failure?.message?.take(240).orEmpty()
        return when {
            server.transportType == McpTransportType.STDIO && server.stdioCommand.isBlank() ->
                "MCP stdio 缺少启动命令；请填写可执行文件或 shell 命令。"
            server.transportType == McpTransportType.STDIO && failure is McpJsonRpcException ->
                "MCP stdio 已启动，但握手失败：$detail"
            server.transportType == McpTransportType.STDIO && failure is IOException ->
                "MCP stdio 进程未就绪：$detail"
            failure is McpHttpStatusException -> when (failure.code) {
                401, 403 -> "MCP HTTP ${failure.code}：认证失败，请检查 Bearer Token。"
                404 -> "MCP HTTP 404：端点路径不正确，或服务尚未启动。"
                408, 504 -> "MCP HTTP ${failure.code}：服务器响应超时，请稍后重试。"
                in 500..599 -> "MCP HTTP ${failure.code}：服务器内部错误，请查看服务日志。"
                else -> "MCP HTTP ${failure.code}：$detail"
            }
            failure is McpJsonRpcException -> "MCP 协议握手失败：$detail"
            failure is ConnectException -> "无法连接 MCP 服务；本地服务请先启动并确认端口未冲突。"
            failure is SocketTimeoutException -> "MCP 连接超时；请检查地址、网络或本地服务启动状态。"
            failure is UnknownHostException -> "MCP 域名无法解析；请检查地址和网络连接。"
            server.launchCommand.isNotBlank() -> "MCP 本地服务启动或就绪检查失败：$detail"
            else -> "MCP 连接失败：${detail.ifBlank { failure?.javaClass?.simpleName ?: "未知错误" }}"
        }
    }
}
