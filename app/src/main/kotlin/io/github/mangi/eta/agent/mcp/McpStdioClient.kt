package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Newline-delimited MCP stdio transport. The process is started on demand and reaped on close. */
internal class McpStdioClient(
    private val server: McpServerSetting,
    private val elicitation: ((JSONObject) -> JSONObject)? = null,
    private val recovery: McpRecoveryContext? = null,
) : McpClient {
    private val requestIds = AtomicLong(1)
    private data class QueuedMessage(val json: JSONObject, val bytes: Long)
    private val responses = LinkedBlockingQueue<QueuedMessage>(64)
    private val queuedBytes = AtomicLong(0)
    @Volatile private var readerFailure: IOException? = null
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val writeLock = Any()
    private val stderr = StringBuilder()
    @Volatile private var process: Process? = null
    @Volatile private var ownedProcess: McpExecutionProcess? = null
    @Volatile override var protocolVersion: String? = null
        private set

    override fun discoverTools(): McpHttpClient.Discovery {
        ensureInitialized()
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var cacheTtlMs: Long? = null
        var pages = 0
        do {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = request("tools/list", params)
            if (protocolVersion == McpProtocolMode.LATEST && !result.has("resultType")) {
                check(server.protocolMode == McpProtocolMode.AUTO) { "MCP_LATEST_RESULT_TYPE_REQUIRED" }
                initializeLegacy()
                return discoverTools()
            }
            if (protocolVersion == McpProtocolMode.LATEST) require(result.optString("resultType") == "complete")
            val array = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until array.length()) {
                parseTool(array.optJSONObject(index))?.let(tools::add)
            }
            cacheTtlMs = result.optJSONObject("cacheHints")?.optLong("ttlMs")?.takeIf { it > 0 }
            cursor = result.optString("nextCursor").takeIf(String::isNotBlank)
            pages += 1
        } while (cursor != null && pages < MAX_PAGES)
        return McpHttpClient.Discovery(
            protocolVersion = protocolVersion ?: McpProtocolMode.LATEST,
            tools = tools.take(MAX_TOOLS),
            cacheTtlMs = cacheTtlMs,
        )
    }

    override fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        ensureInitialized()
        val params = JSONObject().put("name", tool.name).put("arguments", arguments)
        val initial = request("tools/call", params)
        return if (protocolVersion == McpProtocolMode.LATEST) McpInputRoundTrips.complete(initial, params, elicitation, recovery, "tools/call") { retry -> request("tools/call", retry) } else initial
    }

    override fun resumeRequest(method: String, params: JSONObject): JSONObject {
        require(server.protocolMode != McpProtocolMode.LEGACY && method in setOf("tools/call", "resources/read", "prompts/get"))
        require(params.optString("requestState").isNotEmpty()) { "MCP_OPAQUE_STATE_REQUIRED" }
        startProcess()
        protocolVersion = McpProtocolMode.LATEST
        return request(method, params)
    }

    override fun discoverCatalog(): McpHttpClient.Catalog {
        ensureInitialized()
        val resources = runCatching { listResources() }.getOrNull()
        val prompts = runCatching { listPrompts() }.getOrNull()
        return McpHttpClient.Catalog(
            resources = resources?.first.orEmpty(),
            prompts = prompts?.first.orEmpty(),
            resourcesSupported = resources != null,
            promptsSupported = prompts != null,
            resourcesTruncated = resources?.second ?: false,
            promptsTruncated = prompts?.second ?: false,
        )
    }

    override fun readResource(uri: String): String = readResourcePreview(uri).text

    override fun readResourcePreview(uri: String): McpHttpClient.ContentPreview {
        return McpContentPreview.fromResources(readResourceContents(uri), MAX_CONTENT_BYTES)
    }

    override fun readResourceContents(uri: String): JSONArray {
        require(uri.isNotBlank() && uri.length <= 4096 && uri.none(Char::isISOControl)) { "MCP 资源地址无效" }
        ensureInitialized()
        val params = JSONObject().put("uri", uri)
        val initial = request("resources/read", params)
        val result = if (protocolVersion == McpProtocolMode.LATEST) McpInputRoundTrips.complete(initial, params, elicitation, recovery, "resources/read") { retry -> request("resources/read", retry) } else initial
        return result.optJSONArray("contents") ?: error("MCP 资源响应缺少 contents")
    }

    override fun getPrompt(
        prompt: McpHttpClient.PromptSummary,
        arguments: Map<String, String>,
    ): McpHttpClient.ContentPreview {
        ensureInitialized()
        val params = JSONObject().put("name", prompt.name).put("arguments", JSONObject(arguments))
        val initial = request("prompts/get", params)
        val result = if (protocolVersion == McpProtocolMode.LATEST) McpInputRoundTrips.complete(initial, params, elicitation, recovery, "prompts/get") { retry -> request("prompts/get", retry) } else initial
        return McpContentPreview.fromPrompt(
            result.optJSONArray("messages") ?: error("MCP 提示模板响应缺少 messages"),
            MAX_CONTENT_BYTES,
        )
    }

    private fun ensureInitialized() {
        if (protocolVersion != null) return
        startProcess()
        if (server.protocolMode == McpProtocolMode.LEGACY || server.lastProtocolVersion in LEGACY_VERSIONS) {
            initializeLegacy(); return
        }
        protocolVersion = McpProtocolMode.LATEST
        if (server.protocolMode == McpProtocolMode.AUTO) {
            // Negotiate with a read before an effectful tool, never by replaying tools/call.
            val probe = try { request("tools/list", JSONObject()) }
            catch (failure: McpJsonRpcException) {
                if (failure.code !in setOf(-32600, -32601, -32000, -32002)) throw failure
                initializeLegacy(); return
            }
            if (!probe.has("resultType")) initializeLegacy()
            else require(probe.optString("resultType") == "complete") { "MCP_DISCOVERY_RESULT_INVALID" }
        }
    }

    private fun initializeLegacy() {
        startProcess()
        protocolVersion = null
        val params = JSONObject().put("protocolVersion", McpProtocolMode.LEGACY)
            .put("capabilities", McpInputRoundTrips.capabilities(elicitation != null))
            .put("clientInfo", JSONObject().put("name", "NEXA").put("version", "1"))
        val result = request("initialize", params)
        val selected = result.optString("protocolVersion")
        require(selected in LEGACY_VERSIONS) { "MCP_PROTOCOL_VERSION_UNSUPPORTED" }
        protocolVersion = selected
        send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
    }

    private fun request(
        method: String,
        params: JSONObject,
    ): JSONObject {
        val id = requestIds.getAndIncrement()
        val decorated = JSONObject(params.toString())
        if (protocolVersion == McpProtocolMode.LATEST) decorated.put("_meta", (decorated.optJSONObject("_meta") ?: JSONObject())
            .put("io.modelcontextprotocol/protocolVersion", McpProtocolMode.LATEST)
            .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "NEXA").put("version", "1"))
            .put("io.modelcontextprotocol/clientCapabilities", McpInputRoundTrips.capabilities(elicitation != null)))
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", decorated),
        )
        var deadline = System.nanoTime() + REQUEST_TIMEOUT_MS * 1_000_000L
        var reverseCount = 0
        while (System.nanoTime() < deadline) {
            if (closed.get()) throw IOException("MCP stdio client is closed")
            io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfStopped()
            if (responses.isEmpty()) readerFailure?.let { throw it }
            val incoming = responses.poll(250, TimeUnit.MILLISECONDS) ?: continue
            queuedBytes.addAndGet(-incoming.bytes)
            val response = incoming.json
            if (response.has("method")) {
                if (response.has("id")) {
                    require(++reverseCount <= 8) { "MCP_REVERSE_REQUEST_LIMIT" }
                    val started = System.nanoTime()
                    send(McpInputRoundTrips.reverseReply(response, elicitation, protocolVersion))
                    deadline += System.nanoTime() - started // User think time is not a transport timeout.
                }
                continue
            }
            if (response.opt("id")?.toString() != id.toString()) continue
            response.optJSONObject("error")?.let { error ->
                throw McpJsonRpcException(
                    error.optInt("code"),
                    error.optString("message").take(200),
                )
            }
            return response.optJSONObject("result") ?: JSONObject()
        }
        throw IOException(
            "MCP stdio 请求超时：$method",
        )
    }

    private fun send(message: JSONObject) {
        val current = process?.takeIf(Process::isAlive) ?: error("MCP stdio 进程未运行")
        synchronized(writeLock) {
            current.outputStream.bufferedWriter(Charsets.UTF_8).let { writer ->
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            }
        }
    }

    private fun readBoundedWireLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        var bytes = 0L
        while (true) {
            val value = reader.read()
            if (value < 0) return line.takeIf { it.isNotEmpty() }?.toString()?.removeSuffix("\r")
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            bytes += when { value < 0x80 -> 1; value < 0x800 -> 2; else -> 3 }
            require(bytes <= 12L * 1024 * 1024) { "MCP 响应超过大小限制" }
            line.append(value.toChar())
        }
    }

    private fun startProcess() {
        if (closed.get()) throw IllegalStateException("MCP stdio client is closed")
        if (!started.compareAndSet(false, true)) return
        require(server.stdioCommand.isNotBlank()) { "请填写 MCP stdio 启动命令" }
        val commandLine = buildList {
            add(server.stdioCommand)
            addAll(server.stdioArguments)
        }.joinToString(" ", transform = ::shellQuote)
        val owned = try {
            val context = if (server.executionTarget == io.github.mangi.eta.data.model.McpExecutionTarget.ANDROID) null
                else io.github.mangi.eta.data.repository.McpServerRepository.appContext()
            McpExecutionProcess.start(context, server, "exec $commandLine", mergeStderr = false)
        } catch (failure: Throwable) {
            started.set(false)
            // The process may include credential arguments; never echo its command or stderr.
            throw IOException(if (failure is IllegalArgumentException) failure.message ?: "MCP 执行位置无效" else "无法启动 MCP stdio 命令", failure)
        }
        ownedProcess = owned
        val running = owned.process
        process = running
        if (closed.get()) { owned.close(); ownedProcess = null; process = null; throw IOException("MCP stdio client is closed") }
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(running.inputStream, Charsets.UTF_8)).use { reader ->
                    while (!closed.get()) {
                        val line = readBoundedWireLine(reader) ?: break
                        if (line.isBlank()) continue
                        runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                            val bytes = line.toByteArray(Charsets.UTF_8).size.toLong()
                            if (queuedBytes.addAndGet(bytes) > 16L * 1024 * 1024 || !responses.offer(QueuedMessage(json, bytes))) {
                                queuedBytes.addAndGet(-bytes)
                                throw IOException("MCP stdio 待读响应超过预算")
                            }
                        }
                    }
                }
                if (!closed.get()) readerFailure = IOException("MCP stdio 响应流已结束")
            }.onFailure { failure -> if (!closed.get()) readerFailure = IOException("MCP stdio 响应流不可读取", failure) }
        }, "eta-mcp-stdio-reader").apply { isDaemon = true; start() }
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(running.errorStream, Charsets.UTF_8)).use { reader ->
                    while (!closed.get()) {
                        val buffer = CharArray(1024)
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(stderr) {
                            val keep = minOf(count, (MAX_STDERR_CHARS - stderr.length).coerceAtLeast(0))
                            if (keep > 0) stderr.append(buffer, 0, keep)
                        }
                    }
                }
            }
        }, "eta-mcp-stdio-stderr").apply { isDaemon = true; start() }
    }

    private fun listResources(): Pair<List<McpHttpClient.ResourceSummary>, Boolean> {
        val values = mutableListOf<McpHttpClient.ResourceSummary>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("resources/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val array = result.optJSONArray("resources") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                values += McpHttpClient.ResourceSummary(
                    uri = uri,
                    name = item.optString("name").take(160).ifBlank { uri.take(160) },
                    description = item.optString("description").take(2_000),
                    mimeType = item.optString("mimeType").take(128),
                )
            }
            cursor = result.optString("nextCursor").takeIf(String::isNotBlank) ?: return values.take(MAX_RESOURCES) to false
        }
        return values.take(MAX_RESOURCES) to true
    }

    private fun listPrompts(): Pair<List<McpHttpClient.PromptSummary>, Boolean> {
        val values = mutableListOf<McpHttpClient.PromptSummary>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("prompts/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val array = result.optJSONArray("prompts") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                val definitions = mutableListOf<McpHttpClient.PromptArgument>()
                item.optJSONArray("arguments")?.let { args ->
                    for (argIndex in 0 until args.length()) {
                        val argument = args.optJSONObject(argIndex) ?: continue
                        val argumentName = argument.optString("name")
                        if (argumentName.isBlank()) continue
                        definitions += McpHttpClient.PromptArgument(
                            name = argumentName,
                            description = argument.optString("description").take(2_000),
                            required = argument.optBoolean("required"),
                        )
                    }
                }
                values += McpHttpClient.PromptSummary(
                    name = name,
                    title = item.optString("title").take(160),
                    description = item.optString("description").take(2_000),
                    arguments = definitions.map { it.name },
                    argumentDefinitions = definitions.take(32),
                )
            }
            cursor = result.optString("nextCursor").takeIf(String::isNotBlank) ?: return values.take(MAX_PROMPTS) to false
        }
        return values.take(MAX_PROMPTS) to true
    }

    private fun parseTool(item: JSONObject?): McpToolDefinition? {
        item ?: return null
        val name = item.optString("name").takeIf { it.matches(TOOL_NAME) } ?: return null
        val schema = item.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
        val annotations = item.optJSONObject("annotations") ?: JSONObject()
        return McpToolDefinition(
            name = name,
            title = item.optString("title").take(160),
            description = item.optString("description").take(2_000),
            inputSchemaJson = schema.toString(),
            readOnlyHint = annotations.nullableBoolean("readOnlyHint"),
            destructiveHint = annotations.nullableBoolean("destructiveHint"),
            idempotentHint = annotations.nullableBoolean("idempotentHint"),
            openWorldHint = annotations.nullableBoolean("openWorldHint"),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        responses.clear()
        ownedProcess?.close()
        ownedProcess = null
        process = null
    }

    private fun stderrSnapshot(): String = synchronized(stderr) {
        stderr.toString().trim().take(400)
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val LEGACY_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", McpProtocolMode.LEGACY)
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val MAX_PAGES = 8
        const val MAX_TOOLS = 128
        const val MAX_RESOURCES = 128
        const val MAX_PROMPTS = 128
        const val MAX_CONTENT_BYTES = 128 * 1024
        const val MAX_STDERR_CHARS = 4_000
        val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
