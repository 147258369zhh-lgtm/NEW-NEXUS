package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/** Owns user-configured on-demand local MCP server processes. */
internal object McpProcessManager {
    private data class Managed(val process: Process, val logFile: File)
    private val processes = ConcurrentHashMap<String, Managed>()

    fun ensureStarted(context: Context, server: McpServerSetting) {
        if (server.transportType != McpTransportType.HTTP || server.launchCommand.isBlank()) return
        synchronized(processes) {
            val current = processes[server.id]
            if (current?.process?.isAlive == true) return
            if (current != null) {
                runCatching { current.process.destroyForcibly() }
                processes.remove(server.id)
            }
            val logDir = File(context.filesDir, "mcp_logs").apply { mkdirs() }
            val logFile = File(logDir, "${server.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.log")
            val builder = ProcessBuilder("sh", "-c", server.launchCommand)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            server.workingDirectory.takeIf(String::isNotBlank)?.let { directory ->
                val file = File(directory)
                require(file.isDirectory) { "MCP 工作目录不存在" }
                builder.directory(file)
            }
            val process = builder.start()
            processes[server.id] = Managed(process, logFile)
        }
    }

    suspend fun awaitReady(server: McpServerSetting, timeoutMs: Long = 12_000L) {
        if (server.transportType != McpTransportType.HTTP || server.launchCommand.isBlank()) return
        val uri = runCatching { URI(server.url) }.getOrElse {
            throw IOException("MCP 地址无效", it)
        }
        val host = uri.host ?: throw IOException("MCP 地址缺少主机名")
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val managed = processes[server.id]
            if (managed == null || !managed.process.isAlive) {
                throw IOException("MCP 本地服务启动失败：${managed?.logFile?.tail() ?: "进程未运行"}")
            }
            val reachable = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 600) }
                true
            }.getOrDefault(false)
            if (reachable) return
            delay(250)
        }
        throw IOException("MCP 本地服务启动超时，端口 $host:$port 未就绪")
    }

    fun stop(serverId: String) {
        synchronized(processes) {
            processes.remove(serverId)?.process?.let { process ->
                runCatching { process.destroy() }
                if (process.isAlive) runCatching { process.destroyForcibly() }
            }
        }
    }

    fun stopAll() {
        synchronized(processes) {
            processes.keys.toList().forEach(::stop)
        }
    }

    private fun File.tail(limit: Int = 600): String = runCatching {
        readText().takeLast(limit).trim().ifBlank { "无进程日志" }
    }.getOrDefault("无法读取进程日志")
}
