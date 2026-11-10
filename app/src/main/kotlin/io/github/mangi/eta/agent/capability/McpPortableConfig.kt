package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.repository.McpServerRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class McpPortableConnection(
    val name: String,
    val url: String,
    val transportType: String = McpTransportType.HTTP,
    val stdioCommand: String = "",
    val stdioArguments: List<String> = emptyList(),
    val launchCommand: String = "",
    val workingDirectory: String = "",
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val enabledToolNames: Set<String> = emptySet(),
)

data class McpPortableBundle(
    val exportedAt: Long,
    val connections: List<McpPortableConnection>,
)

data class McpPortableImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
    val credentialRequired: Int,
)

class McpPortableConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Non-secret MCP connection interchange. Tokens are deliberately excluded. */
internal object McpPortableCodec {
    const val SCHEMA = "eta-mcp-connections/v1"
    const val MAX_BYTES = 256 * 1024
    const val MAX_CONNECTIONS = 50

    fun encode(servers: List<McpServerSetting>, exportedAt: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("exportedAt", exportedAt)
            .put("credentialsIncluded", false)
            .put("connections", JSONArray().also { values ->
                servers.take(MAX_CONNECTIONS).forEach { server ->
                    values.put(
                        JSONObject()
                            .put("name", server.name)
                            .put("url", server.url)
                            .put("transportType", server.transportType)
                            .put("stdioCommand", server.stdioCommand)
                            .put("stdioArguments", JSONArray(server.stdioArguments))
                            .put("launchCommand", server.launchCommand)
                            .put("workingDirectory", server.workingDirectory)
                            .put("enabled", server.enabled)
                            .put("protocolMode", supportedProtocol(server.protocolMode))
                            .put("authorizationType", supportedAuthorization(server.authorizationType))
                            .put("enabledToolNames", JSONArray(server.enabledToolNames.sorted())),
                    )
                }
            })
        return root.toString(2)
    }

    fun decode(raw: String): McpPortableBundle {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw McpPortableConfigException("MCP 配置文件超过 256 KiB 上限")
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw McpPortableConfigException("MCP 配置文件不是有效 JSON", it) }
        if (root.optString("schema") != SCHEMA) {
            throw McpPortableConfigException("不支持的 MCP 配置文件格式，需要 $SCHEMA")
        }
        if (root.optBoolean("credentialsIncluded", false)) {
            throw McpPortableConfigException("配置文件声称包含凭据，已拒绝导入")
        }
        val values = root.optJSONArray("connections")
            ?: throw McpPortableConfigException("MCP 配置文件缺少 connections")
        if (values.length() > MAX_CONNECTIONS) {
            throw McpPortableConfigException("一次最多导入 $MAX_CONNECTIONS 个 MCP 连接")
        }
        val connections = buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index)
                    ?: throw McpPortableConfigException("第 ${index + 1} 个 MCP 连接格式无效")
                add(parseConnection(value, index))
            }
        }
        if (connections.map { it.transportType to (it.url.ifBlank { it.stdioCommand }) }.distinct().size != connections.size) {
            throw McpPortableConfigException("MCP 配置中存在重复连接")
        }
        return McpPortableBundle(
            exportedAt = root.optLong("exportedAt", 0L).coerceAtLeast(0L),
            connections = connections,
        )
    }

    private fun parseConnection(value: JSONObject, index: Int): McpPortableConnection {
        val label = "第 ${index + 1} 个 MCP 连接"
        val name = value.optString("name").trim()
        if (name.length !in 1..80 || name.any(Char::isISOControl)) {
            throw McpPortableConfigException("${label}名称应为 1-80 个可见字符")
        }
        val transport = value.optString("transportType", McpTransportType.HTTP)
        if (transport !in McpTransportType.values) throw McpPortableConfigException("${label}传输方式无效")
        val stdioCommand = value.optString("stdioCommand").trim()
        val parsedUrl = if (transport == McpTransportType.STDIO) {
            if (stdioCommand.isBlank()) throw McpPortableConfigException("${label}缺少 stdio 命令")
            null
        } else {
            runCatching { validateMcpEndpoint(value.optString("url")) }
                .getOrElse { throw McpPortableConfigException("${label}地址无效", it) }
        }
        if (parsedUrl != null && (!parsedUrl.username.isNullOrBlank() || !parsedUrl.password.isNullOrBlank())) {
            throw McpPortableConfigException("${label}地址不能包含用户名或密码")
        }
        if (parsedUrl != null && (parsedUrl.query != null || parsedUrl.fragment != null)) {
            throw McpPortableConfigException("${label}地址不能包含查询参数或片段，请使用独立认证字段")
        }
        val protocol = value.optString("protocolMode", McpProtocolMode.AUTO)
        if (protocol !in supportedProtocols) throw McpPortableConfigException("${label}协议模式无效")
        val authorization = value.optString("authorizationType", McpAuthorizationType.NONE)
        if (authorization !in supportedAuthorizations) {
            throw McpPortableConfigException("${label}认证方式无效")
        }
        val toolNames = value.optJSONArray("enabledToolNames") ?: JSONArray()
        if (toolNames.length() > 256) throw McpPortableConfigException("${label}启用工具过多")
        val tools = buildSet {
            for (toolIndex in 0 until toolNames.length()) {
                val toolName = toolNames.optString(toolIndex).trim()
                if (!TOOL_NAME.matches(toolName)) {
                    throw McpPortableConfigException("${label}包含无效工具名")
                }
                add(toolName)
            }
        }
        val argumentsArray = value.optJSONArray("stdioArguments") ?: JSONArray()
        if (argumentsArray.length() > 64) throw McpPortableConfigException("${label} stdio 参数过多")
        val arguments = buildList {
            for (argumentIndex in 0 until argumentsArray.length()) {
                val argument = argumentsArray.optString(argumentIndex)
                if (argument.length > 2_000 || argument.any(Char::isISOControl)) {
                    throw McpPortableConfigException("${label}包含无效 stdio 参数")
                }
                add(argument)
            }
        }
        return McpPortableConnection(
            name = name,
            url = parsedUrl?.toString().orEmpty(),
            transportType = transport,
            stdioCommand = stdioCommand,
            stdioArguments = arguments,
            launchCommand = value.optString("launchCommand").take(8_000),
            workingDirectory = value.optString("workingDirectory").take(2_000),
            enabled = value.optBoolean("enabled", true),
            protocolMode = protocol,
            authorizationType = authorization,
            enabledToolNames = tools,
        )
    }

    private fun supportedProtocol(value: String): String =
        value.takeIf { it in supportedProtocols } ?: McpProtocolMode.AUTO

    private fun supportedAuthorization(value: String): String =
        value.takeIf { it in supportedAuthorizations } ?: McpAuthorizationType.NONE

    private val supportedProtocols = setOf(
        McpProtocolMode.AUTO,
        McpProtocolMode.LATEST,
        McpProtocolMode.LEGACY,
    )
    private val supportedAuthorizations = setOf(
        McpAuthorizationType.NONE,
        McpAuthorizationType.BEARER,
    )
    private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
}

internal class McpPortableConfigService(
    private val listServers: suspend () -> List<McpServerSetting>,
    private val addServer: suspend (McpServerSetting) -> McpServerSetting,
    private val deleteServer: suspend (String) -> Unit,
) {
    constructor(context: Context) : this(
        listServers = { McpServerRepository.servers() },
        addServer = { server -> McpServerRepository.addUnconfigured(server) },
        deleteServer = { serverId -> McpServerRepository.delete(serverId) },
    )

    suspend fun exportJson(): String {
        return McpPortableCodec.encode(listServers())
    }

    suspend fun importJson(raw: String): McpPortableImportResult {
        val bundle = McpPortableCodec.decode(raw)
        val existing = listServers()
        val seenUrls = existing.mapTo(mutableSetOf()) { it.transportType to (it.url.ifBlank { it.stdioCommand }) }
        val unique = bundle.connections.filter { connection ->
            seenUrls.add(connection.transportType to (connection.url.ifBlank { connection.stdioCommand }))
        }
        val added = mutableListOf<String>()
        try {
            unique.forEach { connection ->
                val server = McpServerSetting(
                    id = UUID.randomUUID().toString(),
                    name = connection.name,
                    url = connection.url,
                    transportType = connection.transportType,
                    stdioCommand = connection.stdioCommand,
                    stdioArguments = connection.stdioArguments,
                    launchCommand = connection.launchCommand,
                    workingDirectory = connection.workingDirectory,
                    enabled = connection.enabled,
                    protocolMode = connection.protocolMode,
                    authorizationType = connection.authorizationType,
                    enabledToolNames = connection.enabledToolNames,
                )
                added += addServer(server).id
            }
        } catch (failure: Throwable) {
            val rollbackFailures = withContext(NonCancellable) {
                added.count { id -> runCatching { deleteServer(id) }.isFailure }
            }
            if (failure is CancellationException) throw failure
            val suffix = if (rollbackFailures > 0) {
                "，其中 $rollbackFailures 个未能回滚"
            } else {
                "，已回滚本次新增连接"
            }
            throw McpPortableConfigException("MCP 导入失败$suffix", failure)
        }
        return McpPortableImportResult(
            imported = unique.size,
            skippedDuplicates = bundle.connections.size - unique.size,
            credentialRequired = unique.count { it.authorizationType == McpAuthorizationType.BEARER },
        )
    }
}
