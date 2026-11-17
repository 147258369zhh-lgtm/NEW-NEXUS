package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.terminal.*
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlatformTool(val descriptor: ToolDescriptor, val unavailable: String?)
internal data class PlatformLinuxPackage(val id: String, val title: String, val commands: List<String>, val installed: Boolean)
internal data class ToolPlatformSnapshot(val tools: List<PlatformTool>, val linuxName: String,
    val linuxReady: Boolean, val linuxPackages: List<PlatformLinuxPackage>)

/** Read-only projection of existing owners. Does not launch shells, connect services or install packages. */
internal class ToolPlatformService(context: Context) {
    private val context = context.applicationContext
    suspend fun snapshot(): ToolPlatformSnapshot = withContext(Dispatchers.IO) {
        val capabilities = AgentToolCapabilities.capture(context)
        val tools = NexusToolDescriptors.byId.values.map { PlatformTool(it, capabilities.unavailableCode(it.id)) }
        val selection = LinuxEnvironmentSettingsRepository.executionSelection(context)
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, selection.distribution, selection.backend)
        val ready = LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)
        val names = mapOf("python" to "Python 运行环境", "node" to "Node 运行环境", "ssh" to "远程连接", "kimi" to "Kimi 代码助手")
        val packages = LinuxPackageProfiles.ALL.filter { selection.distribution in it.specs }.map { profile ->
            PlatformLinuxPackage(profile.id, names[profile.id] ?: profile.displayName,
                profile.spec(selection.distribution).checks.map { it.command }.distinct(),
                ready && linuxPackageProfileReady(rootfs, profile))
        } + PlatformLinuxPackage("apk_analysis", "APK 分析", listOf("apktool", "jadx"), ready && linuxApkAnalysisReady(rootfs))
        ToolPlatformSnapshot(tools, "${selection.distribution.wireName} · ${selection.backend.wireName}", ready, packages)
    }
}
