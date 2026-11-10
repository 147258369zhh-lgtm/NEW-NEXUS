package io.github.mangi.eta.agent.connector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

internal enum class ConnectorKind {
    MCP, A2A, OPENAPI, REST, JSON_RPC, GRAPHQL, SSE, WEBSOCKET,
    ANDROID_INTENT, CONTENT_URI, PACKAGE, BROADCAST, MEDIASTORE,
    UNIX_SOCKET, SHELL, SQLITE,
}

internal data class ConnectorCapability(
    val id: String,
    val title: String,
    val description: String,
    val inputSchemaJson: String = """{"type":"object","properties":{}}""",
    val readOnly: Boolean = true,
    val destructive: Boolean = false,
)

internal data class ConnectorDescriptor(
    val id: String,
    val name: String,
    val kind: ConnectorKind,
    val transport: String,
    val capabilities: List<ConnectorCapability>,
    val configured: Boolean = true,
)

internal data class ConnectorHealth(
    val available: Boolean,
    val detail: String,
    val checkedAt: Long = System.currentTimeMillis(),
)

internal data class ConnectorInvocation(
    val capabilityId: String,
    val arguments: JSONObject = JSONObject(),
)

internal data class ConnectorResult(
    val json: JSONObject,
    val text: String? = null,
    val events: List<JSONObject> = emptyList(),
)

internal data class ConnectorEvent(
    val connectorId: String,
    val type: String,
    val payload: JSONObject,
    val timestamp: Long = System.currentTimeMillis(),
)

internal interface ConnectorTransport : AutoCloseable {
    val descriptor: ConnectorDescriptor
    suspend fun probe(): ConnectorHealth
    suspend fun invoke(request: ConnectorInvocation): ConnectorResult
    val events: Flow<ConnectorEvent> get() = emptyFlow()
    override fun close() = Unit
}

internal class ConnectorSecurityManager(
    private val allowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
) {
    private val audit = ArrayDeque<ConnectorEvent>()

    fun allowHost(host: String) {
        allowedHosts += host.lowercase()
    }

    fun authorize(connector: ConnectorDescriptor, request: ConnectorInvocation, confirmed: Boolean) {
        val capability = connector.capabilities.firstOrNull { it.id == request.capabilityId }
            ?: throw SecurityException("连接能力不存在")
        if (capability.destructive && !confirmed) throw SecurityException("该操作需要用户确认")
        record(connector.id, "authorize", JSONObject().put("capability", capability.id))
    }

    fun validateEndpoint(raw: String) {
        val uri = runCatching { URI(raw) }.getOrElse { throw SecurityException("连接地址无效") }
        val host = uri.host?.lowercase() ?: throw SecurityException("连接地址缺少主机名")
        val local = host in setOf("127.0.0.1", "localhost", "::1")
        if (uri.scheme != "https" && !(local && uri.scheme == "http")) {
            throw SecurityException("远程连接必须使用 HTTPS")
        }
        if (allowedHosts.isNotEmpty() && host !in allowedHosts && !local) {
            throw SecurityException("连接主机不在白名单中")
        }
    }

    fun auditEvents(): List<ConnectorEvent> = synchronized(audit) { audit.toList() }

    private fun record(connectorId: String, type: String, payload: JSONObject) {
        synchronized(audit) {
            audit.addLast(ConnectorEvent(connectorId, type, payload))
            while (audit.size > 256) audit.removeFirst()
        }
    }
}

internal class WebSocketConnectorTransport(
    private val endpoint: String,
    private val security: ConnectorSecurityManager,
    private val bearerToken: String? = null,
) : ConnectorTransport {
    private val eventChannel = Channel<ConnectorEvent>(32)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    override val descriptor = ConnectorDescriptor(
        id = "websocket:$endpoint",
        name = "WebSocket",
        kind = ConnectorKind.WEBSOCKET,
        transport = "websocket",
        capabilities = listOf(
            ConnectorCapability("send", "发送 JSON 消息", "发送一条 JSON 消息并等待匹配 id 的响应。", destructive = true),
        ),
    )

    override val events: Flow<ConnectorEvent> = eventChannel.receiveAsFlow()

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        runCatching {
            security.validateEndpoint(endpoint)
            val connected = CompletableDeferred<Boolean>()
            socket?.cancel()
            val request = Request.Builder().url(endpoint).apply {
                bearerToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }.build()
            socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { connected.complete(true) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected.complete(false)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                        message.optString("id").takeIf(String::isNotBlank)?.let { pending.remove(it)?.complete(message) }
                        if (message.optString("id").isBlank()) {
                            eventChannel.trySend(ConnectorEvent(descriptor.id, "message", message))
                        }
                    }
                },
            )
            val ok = withTimeout(10_000) { connected.await() }
            ConnectorHealth(ok, if (ok) "WebSocket 已连接" else "WebSocket 握手失败")
        }.getOrElse { ConnectorHealth(false, it.message ?: "WebSocket 连接失败") }
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        security.authorize(descriptor, request, confirmed = request.arguments.optBoolean("confirmed"))
        val current = socket ?: throw IOException("WebSocket 尚未连接")
        val id = request.arguments.optString("id").ifBlank { UUID.randomUUID().toString() }
        val payload = JSONObject(request.arguments.toString()).apply { remove("confirmed") }.put("id", id)
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        if (!current.send(payload.toString())) {
            pending.remove(id)
            throw IOException("WebSocket 发送失败")
        }
        return try {
            ConnectorResult(json = withTimeout(30_000) { response.await() })
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        socket?.close(1000, "Nexus connector closed")
        socket = null
        eventChannel.close()
        pending.values.forEach { it.cancel() }
        pending.clear()
        client.dispatcher.executorService.shutdown()
    }
}

internal class AndroidLocalConnectorTransport(
    context: Context,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val appContext = context.applicationContext

    override val descriptor = ConnectorDescriptor(
        id = "android:local",
        name = "Android 本地能力",
        kind = ConnectorKind.ANDROID_INTENT,
        transport = "intent/content/package",
        capabilities = listOf(
            ConnectorCapability("package_info", "查询应用包", "按包名查询安装状态与版本。"),
            ConnectorCapability("open_uri", "打开链接", "通过 Android Intent 打开 URI。", destructive = true),
            ConnectorCapability("query_uri", "查询 ContentProvider", "以只读方式查询 content:// 资源。"),
            ConnectorCapability("read_uri", "读取文件", "通过 SAF/ContentResolver 读取单个 URI。"),
        ),
    )

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        ConnectorHealth(true, "PackageManager 与 ContentResolver 可用")
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, confirmed = request.arguments.optBoolean("confirmed"))
        when (request.capabilityId) {
            "package_info" -> {
                val name = request.arguments.optString("package")
                val info = appContext.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
                ConnectorResult(json = JSONObject().put("package", name).put("versionName", info.versionName))
            }
            "open_uri" -> {
                val uri = request.arguments.optString("uri").toUri()
                appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ConnectorResult(json = JSONObject().put("opened", uri.toString()))
            }
            "query_uri" -> {
                val uri = verifyContentUri(request.arguments.optString("uri"))
                val projection = request.arguments.optJSONArray("columns")?.let { array ->
                    Array(array.length()) { array.optString(it) }
                }
                val rows = buildList {
                    appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext() && size < 200) {
                            add(JSONObject().apply {
                                cursor.columnNames.forEachIndexed { index, name -> put(name, cursor.getString(index)) }
                            })
                        }
                    }
                }
                ConnectorResult(json = JSONObject().put("rows", rows))
            }
            "read_uri" -> {
                val uri = verifyContentUri(request.arguments.optString("uri"))
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_LOCAL_BYTES) throw IOException("文件超过读取上限")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: throw IOException("无法读取 URI")
                ConnectorResult(
                    json = JSONObject().put("uri", uri.toString()).put("bytes", bytes.size),
                    text = bytes.toString(Charsets.UTF_8),
                )
            }
            else -> throw IllegalArgumentException("未知的 Android 本地能力")
        }
    }

    private fun verifyContentUri(raw: String): Uri {
        val uri = raw.toUri()
        require(uri.scheme == "content") { "仅允许 content:// URI" }
        require(uri.authority !in FORBIDDEN_AUTHORITIES) { "该 ContentProvider 不允许访问" }
        return uri
    }

    private companion object {
        const val MAX_LOCAL_BYTES = 8 * 1024 * 1024
        val FORBIDDEN_AUTHORITIES = setOf("com.android.settings", "android", "com.android.providers.settings")
    }
}
