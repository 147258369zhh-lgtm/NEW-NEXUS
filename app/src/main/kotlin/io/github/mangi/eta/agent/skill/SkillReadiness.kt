package io.github.mangi.eta.agent.skill

import android.content.Context
import io.github.mangi.eta.agent.terminal.DebianEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.DebianEnvironmentState
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import java.io.File

enum class SkillPrerequisiteStatus {
    READY,
    BLOCKED,
    UNVERIFIED,
}

data class SkillPrerequisiteReport(
    val requirement: String,
    val status: SkillPrerequisiteStatus,
    val detail: String,
)

internal data class SkillReadinessEnvironment(
    val rootAvailable: Boolean,
    val accessibilityAvailable: Boolean,
    val accessibilityRecoveryAvailable: Boolean,
    val linuxState: DebianEnvironmentState,
    val supportedTools: Set<String>,
    val availableExecutables: Set<String>,
)

/** Read-only prerequisites review. Unknown declarations stay UNVERIFIED instead of being reported as installed. */
internal object SkillReadinessInspector {
    fun capture(context: Context): SkillReadinessEnvironment {
        val appContext = context.applicationContext
        val capabilities = AgentToolCapabilities.capture(appContext)
        val linux = DebianEnvironmentInstaller(appContext).status().state
        val rootfs = LinuxEnvironmentPaths.rootfsDir(appContext, LinuxDistribution.DEBIAN)
        return SkillReadinessEnvironment(
            rootAvailable = capabilities.rootAvailable,
            accessibilityAvailable = capabilities.accessibilityAvailable ||
                capabilities.accessibilityRecoveryAvailable,
            accessibilityRecoveryAvailable = capabilities.accessibilityRecoveryAvailable,
            linuxState = linux,
            supportedTools = AgentToolRequirements.toolNames
                .filter { capabilities.unavailableCode(it) == null }
                .toSet(),
            availableExecutables = detectExecutables(rootfs),
        )
    }

    fun inspect(
        dependencies: List<String>,
        environment: SkillReadinessEnvironment,
    ): List<SkillPrerequisiteReport> = dependencies.map { raw ->
        val requirement = raw.trim()
        val normalizedRequirement = requirement.lowercase()
        when {
            canonical(requirement) in ROOT_NAMES -> if (environment.rootAvailable) {
                ready(requirement, "Root 权限可用")
            } else {
                blocked(requirement, "需要在权限中心授予 Root")
            }
            canonical(requirement) in ACCESSIBILITY_NAMES -> if (environment.accessibilityAvailable) {
                ready(requirement, "无障碍或自动恢复可用")
            } else {
                blocked(requirement, "需要启用 Nexus 无障碍服务")
            }
            canonical(requirement) == "linux" -> when (environment.linuxState) {
                DebianEnvironmentState.READY -> ready(requirement, "Linux 环境已就绪")
                DebianEnvironmentState.BASE_READY -> unverified(requirement, "Linux 基础环境已安装，常用工具尚未确认")
                DebianEnvironmentState.NOT_INSTALLED -> blocked(requirement, "需要先在终端中安装 Linux 环境")
            }
            normalizedRequirement in AgentToolRequirements.toolNames -> if (normalizedRequirement in environment.supportedTools) {
                ready(requirement, "所需工具当前可用")
            } else {
                blocked(requirement, "工具 $normalizedRequirement 当前不可用")
            }
            else -> inspectBinary(requirement, environment)
        }
    }

    private fun inspectBinary(
        requirement: String,
        environment: SkillReadinessEnvironment,
    ): SkillPrerequisiteReport {
        val binary = binaryName(requirement)
        return when {
            binary in FILE_TOOLS -> if (FILE_TOOLS.all(environment.supportedTools::contains)) {
                ready(requirement, "文件工具当前可用")
            } else {
                blocked(requirement, "需要可用的文件读写工具")
            }
            binary in KNOWN_BINARIES -> if (binary in environment.availableExecutables) {
                ready(requirement, "已在本地环境中检测到")
            } else {
                blocked(requirement, "未检测到 $binary，请安装后重试")
            }
            else -> unverified(requirement, "尚未识别该依赖，需要运行前确认")
        }
    }

    private fun detectExecutables(rootfs: File): Set<String> = buildSet {
        BINARY_LOCATIONS.forEach { (name, candidates) ->
            if (candidates.any { relative -> File(rootfs, relative).isFile }) add(name)
        }
    }

    private fun binaryName(raw: String): String {
        val value = canonical(raw)
            .removePrefix("binary:")
            .removePrefix("command:")
        return value.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    private fun canonical(raw: String): String = raw.trim().lowercase()
        .replace(' ', '-')

    private fun ready(requirement: String, detail: String) =
        SkillPrerequisiteReport(requirement, SkillPrerequisiteStatus.READY, detail)

    private fun blocked(requirement: String, detail: String) =
        SkillPrerequisiteReport(requirement, SkillPrerequisiteStatus.BLOCKED, detail)

    private fun unverified(requirement: String, detail: String) =
        SkillPrerequisiteReport(requirement, SkillPrerequisiteStatus.UNVERIFIED, detail)

    private val ROOT_NAMES = setOf("root", "superuser", "root-access")
    private val ACCESSIBILITY_NAMES = setOf("accessibility", "screen-access", "a11y")
    private val FILE_TOOLS = setOf("read_file", "write_file")
    private val KNOWN_BINARIES = setOf(
        "bash", "curl", "ffmpeg", "git", "jq", "libreoffice", "node", "nodejs",
        "pandoc", "python", "python3", "sqlite3",
    )
    private val BINARY_LOCATIONS = mapOf(
        "bash" to listOf("bin/bash", "usr/bin/bash"),
        "curl" to listOf("usr/bin/curl"),
        "ffmpeg" to listOf("usr/bin/ffmpeg"),
        "git" to listOf("usr/bin/git"),
        "jq" to listOf("usr/bin/jq"),
        "libreoffice" to listOf("usr/bin/libreoffice"),
        "node" to listOf("usr/bin/node", "usr/local/bin/node"),
        "nodejs" to listOf("usr/bin/nodejs", "usr/bin/node"),
        "pandoc" to listOf("usr/bin/pandoc"),
        "python" to listOf("usr/bin/python", "usr/bin/python3", "usr/local/bin/python3"),
        "python3" to listOf("usr/bin/python3", "usr/local/bin/python3"),
        "sqlite3" to listOf("usr/bin/sqlite3"),
    )
}
