package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope

/** App-owned services survive individual calls; only an explicit stop closes a live service. */
internal object McpProcessManager {
    private data class Managed(
        val process: Process,
        val logFile: File,
        val pidFile: File,
        val command: String,
        val directory: String,
        val target: String = "android",
        val backend: String = "chroot",
        val ownedProcess: McpExecutionProcess? = null,
    )
    private val processes = ConcurrentHashMap<String, Managed>()

    fun isRunning(serverId: String): Boolean = processes[serverId]?.process?.isAlive == true

    suspend fun ensureReady(context: Context, server: McpServerSetting, checkActive: () -> Unit = {}) {
        currentCoroutineContext().ensureActive()
        RuntimeInvocationScope.controller?.throwIfCancelled()
        checkActive()
        ensureStarted(context, server)
        awaitReady(server, checkActive = checkActive)
    }

    private fun ensureStarted(context: Context, server: McpServerSetting) {
        if (server.transportType != McpTransportType.HTTP || server.launchCommand.isBlank()) return
        synchronized(processes) {
            val current = processes[server.id]
            if (current?.process?.isAlive == true) {
                require(current.command == server.launchCommand && current.directory == server.workingDirectory &&
                    current.target == server.executionTarget && current.backend == server.linuxBackend) {
                    "MCP 本地服务仍在使用旧配置，请先停止服务后重试"
                }
                return
            }
            if (current != null) {
                terminate(current)
                processes.remove(server.id)
            }
            validateMcpExecutionBinding(server)
            val logDir = File(context.filesDir, "mcp_logs").apply { mkdirs() }
            val safeId = server.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val logFile = File(logDir, "$safeId.log")
            val pidFile = File(logDir, "$safeId.pid").apply { delete() }
            if (server.executionTarget != "android") {
                val owned = McpExecutionProcess.start(context, server, server.launchCommand, mergeStderr = true)
                processes[server.id] = Managed(owned.process, logFile, pidFile, server.launchCommand, server.workingDirectory, server.executionTarget, server.linuxBackend, owned)
                kotlin.concurrent.thread(name = "nexa-mcp-linux-log", isDaemon = true) {
                    runCatching {
                        owned.process.inputStream.use { input ->
                            logFile.outputStream().use { output ->
                                val buffer = ByteArray(4096)
                                var written = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    val keep = minOf(count, (1024L * 1024 - written).coerceAtLeast(0).toInt())
                                    if (keep > 0) { output.write(buffer, 0, keep); written += keep }
                                }
                            }
                        }
                    }
                }
                return
            }
            val script = "printf '%s\\n' \"\$\$\" > ${shellQuote(pidFile.absolutePath)}\n${server.launchCommand}"
            val builder = ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            server.workingDirectory.takeIf(String::isNotBlank)?.let { directory ->
                val file = File(directory)
                require(file.isDirectory) { "MCP 工作目录不存在" }
                builder.directory(file)
            }
            val process = builder.start()
            processes[server.id] = Managed(process, logFile, pidFile, server.launchCommand, server.workingDirectory, server.executionTarget, server.linuxBackend)
        }
    }

    private suspend fun awaitReady(server: McpServerSetting, timeoutMs: Long = 12_000L, checkActive: () -> Unit) {
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
            currentCoroutineContext().ensureActive()
            RuntimeInvocationScope.controller?.throwIfCancelled()
            checkActive()
            val managed = processes[server.id]
            if (managed == null || !managed.process.isAlive) {
                throw IOException("MCP 本地服务启动失败，所选执行位置中的进程未运行")
            }
            val reachable = runCatching {
                Socket().use { socket ->
                    val binding = RuntimeInvocationScope.controller?.register { runCatching { socket.close() } }
                    try { socket.connect(InetSocketAddress(host, port), 600) } finally { binding?.close() }
                }
                true
            }.getOrDefault(false)
            RuntimeInvocationScope.controller?.throwIfCancelled()
            checkActive()
            if (reachable) return
            delay(250)
        }
        throw IOException("MCP 本地服务启动超时，端口 $host:$port 未就绪")
    }

    fun stop(serverId: String) {
        synchronized(processes) {
            processes.remove(serverId)?.let(::terminate)
        }
    }

    /** Stops the owned process tree and keeps the record if any process is still alive. */
    suspend fun stopAndAwait(serverId: String, timeoutMs: Long = 3_000L): Boolean = withContext(Dispatchers.IO) {
        val managed = synchronized(processes) { processes.remove(serverId) } ?: return@withContext true
        managed.ownedProcess?.let { owned ->
            owned.close()
            val stopped = !managed.process.isAlive
            if (!stopped) processes.putIfAbsent(serverId, managed)
            if (stopped) managed.pidFile.delete()
            return@withContext stopped
        }
        val descendants = processTree(managed)
        descendants.forEach { pid -> signal(pid, force = false) }
        runCatching { managed.process.destroy() }
        val graceful = runCatching { managed.process.waitFor(minOf(timeoutMs, 750L), TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!graceful || descendants.any(::isAlive)) {
            descendants.filter(::isAlive).forEach { pid -> signal(pid, force = true) }
            if (managed.process.isAlive) runCatching { managed.process.destroyForcibly() }
            runCatching { managed.process.waitFor(timeoutMs.coerceAtLeast(750L), TimeUnit.MILLISECONDS) }
        }
        val stopped = !managed.process.isAlive && descendants.none(::isAlive)
        if (!stopped) processes.putIfAbsent(serverId, managed)
        if (stopped) managed.pidFile.delete()
        stopped
    }

    fun stopAll() {
        synchronized(processes) {
            processes.keys.toList().forEach(::stop)
        }
    }

    private fun terminate(managed: Managed) {
        managed.ownedProcess?.let { it.close(); managed.pidFile.delete(); return }
        val descendants = processTree(managed)
        descendants.forEach { pid -> signal(pid, force = false) }
        runCatching { managed.process.destroy() }
        descendants.filter(::isAlive).forEach { pid -> signal(pid, force = true) }
        if (managed.process.isAlive) runCatching { managed.process.destroyForcibly() }
        managed.pidFile.delete()
    }

    private fun processTree(managed: Managed): List<Int> = runCatching {
        val root = managed.pidFile.readText().trim().toInt()
        val discovered = linkedSetOf<Int>()
        val pending = ArrayDeque<Int>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val children = File("/proc/$parent/task/$parent/children")
                .takeIf(File::isFile)
                ?.readText()
                ?.split(Regex("\\s+"))
                .orEmpty()
                .mapNotNull(String::toIntOrNull)
            children.filter { it > 0 && discovered.add(it) }.forEach(pending::addLast)
        }
        discovered.toList()
    }.getOrDefault(emptyList())

    private fun isAlive(pid: Int): Boolean = runCatching { File("/proc/$pid").isDirectory }.getOrDefault(false)

    private fun signal(pid: Int, force: Boolean) {
        if (pid <= 0) return
        runCatching { android.os.Process.sendSignal(pid, if (force) 9 else 15) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun File.tail(limit: Int = 600): String = runCatching {
        RandomAccessFile(this, "r").use { file ->
            val count = minOf(file.length(), limit * 4L).toInt()
            file.seek(file.length() - count)
            ByteArray(count).also(file::readFully).toString(Charsets.UTF_8).takeLast(limit)
        }.trim().ifBlank { "无进程日志" }
    }.getOrDefault("无法读取进程日志")
}
