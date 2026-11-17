package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.capability.CapabilityToolProvider
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.McpCredentialsUnavailable
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import kotlinx.coroutines.ensureActive
import io.github.zhh_lgtm.nexus.application.api.HostAdapter
import io.github.zhh_lgtm.nexus.application.api.HostStatus
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.capability.RunHostBinding
import io.github.mangi.eta.agent.capability.RunHostIdentity

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
    val resourceReader: Boolean = false,
)

/** Schemas are frozen for the run; revocation and connection changes apply immediately. */
internal class McpRunSnapshot(
    tools: List<McpRunTool>,
) {
    private val nameCounts = tools.groupingBy(McpRunTool::modelName).eachCount()
    val tools = tools.filter { nameCounts[it.modelName] == 1 }
    private val byModelName = this.tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具（server.id=").append(tool.server.id).append("，@mcp:").append(tool.server.id).append("）")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    append("。 ").append(it)
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    fun hostAdapters(): List<HostAdapter> = tools.groupBy { it.server.id }.map { (serverId, serverTools) ->
        val server = serverTools.first().server
        object : HostAdapter {
            override val id = RunHostIdentity.of("mcp", serverId)
            override fun status() = HostStatus.READY
            override fun listTools(): List<ToolDescriptor> = serverTools.map { tool ->
                ToolDescriptor(
                    id = tool.modelName,
                    namespace = "mcp",
                    shortDescription = "MCP ${server.name}: ${tool.definition.description.take(160)}",
                    modelDescription = tool.definition.description.ifBlank { "MCP tool ${tool.definition.name}" },
                    inputSchemaRef = "mcp://${server.id}/${tool.definition.name}",
                    effectClass = EffectClass.HIGH_RISK,
                    replayPolicy = ReplayPolicy.REQUIRE_USER,
                    timeoutMs = 120_000,
                    hostKind = HostKind.MCP,
                )
            }
        }
    }

    fun bindings(): List<RunHostBinding> = tools.groupBy { it.server.id }.map { (_, rows) ->
        val snapshot = McpRunSnapshot(rows)
        val host = snapshot.hostAdapters().single()
        val schemas = JSONArray().also(snapshot::appendModelTools)
        RunHostBinding(host.id, host.listTools(), schemas, McpToolExecutor(snapshot), available = { name ->
            snapshot.resolve(name)?.let(::isToolStillAvailable) == true
        }, displayName = rows.first().server.name)
    }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(): McpRunSnapshot {
            val projected = mutableListOf<McpRunTool>()
            val resourceReaders = mutableListOf<McpRunTool>()
            val now = System.currentTimeMillis()
            val connections = discoverMcpBatch(McpServerRepository.enabledServers()) { listed ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfCancelled()
                val connection = try {
                    McpServerRepository.connectionSnapshot(listed.id)
                } catch (_: McpCredentialsUnavailable) {
                    AndroidAgentLogger.warn("MCP server excluded: code=MCP_CREDENTIALS_UNAVAILABLE")
                    return@discoverMcpBatch null
                } ?: return@discoverMcpBatch null
                val configured = connection.server
                val bearerToken = connection.token
                if (!configured.enabled) return@discoverMcpBatch null
                val needsRefresh = configured.lastProtocolVersion == null ||
                    configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                    (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
                val server = if (needsRefresh) {
                    try {
                        val discovered = McpServerManager.discover(configured, bearerToken)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfCancelled()
                        McpServerRepository.saveDiscovery(configured, discovered, bearerToken)
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException ||
                            failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                            failure is io.github.mangi.eta.agent.runtime.AgentRunPauseException) throw failure
                        io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfStopped()
                        AndroidAgentLogger.warn("MCP discovery excluded: type=${failure.safeLogType()}")
                        McpServerRepository.recordDiscoveryFailure(configured, bearerToken, McpFailureDiagnostics.describe(configured, failure))
                        null
                    }
                        ?: return@discoverMcpBatch null
                } else {
                    configured
                }
                if (!server.enabled) return@discoverMcpBatch null
                server to bearerToken
            }
            connections.filterNotNull().forEach { (server, bearerToken) ->
                // A resource_link need not appear in resources/list. Keep this explicit adapter lazy;
                // actual resources/read support is checked by the server when the user invokes it.
                resourceReaders += resourceReader(server, bearerToken)
                server.activeTools.filter { McpToolGovernance.assess(it).accepted }.forEach { tool ->
                    projected += McpRunTool(
                        modelName = modelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
            }
            // Keep the discovery/routing directory complete. Lazy activation bounds model schemas;
            // truncating here makes later enabled tools and resource links impossible to recover.
            projected += resourceReaders
            return McpRunSnapshot(projected)
        }

        /** A local protocol adapter, never a server-declared executable tool. */
        fun resourceReader(server: McpServerSetting, bearerToken: String?): McpRunTool = McpRunTool(
            // Remote names have an alphanumeric server part after mcp_. The reserved double underscore
            // cannot collide, while retaining the mcp_ prefix required by authorization and privacy policies.
            modelName = "mcp__nexa_" + modelToolName(server.id, "nexa_read_resource").removePrefix("mcp_"),
            server = server, bearerToken = bearerToken,
            resourceReader = true,
            definition = McpToolDefinition("nexa_read_resource",
                description = "显式读取本服务器返回的resource_link URI并把实际内容保存为当前会话或会议成果。调用服务器的resources/read，使用该MCP连接认证，不读取浏览器Cookie。只传明确的资源URI，不会自动抓取正文链接。",
                inputSchemaJson = JSONObject().put("type", "object").put("additionalProperties", false)
                    .put("properties", JSONObject().put("uri", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 4096)))
                    .put("required", JSONArray().put("uri")).toString()),
        )

        private fun modelToolName(serverId: String, toolName: String): String {
            val serverPart = serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }
            val normalized = toolName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$toolName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "mcp_${serverPart}_${normalized}_$digest"
        }

    }
}

private fun isToolStillAvailable(frozen: McpRunTool): Boolean = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
    val connection = runCatching { McpServerRepository.connectionSnapshot(frozen.server.id) }.getOrNull()
    val current = connection?.server
    current != null && current.enabled && current.sameConnectionAs(frozen.server) &&
        (frozen.resourceReader || current.activeTools.any { it == frozen.definition }) && connection.token == frozen.bearerToken
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
    private val available: (McpRunTool) -> Boolean = ::isToolStillAvailable,
) : CapabilityToolProvider {
    private val lifecycleLock = Any()
    private val clients = mutableSetOf<McpClient>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        if (synchronized(lifecycleLock) { closed }) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
        return runCatching {
            val controller = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller
            controller?.throwIfCancelled()
            if (!available(tool)) return failure("MCP_TOOL_UNAVAILABLE", "MCP 工具已停用或配置发生变化")
            if (tool.server.launchCommand.isNotBlank()) {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.coroutineContext()) {
                    McpProcessManager.ensureReady(McpServerRepository.appContext(), tool.server) {
                        check(!synchronized(lifecycleLock) { closed }) { "MCP 工具执行器已关闭" }
                    }
                }
            }
            if (tool.server.launchCommand.isNotBlank() && !available(tool)) {
                return failure("MCP_TOOL_UNAVAILABLE", "MCP 工具已停用或配置发生变化")
            }
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                McpClientFactory.create(
                    server = tool.server,
                    bearerToken = tool.bearerToken,
                    elicitation = McpElicitationBridge(McpServerRepository.appContext(), tool.server)::handle,
                    recovery = McpRecoveryContext(McpServerRepository.appContext(), tool, toolCall),
                ).also { clients += it }
            }
            val cancellation = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.register(client::close)
            try {
                controller?.throwIfCancelled()
                val result = if (tool.resourceReader) {
                    val contents = client.readResourceContents(arguments.getString("uri"))
                    JSONObject().put("resultType", "complete").put("content", JSONArray().apply {
                        for (index in 0 until contents.length()) contents.optJSONObject(index)?.let { resource ->
                            put(JSONObject().put("type", "resource").put("resource", resource))
                        }
                    })
                } else client.callTool(tool.definition, arguments)
                adaptResult(tool, result, client.protocolVersion).also { controller?.throwIfCancelled() }
            }
            finally { cancellation?.close(); synchronized(lifecycleLock) { clients.remove(client) }; client.close() }
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunPauseException) throw failure
            io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfCancelled()
            failure("MCP_CALL_FAILED", McpFailureDiagnostics.describe(tool.server, failure))
        }
    }

    override fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    internal fun adaptResult(tool: McpRunTool, result: JSONObject, protocolVersion: String?): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "MCP 补充输入未完成：协议适配未取得可恢复结果，请查看该运行的追问记录",
            )
        }
        if (protocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        val files = mutableListOf<AgentModelClient.ToolFile>()
        var fileBytes = 0L
        fun attachment(name: String, mime: String, bytes: ByteArray): Boolean {
            if (result.optBoolean("isError", false) || files.size >= 8 || bytes.isEmpty() || bytes.size > 8 * 1024 * 1024 || fileBytes + bytes.size > 16 * 1024 * 1024) return false
            files += AgentModelClient.ToolFile(name, mime.take(120), bytes); fileBytes += bytes.size; return true
        }
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val decoded = if (data.length <= MAX_IMAGE_BYTES * 2) runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() else null
                    val bytes = decoded?.size
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                    attachment("image-${index + 1}." + when (mimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/png" -> "png"; else -> "bin" }, mimeType, decoded)
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val binary = resource?.optString("blob")?.takeIf { it.isNotBlank() && it.length <= 12 * 1024 * 1024 }
                    val embedded = if (binary != null) runCatching { Base64.decode(binary, Base64.DEFAULT) }.getOrNull()
                        else (resource?.opt("text") as? String)?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024 }?.toByteArray(Charsets.UTF_8)
                    val saved = embedded != null && attachment(resource?.optString("uri").orEmpty().substringAfterLast('/').ifBlank { "resource-${index + 1}" },
                        resource?.optString("mimeType").orEmpty().ifBlank { if (binary == null) "text/plain" else "application/octet-stream" }, embedded)
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (!saved && (resource?.has("blob") == true || resourceText.isNotBlank())) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val reader = snapshot.tools.firstOrNull { it.resourceReader && it.server.id == tool.server.id }?.modelName
                        val rendered = (if (label.isBlank()) uri else "$label: $uri") +
                            reader?.let { "\n读取并归档该资源请显式调用 $it，uri 参数使用上述地址。" }.orEmpty()
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> {
                    val mime = item.optString("mimeType")
                    val data = item.optString("data")
                    val bytes = if (mime.startsWith("audio/") && data.length <= 12 * 1024 * 1024) runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() else null
                    if (bytes == null || !attachment("audio-${index + 1}." + when (mime) { "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; else -> "bin" }, mime, bytes)) omittedItems++
                }
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
            files = files,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val providers: List<CapabilityToolProvider>,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    constructor(
        local: AgentModelClient.ToolExecutor,
        mcp: McpToolExecutor,
    ) : this(local, listOf(mcp))

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        providers.firstOrNull { it.contains(toolCall.name) }?.execute(toolCall)
            ?: local.execute(toolCall)

    override fun close() {
        providers.forEach { provider -> runCatching { provider.close() } }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
