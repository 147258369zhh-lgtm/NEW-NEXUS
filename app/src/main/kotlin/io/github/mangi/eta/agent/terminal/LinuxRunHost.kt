package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.capability.RunHostIdentity
import io.github.mangi.eta.agent.capability.RunLocalHostBinding
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.HostAdapter
import io.github.zhh_lgtm.nexus.application.api.HostStatus
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import org.json.JSONObject

/** Metadata and execution resolve to the already selected terminal owner and workspace. */
internal object LinuxRunHost {
    fun binding(context: TerminalExecutionContext, limits: LinuxExecutionLimits, directory: CapabilityDirectory,
        stopped: () -> Boolean, rootAvailable: () -> Boolean, sessionEnvironment: (String) -> TerminalEnvironment?,
        prootAvailable: () -> Boolean = ProotCommandBuilder::available, activeProcesses: () -> Int = { 0 }): RunLocalHostBinding {
        val tools = AgentFileToolCatalog.names + AgentTerminalToolCatalog.names
        val adapter = object : HostAdapter {
            override val id = RunHostIdentity.of("linux", "${context.linuxRootfsPath}:${context.workspaceBinding?.id ?: "shared"}")
            override fun status() = when {
                stopped() -> HostStatus.STOPPED
                !context.linuxReady -> HostStatus.STOPPED
                context.linuxBackend == LinuxExecutionBackend.CHROOT && !rootAvailable() -> HostStatus.DEGRADED
                context.linuxBackend == LinuxExecutionBackend.PROOT && !prootAvailable() -> HostStatus.DEGRADED
                else -> HostStatus.READY
            }
            override fun listTools() = tools.mapNotNull { directory.describe(it)?.copy(hostKind = HostKind.LINUX) }
        }
        return RunLocalHostBinding(adapter, "Linux", accepts = { name, args ->
            name in tools && (args.optString("environment").equals("linux", ignoreCase = true) ||
                args.optString("session_id").takeIf(String::isNotBlank)?.let(sessionEnvironment)?.isLinux == true)
        }, metadata = {
            JSONObject().put("backend", context.linuxBackend.wireName).put("limits", limits.json())
                .put("active_shells", activeProcesses())
                .put("workspace", JSONObject().put("host_path", context.linuxHostWorkspacePath).put("guest_path", context.linuxWorkspacePath)
                    .put("project_id", context.workspaceBinding?.projectId ?: JSONObject.NULL).put("source_read_only", context.workspaceBinding?.sourceReadOnly ?: false))
        })
    }
}
