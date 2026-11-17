package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.agent.terminal.*
import io.github.mangi.eta.data.model.McpExecutionTarget
import io.github.mangi.eta.data.model.McpServerSetting
import java.io.File
import java.io.IOException

/** An explicit Linux binding never consults the current terminal selection or falls back. */
internal class McpExecutionProcess private constructor(val process: Process, private val supervisor: ShellProcessSupervisor?) {
    fun close() {
        if (supervisor != null) {
            supervisor.terminateAndReap(process)
            supervisor.unregisterProcess(process)
        } else {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
    }

    companion object {
        fun start(context: Context?, server: McpServerSetting, command: String, mergeStderr: Boolean): McpExecutionProcess {
            validateMcpExecutionBinding(server)
            if (server.executionTarget == McpExecutionTarget.ANDROID) {
                val builder = ProcessBuilder("sh", "-c", command).redirectErrorStream(mergeStderr)
                server.workingDirectory.takeIf(String::isNotBlank)?.let {
                    val directory = File(it)
                    require(directory.isDirectory) { "MCP 工作目录不存在" }
                    builder.directory(directory)
                }
                return McpExecutionProcess(builder.start(), null)
            }
            val app = requireNotNull(context) { "MCP Linux 执行环境未初始化" }
            val distribution = if (server.executionTarget == McpExecutionTarget.DEBIAN) LinuxDistribution.DEBIAN else LinuxDistribution.ALPINE
            val backend = LinuxExecutionBackend.entries.single { it.wireName == server.linuxBackend }
            val rootfs = LinuxEnvironmentPaths.rootfsDir(app, distribution, backend)
            require(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) && File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile) { "选定 MCP Linux 实例尚未安装基础工具，请先安装" }
            require(if (backend == LinuxExecutionBackend.CHROOT) TerminalRuntime.rootAvailable else ProotCommandBuilder.available()) { "选定 MCP Linux 实例当前不可运行" }
            val supervisor = ShellProcessSupervisor()
            val running = supervisor.startShellProcess(
                identity = if (backend == LinuxExecutionBackend.PROOT) "user" else "root",
                command = mcpGuestCommand(server.workingDirectory, command), mergeStderr = mergeStderr,
                environment = distribution.terminalEnvironment, linuxRootfsPath = rootfs.absolutePath,
            ) ?: throw IOException("无法启动选定 MCP Linux 实例")
            return McpExecutionProcess(running, supervisor)
        }
    }
}

internal fun validateMcpExecutionBinding(server: McpServerSetting) {
    require(server.executionTarget in McpExecutionTarget.values && server.linuxBackend in McpExecutionTarget.backends) { "MCP 执行位置无效" }
    require(server.workingDirectory.none(Char::isISOControl)) { "MCP 工作目录无效" }
    if (server.executionTarget != McpExecutionTarget.ANDROID && server.workingDirectory.isNotBlank())
        require(server.workingDirectory.startsWith('/')) { "Linux MCP 工作目录必须是实例内的绝对路径" }
}

internal fun mcpGuestCommand(directory: String, command: String): String {
    val target = directory.ifBlank { "/workspace" }
    require(target.startsWith('/') && target.none(Char::isISOControl)) { "Linux MCP 工作目录无效" }
    val quoted = "'" + target.replace("'", "'\\''") + "'"
    return "cd $quoted || { echo MCP_WORKING_DIRECTORY_UNAVAILABLE >&2; exit 72; }\n$command"
}
