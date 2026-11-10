package io.github.mangi.eta.fork.a2a

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** A small HTTP/1.1 host for the A2A JSON-RPC binding. */
internal class A2AInboundEngine(
    private val port: Int,
    private val bearerTokenProvider: () -> String,
    private val tasks: A2AInboundTaskHandler,
    private val bindAddress: String = DEFAULT_BIND_ADDRESS,
    private val advertisedEndpointProvider: () -> String? = { null },
    private val runtimeBootstrap: (() -> Unit)? = null,
    private val accessProvider: (String) -> A2AToolAccess = { A2AToolAccess.readOnly(it) },
    private val peerObserver: (String) -> Unit = {},
) {
    constructor(
        context: Context,
        port: Int,
        bearerToken: String,
    ) : this(
        port = port,
        bearerTokenProvider = { bearerToken },
        tasks = PersistentA2AInboundTaskHandler(
            dao = A2ADatabase.get(context.applicationContext).dao(),
            executor = AgentRuntimeA2AInboundExecutor(context.applicationContext),
        ),
        runtimeBootstrap = { A2ARuntimeBootstrap.ensure(context.applicationContext) },
    )

    constructor(
        context: Context,
        port: Int,
        bearerTokenProvider: () -> String,
        bindAddress: String = DEFAULT_BIND_ADDRESS,
        advertisedEndpointProvider: () -> String? = { null },
        accessProvider: (String) -> A2AToolAccess = { A2AToolAccess.readOnly(it) },
        peerObserver: (String) -> Unit = {},
    ) : this(
        port = port,
        bearerTokenProvider = bearerTokenProvider,
        tasks = PersistentA2AInboundTaskHandler(
            dao = A2ADatabase.get(context.applicationContext).dao(),
            executor = AgentRuntimeA2AInboundExecutor(context.applicationContext),
        ),
        bindAddress = bindAddress,
        advertisedEndpointProvider = advertisedEndpointProvider,
        runtimeBootstrap = { A2ARuntimeBootstrap.ensure(context.applicationContext) },
        accessProvider = accessProvider,
        peerObserver = peerObserver,
    )

    /** Test and legacy-compatible constructor for a synchronous message-only endpoint. */
    constructor(
        port: Int,
        bearerTokenProvider: () -> String,
        complete: (prompt: String, history: List<AgentModelClient.ConversationMessage>) -> String,
    ) : this(
        port = port,
        bearerTokenProvider = bearerTokenProvider,
        tasks = LegacyA2AInboundMessageHandler(complete),
    )
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = ThreadPoolExecutor(
        MAX_CLIENTS,
        MAX_CLIENTS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
    ) { runnable ->
        Thread(runnable, "eta-a2a-client").apply {
            isDaemon = true
            setUncaughtExceptionHandler { thread, failure ->
                warn("Uncaught A2A worker failure on ${thread.name}", failure)
            }
        }
    }.apply { allowCoreThreadTimeOut(true) }
    private var serverSocket: ServerSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "A2A inbound server is already running" }
        try {
            runBlocking { tasks.recoverPendingTasks() }
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port), MAX_BACKLOG)
            }
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "eta-a2a-accept").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            running.set(false)
            throw failure
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        tasks.close()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
                continue
            }
            runCatching {
                workers.execute {
                    runCatching { handle(client) }.onFailure { failure ->
                        warn("A2A connection closed after failure", failure)
                    }
                }
            }.onFailure {
                runCatching {
                    val body = errorResponse(null, -32029, "Too many concurrent A2A requests")
                    writeResponse(client, 429, body)
                }.onFailure { client.closeQuietly() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            runtimeBootstrap?.invoke()
            socket.use { connection ->
                connection.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val requestLine = readAsciiLine(input, MAX_LINE_BYTES)
                ?: return@use writeResponse(connection, 400, errorResponse(null, -32600, "Missing request line"))
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3) {
                return@use writeResponse(connection, 400, errorResponse(null, -32600, "Malformed request line"))
            }
            val method = requestParts[0].uppercase()
            val path = requestParts[1].substringBefore('?')
            val headers = readHeaders(input)
                ?: return@use writeResponse(connection, 431, errorResponse(null, -32600, "Headers too large"))
            val body = readBody(input, headers["content-length"]?.toIntOrNull())
                ?: return@use writeResponse(connection, 413, errorResponse(null, -32600, "Request body too large"))
            val origin = headers["origin"]

            if (method == "OPTIONS") {
                return@use writeResponse(connection, 204, "", contentType = null, origin = origin)
            }
            if (method == "GET" && path in AGENT_CARD_PATHS) {
                return@use writeResponse(
                    connection,
                    200,
                    agentCardJson(requestHost = headers["host"]),
                    origin = origin,
                )
            }
            if (method != "POST" || path !in RPC_PATHS) {
                return@use writeResponse(connection, 404, errorResponse(null, -32601, "Not found"), origin = origin)
            }
            val requestedVersion = headers["a2a-version"]?.trim()
            if (requestedVersion != null && requestedVersion != A2A_PROTOCOL_VERSION) {
                return@use writeResponse(
                    connection,
                    400,
                    errorResponse(
                        null,
                        -32003,
                        "Unsupported A2A version: $requestedVersion; expected $A2A_PROTOCOL_VERSION",
                    ),
                    origin = origin,
                )
            }
            if (!isAuthorized(headers["authorization"])) {
                return@use writeResponse(
                    connection,
                    401,
                    errorResponse(null, -32001, "Bearer token required"),
                    origin = origin,
                    extraHeaders = mapOf("WWW-Authenticate" to "Bearer"),
                )
            }
            val peerId = headers["x-a2a-peer-id"]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: headers["x-a2a-client-id"]?.trim()?.takeIf(String::isNotBlank)
                ?: peerIdForCurrentToken()
            val access = accessProvider(peerId)
            peerObserver(peerId)

            val rawBody = body.toString(Charsets.UTF_8)
            val requestId = extractRpcId(rawBody)
            val requestMethod = runCatching {
                canonicalRpcMethod(A2A_JSON.decodeFromString<A2AJsonRpcRequest>(rawBody).method)
            }.getOrNull()
            if (requestMethod == "SendStreamingMessage" || requestMethod == "SubscribeToTask") {
                handleStreamingRpc(connection, rawBody, origin, access)
                return@use
            }
            val response = runCatching { handleRpc(rawBody, access) }.getOrElse { failure ->
                errorResponse(requestId, -32603, failure.message ?: "A2A request failed")
            }
                writeResponse(connection, 200, response, origin = origin)
            }
        } catch (failure: Throwable) {
            // A peer closing early is a connection-local event. It must never escape the worker
            // or be mistaken for a process-wide service failure.
            warn("A2A peer connection ended before a response was delivered", failure)
        }
    }

    private fun handleRpc(raw: String, access: A2AToolAccess): String {
        val request = runCatching { A2A_JSON.decodeFromString<A2AJsonRpcRequest>(raw) }
            .getOrElse { failure -> return errorResponse(null, -32700, failure.message ?: "Invalid JSON-RPC") }
        if (request.jsonrpc != "2.0") return errorResponse(request.id, -32600, "Invalid JSON-RPC version")
        return when (canonicalRpcMethod(request.method)) {
            "SendMessage" -> handleSendMessage(request, access)
            "GetTask" -> handleGetTask(request)
            "CancelTask" -> handleCancelTask(request)
            "SendStreamingMessage", "SubscribeToTask" ->
                errorResponse(request.id, -32601, "Streaming must use text/event-stream")
            else -> errorResponse(request.id, -32601, "Method not found: ${request.method}")
        }
    }

    private fun handleSendMessage(request: A2AJsonRpcRequest, access: A2AToolAccess): String {
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2ASendMessageRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val response = runCatching { runBlocking { tasks.sendMessage(params, access) } }
            .getOrElse { failure -> return errorResponse(request.id, -32603, failure.message ?: "A2A request failed") }
        val result = A2A_JSON.encodeToJsonElement(response).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun handleGetTask(request: A2AJsonRpcRequest): String {
        if (!tasks.persistentTasks) return errorResponse(request.id, -32601, "GetTask is unavailable")
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2AGetTaskRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val task = runCatching { runBlocking { tasks.getTask(params.id, params.historyLength) } }
            .getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Unknown task") }
        val result = A2A_JSON.encodeToJsonElement(task).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun handleCancelTask(request: A2AJsonRpcRequest): String {
        if (!tasks.persistentTasks) return errorResponse(request.id, -32601, "CancelTask is unavailable")
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2ACancelTaskRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val task = runCatching { runBlocking { tasks.cancelTask(params.id) } }
            .getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Unknown task") }
        val result = A2A_JSON.encodeToJsonElement(task).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun handleStreamingRpc(
        socket: Socket,
        raw: String,
        origin: String?,
        access: A2AToolAccess,
    ) {
        val request = runCatching { A2A_JSON.decodeFromString<A2AJsonRpcRequest>(raw) }.getOrElse { failure ->
            writeResponse(socket, 200, errorResponse(null, -32700, failure.message ?: "Invalid JSON-RPC"), origin = origin)
            return
        }
        if (request.jsonrpc != "2.0") {
            writeResponse(socket, 200, errorResponse(request.id, -32600, "Invalid JSON-RPC version"), origin = origin)
            return
        }
        runBlocking {
            val output = socket.getOutputStream().buffered()
            writeEventStreamHeaders(output, origin)
            runCatching {
                when (canonicalRpcMethod(request.method)) {
                    "SendStreamingMessage" -> {
                        val params = A2A_JSON.decodeFromJsonElement<A2ASendMessageRequest>(request.params)
                        val configuration = (params.configuration ?: A2ASendMessageConfiguration())
                            .copy(returnImmediately = true)
                        val response = tasks.sendMessage(params.copy(configuration = configuration), access)
                        val task = response.task
                        if (task != null) {
                            streamTask(output, request.id, task)
                        } else {
                            val result = A2A_JSON.encodeToJsonElement(response).toJsonObject()
                            writeSseEvent(output, A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result)))
                        }
                    }
                    "SubscribeToTask" -> {
                        if (!tasks.persistentTasks) error("SubscribeToTask is unavailable")
                        val params = A2A_JSON.decodeFromJsonElement<A2AGetTaskRequest>(request.params)
                        streamTask(output, request.id, tasks.getTask(params.id, params.historyLength))
                    }
                    else -> error("Unsupported streaming method")
                }
            }.onFailure { failure ->
                val error = errorResponse(request.id, -32603, failure.message ?: "A2A stream failed")
                runCatching { writeSseEvent(output, error) }
            }
            runCatching { output.flush() }
        }
    }

    private suspend fun streamTask(
        output: java.io.BufferedOutputStream,
        requestId: String,
        initialTask: A2ATask,
    ) {
        var task = initialTask
        writeTaskEvent(output, requestId, task)
        var lastSignature = task.streamSignature()
        val deadline = System.nanoTime() + STREAM_TIMEOUT_MS * 1_000_000L
        while (!task.status.state.isTerminal && !task.status.state.isInterrupted) {
            if (System.nanoTime() >= deadline) {
                error("A2A stream timed out while waiting for task completion")
            }
            delay(STREAM_POLL_INTERVAL_MS)
            task = tasks.getTask(task.id, historyLength = null)
            val signature = task.streamSignature()
            if (signature == lastSignature) continue
            lastSignature = signature
            val update = A2ATaskStatusUpdateEvent(
                taskId = task.id,
                contextId = task.contextId ?: task.id,
                status = task.status,
            )
            val result = A2A_JSON.encodeToJsonElement(A2AStreamResponse(statusUpdate = update)).toJsonObject()
            writeSseEvent(output, A2A_JSON.encodeToString(A2AJsonRpcResponse(id = requestId, result = result)))
        }
        if (task != initialTask) writeTaskEvent(output, requestId, task)
    }

    private fun writeTaskEvent(output: java.io.BufferedOutputStream, requestId: String, task: A2ATask) {
        val result = A2A_JSON.encodeToJsonElement(A2AStreamResponse(task = task)).toJsonObject()
        writeSseEvent(output, A2A_JSON.encodeToString(A2AJsonRpcResponse(id = requestId, result = result)))
    }

    private fun A2ATask.streamSignature(): String =
        "${status.state}:${status.message?.messageId}:${artifacts.size}:${history.size}"

    private fun writeEventStreamHeaders(output: java.io.BufferedOutputStream, origin: String?) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Connection: close\r\n")
            append("A2A-Version: $A2A_PROTOCOL_VERSION\r\n")
            append("Access-Control-Allow-Origin: ${origin ?: "*"}\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeSseEvent(output: java.io.BufferedOutputStream, json: String) {
        output.write("data: ".toByteArray(Charsets.UTF_8))
        output.write(json.toByteArray(Charsets.UTF_8))
        output.write("\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun canonicalRpcMethod(method: String): String = when (method) {
        "SendMessage", "message/send" -> "SendMessage"
        "GetTask", "tasks/get" -> "GetTask"
        "CancelTask", "tasks/cancel" -> "CancelTask"
        "SendStreamingMessage", "message/stream" -> "SendStreamingMessage"
        "SubscribeToTask", "tasks/resubscribe" -> "SubscribeToTask"
        else -> method
    }

    private fun agentCardJson(requestHost: String? = null): String {
        val endpoint = advertisedEndpointProvider()
            ?.takeIf(::isValidHttpEndpoint)
            ?: endpointFromHost(requestHost)
            ?: "http://127.0.0.1:$port/a2a"
        val authRequirement = buildJsonObject { put("bearerAuth", kotlinx.serialization.json.JsonArray(emptyList())) }
        val card = A2AAgentCard(
            name = "Nexus on this device",
            description = "A private, token-protected A2A endpoint backed by Nexus's Runtime with persistent tasks.",
            supportedInterfaces = listOf(
                A2AAgentInterface(
                    url = endpoint,
                    protocolBinding = "JSONRPC",
                    protocolVersion = A2A_PROTOCOL_VERSION,
                ),
            ),
            provider = A2AAgentProvider(url = "https://github.com/147258369zhh-lgtm/NEW-NEXUS", organization = "Nexus"),
            version = "1.0",
            capabilities = A2AAgentCapabilities(streaming = true, pushNotifications = false),
            securitySchemes = buildJsonObject {
                putJsonObject("bearerAuth") {
                    put("type", "http")
                    put("scheme", "bearer")
                }
            },
            securityRequirements = listOf(authRequirement),
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
            skills = listOf(
                A2AAgentSkill(
                    id = "eta-model-chat",
                    name = "Nexus model conversation",
                    description = "Submit a text task to Nexus's Runtime, then poll GetTask and CancelTask as needed.",
                    tags = listOf("chat", "assistant", "tasks", "private-network"),
                    examples = listOf("Summarize this incident and list the next checks."),
                    inputModes = listOf("text/plain"),
                    outputModes = listOf("text/plain"),
                ),
            ),
        )
        return A2A_JSON.encodeToString(card)
    }

    private fun errorResponse(id: String?, code: Int, message: String): String =
        A2A_JSON.encodeToString(
            A2AJsonRpcResponse(
                id = id,
                error = A2AJsonRpcError(code = code, message = message),
            ),
        )

    private fun extractRpcId(raw: String): String? = runCatching {
        A2A_JSON.parseToJsonElement(raw).let { element ->
            (element as? JsonObject)?.get("id")?.toString()?.trim('"')
        }
    }.getOrNull()

    private fun isAuthorized(header: String?): Boolean {
        val expected = bearerTokenProvider().trim()
        if (expected.isEmpty()) return false
        val supplied = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim().orEmpty()
        return supplied.isNotEmpty() && java.security.MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun peerIdForCurrentToken(): String =
        A2AAccessPolicyStore.tokenPeerId(bearerTokenProvider().trim())

    private fun isValidHttpEndpoint(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun endpointFromHost(hostHeader: String?): String? = runCatching {
        val host = hostHeader?.trim()?.takeIf(String::isNotBlank) ?: return@runCatching null
        val parsed = java.net.URI("http://$host")
        if (!parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
            java.net.URI("http", null, parsed.host, parsed.port, "/a2a", null, null).toString()
        } else null
    }.getOrNull()

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readAsciiLine(input, MAX_LINE_BYTES) ?: return null
            total += line.length + 2
            if (total > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }

    private fun readBody(input: InputStream, contentLength: Int?): ByteArray? {
        val length = contentLength ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return body
    }

    private fun readAsciiLine(input: InputStream, limit: Int): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= limit) {
            val current = input.read()
            if (current < 0) return null
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = output.toByteArray()
                return bytes.copyOf(bytes.size - 1).toString(Charsets.UTF_8)
            }
            output.write(current)
            previous = current
        }
        return null
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        body: String,
        contentType: String? = A2A_JSON_CONTENT_TYPE,
        origin: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            contentType?.let { append("Content-Type: $it\r\n") }
            append("A2A-Version: $A2A_PROTOCOL_VERSION\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    companion object {
        private const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUED_CLIENTS = 16
        private const val MAX_BACKLOG = 32
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val STREAM_POLL_INTERVAL_MS = 250L
        private const val STREAM_TIMEOUT_MS = 120_000L
        private const val LOG_TAG = "EtaA2AInbound"
        private val AGENT_CARD_PATHS = setOf("/.well-known/agent-card.json", "/.well-known/agent.json")
        private val RPC_PATHS = setOf("/", "/a2a")

        private fun kotlinx.serialization.json.JsonElement.toJsonObject(): JsonObject =
            this as JsonObject

        private fun warn(message: String, failure: Throwable) {
            runCatching { Log.w(LOG_TAG, message, failure) }
        }
    }
}

private fun Socket.closeQuietly() {
    runCatching(::close)
}
