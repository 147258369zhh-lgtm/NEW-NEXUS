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
) : McpClient {
    private val requestIds = AtomicLong(1)
    private val responses = LinkedBlockingQueue<JSONObject>()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val writeLock = Any()
    private val stderr = StringBuilder()
    @Volatile private var process: Process? = null
    @Volatile private var protocolVersion: String? = null

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
        return request(
            "tools/call",
            JSONObject().put("name", tool.name).put("arguments", arguments),
        )
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
        require(uri.isNotBlank()) { "MCP 资源地址无效" }
        val result = request("resources/read", JSONObject().put("uri", uri))
        return McpContentPreview.fromResources(
            result.optJSONArray("contents") ?: error("MCP 资源响应缺少 contents"),
            MAX_CONTENT_BYTES,
        )
    }

    override fun getPrompt(
        prompt: McpHttpClient.PromptSummary,
        arguments: Map<String, String>,
    ): McpHttpClient.ContentPreview {
        val result = request(
            "prompts/get",
            JSONObject().put("name", prompt.name).put("arguments", JSONObject(arguments)),
        )
        return McpContentPreview.fromPrompt(
            result.optJSONArray("messages") ?: error("MCP 提示模板响应缺少 messages"),
            MAX_CONTENT_BYTES,
        )
    }

    private fun ensureInitialized() {
        if (protocolVersion != null) return
        startProcess()
        val params = JSONObject()
            .put("protocolVersion", McpProtocolMode.LATEST)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Nexus").put("version", "1"))
        val result = request("initialize", params)
        protocolVersion = result.optString("protocolVersion").takeIf(String::isNotBlank)
            ?: McpProtocolMode.LATEST
        send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
    }

    private fun request(
        method: String,
        params: JSONObject,
    ): JSONObject {
        val id = requestIds.getAndIncrement()
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params),
        )
        val deadline = System.nanoTime() + REQUEST_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val response = responses.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (response.opt("id") is Number && response.optLong("id") != id) continue
            response.optJSONObject("error")?.let { error ->
                throw McpJsonRpcException(
                    error.optInt("code"),
                    error.optString("message").take(200),
                )
            }
            return response.optJSONObject("result") ?: JSONObject()
        }
        throw IOException(
            "MCP stdio 请求超时：$method" +
                stderrSnapshot().takeIf(String::isNotBlank)?.let { "；$it" }.orEmpty(),
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

    private fun startProcess() {
        if (closed.get()) throw IllegalStateException("MCP stdio client is closed")
        if (!started.compareAndSet(false, true)) return
        require(server.stdioCommand.isNotBlank()) { "请填写 MCP stdio 启动命令" }
        val commandLine = buildList {
            add(server.stdioCommand)
            addAll(server.stdioArguments)
        }.joinToString(" ", transform = ::shellQuote)
        val builder = ProcessBuilder("sh", "-c", commandLine)
            .redirectErrorStream(false)
        server.workingDirectory.takeIf(String::isNotBlank)?.let { directory ->
            val file = File(directory)
            require(file.isDirectory) { "MCP 工作目录不存在" }
            builder.directory(file)
        }
        val running = try {
            builder.start()
        } catch (failure: Throwable) {
            started.set(false)
            throw IOException("无法启动 MCP stdio 命令：${failure.message}", failure)
        }
        process = running
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(running.inputStream, Charsets.UTF_8)).use { reader ->
                    while (!closed.get() && running.isAlive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        runCatching { JSONObject(line) }.getOrNull()?.let(responses::offer)
                    }
                }
            }
        }, "eta-mcp-stdio-reader").apply { isDaemon = true; start() }
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(running.errorStream, Charsets.UTF_8)).use { reader ->
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        synchronized(stderr) {
                            if (stderr.length < MAX_STDERR_CHARS) {
                                stderr.append(line.take(MAX_STDERR_CHARS - stderr.length)).append('\n')
                            }
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
        process?.let { running ->
            runCatching { running.outputStream.close() }
            runCatching { running.inputStream.close() }
            runCatching { running.errorStream.close() }
            runCatching { running.destroy() }
            if (running.isAlive) runCatching { running.destroyForcibly() }
        }
        process = null
    }

    private fun stderrSnapshot(): String = synchronized(stderr) {
        stderr.toString().trim().take(400)
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val MAX_PAGES = 8
        const val MAX_TOOLS = 128
        const val MAX_RESOURCES = 128
        const val MAX_PROMPTS = 128
        const val MAX_CONTENT_BYTES = 128 * 1024
        const val MAX_STDERR_CHARS = 4_000
        val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
