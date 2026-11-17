package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

internal object McpTransportType {
    const val HTTP = "http"
    const val STDIO = "stdio"

    val values = setOf(HTTP, STDIO)
}

internal object McpExecutionTarget {
    const val ANDROID = "android"
    const val DEBIAN = "debian"
    const val ALPINE = "alpine"
    val values = setOf(ANDROID, DEBIAN, ALPINE)
    val backends = setOf("chroot", "proot")
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val transportType: String = McpTransportType.HTTP,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val stdioCommand: String = "",
    val stdioArguments: List<String> = emptyList(),
    val launchCommand: String = "",
    val workingDirectory: String = "",
    val executionTarget: String = McpExecutionTarget.ANDROID,
    val linuxBackend: String = "chroot",
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val lastHealthyAt: Long? = null,
    val lastError: String? = null,
) {
    fun sameConnectionAs(other: McpServerSetting): Boolean =
        url == other.url && transportType == other.transportType && protocolMode == other.protocolMode &&
            authorizationType == other.authorizationType && stdioCommand == other.stdioCommand &&
            stdioArguments == other.stdioArguments && launchCommand == other.launchCommand &&
            workingDirectory == other.workingDirectory && executionTarget == other.executionTarget &&
            linuxBackend == other.linuxBackend

    val uniqueToolNames: Set<String>
        get() = tools.groupingBy { it.name }.eachCount().filterValues { it == 1 }.keys

    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            val uniqueNames = uniqueToolNames
            tools.filter { it.name in enabledToolNames && it.name in uniqueNames }
        }
}
