package io.github.mangi.eta.agent.connector

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val ConnectorJsonMedia = "application/json; charset=utf-8".toMediaType()

private fun connectorHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

internal class JsonRpcConnectorTransport(
    private val endpoint: String,
    private val bearerToken: String?,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val client = connectorHttpClient()

    override val descriptor = ConnectorDescriptor(
        id = "jsonrpc:$endpoint",
        name = "JSON-RPC 2.0",
        kind = ConnectorKind.JSON_RPC,
        transport = "http-json-rpc",
        capabilities = listOf(ConnectorCapability("call", "调用 JSON-RPC 方法", "发送标准 JSON-RPC 2.0 请求。", destructive = true)),
    )

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        runCatching {
            security.validateEndpoint(endpoint)
            val response = execute("rpc.discover", JSONObject())
            ConnectorHealth(true, if (response.optJSONObject("error") == null) "JSON-RPC 已响应" else "JSON-RPC 端点可达")
        }.getOrElse { ConnectorHealth(false, it.message ?: "JSON-RPC 连接失败") }
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val method = request.arguments.optString("method")
        require(method.isNotBlank()) { "JSON-RPC 方法名不能为空" }
        val params = request.arguments.optJSONObject("params") ?: JSONObject()
        return ConnectorResult(withContext(Dispatchers.IO) { execute(method, params) })
    }

    private fun execute(method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", UUID.randomUUID().toString())
            .put("method", method)
            .put("params", params)
        val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody(ConnectorJsonMedia))
            .header("Accept", "application/json")
        bearerToken?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "JSON-RPC HTTP ${response.code}" }
            return JSONObject(response.body.string())
        }
    }

    override fun close() = client.dispatcher.executorService.shutdown()
}

internal class GraphQlConnectorTransport(
    private val endpoint: String,
    private val bearerToken: String?,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val client = connectorHttpClient()

    override val descriptor = ConnectorDescriptor(
        id = "graphql:$endpoint",
        name = "GraphQL",
        kind = ConnectorKind.GRAPHQL,
        transport = "http-graphql",
        capabilities = listOf(ConnectorCapability("query", "执行 GraphQL", "发送查询、变更或订阅请求。", destructive = true)),
    )

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        runCatching {
            security.validateEndpoint(endpoint)
            post("query Query { __typename }", JSONObject())
            ConnectorHealth(true, "GraphQL 端点已响应")
        }.getOrElse { ConnectorHealth(false, it.message ?: "GraphQL 连接失败") }
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val query = request.arguments.optString("query")
        require(query.isNotBlank()) { "GraphQL 查询不能为空" }
        val variables = request.arguments.optJSONObject("variables") ?: JSONObject()
        return ConnectorResult(withContext(Dispatchers.IO) { post(query, variables) })
    }

    private fun post(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().put("query", query).put("variables", variables)
        val builder = Request.Builder().url(endpoint).post(payload.toString().toRequestBody(ConnectorJsonMedia))
            .header("Accept", "application/json")
        bearerToken?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "GraphQL HTTP ${response.code}" }
            return JSONObject(response.body.string())
        }
    }

    override fun close() = client.dispatcher.executorService.shutdown()
}

internal class SseConnectorTransport(
    private val endpoint: String,
    private val bearerToken: String?,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val client = connectorHttpClient()

    override val descriptor = ConnectorDescriptor(
        id = "sse:$endpoint",
        name = "SSE",
        kind = ConnectorKind.SSE,
        transport = "text/event-stream",
        capabilities = listOf(ConnectorCapability("listen", "读取 SSE 事件", "读取有限数量的服务端事件后关闭连接。")),
    )

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        runCatching {
            security.validateEndpoint(endpoint)
            val request = request(maxEvents = 1)
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "SSE HTTP ${response.code}" }
                require(response.header("Content-Type").orEmpty().startsWith("text/event-stream")) { "端点未返回 text/event-stream" }
            }
            ConnectorHealth(true, "SSE 端点可连接")
        }.getOrElse { ConnectorHealth(false, it.message ?: "SSE 连接失败") }
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val max = request.arguments.optInt("max_events", 20).coerceIn(1, 200)
        val events = withContext(Dispatchers.IO) { readEvents(request(max)) }
        return ConnectorResult(json = JSONObject().put("events", events), events = events)
    }

    private fun request(maxEvents: Int): Request {
        val builder = Request.Builder().url(endpoint).get().header("Accept", "text/event-stream")
        bearerToken?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("X-Nexus-Max-Events", maxEvents.toString())
        return builder.build()
    }

    private fun readEvents(request: Request): List<JSONObject> {
        val events = mutableListOf<JSONObject>()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "SSE HTTP ${response.code}" }
            val source = response.body.source()
            val data = StringBuilder()
            while (!source.exhausted() && events.size < 200) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) {
                    if (data.isNotEmpty()) {
                        events += runCatching { JSONObject(data.toString()) }
                            .getOrElse { JSONObject().put("data", data.toString()) }
                        data.clear()
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        return events
    }

    override fun close() = client.dispatcher.executorService.shutdown()
}

internal class UnixSocketConnectorTransport(
    private val socketPath: String,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    override val descriptor = ConnectorDescriptor(
        id = "unix:$socketPath",
        name = "Unix Domain Socket",
        kind = ConnectorKind.UNIX_SOCKET,
        transport = "local-socket",
        capabilities = listOf(ConnectorCapability("send", "发送 JSON", "向 Unix Domain Socket 发送一行 JSON 并读取响应。", destructive = true)),
    )

    override suspend fun probe(): ConnectorHealth = runCatching {
        withContext(Dispatchers.IO) { LocalSocket().use { socket -> socket.connect(LocalSocketAddress(socketPath)) } }
        ConnectorHealth(true, "Unix Socket 可连接")
    }.getOrElse { ConnectorHealth(false, it.message ?: "Unix Socket 连接失败") }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val payload = request.arguments.opt("payload") ?: JSONObject()
        return withContext(Dispatchers.IO) {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath))
                socket.soTimeout = 15_000
                socket.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.newLine()
                    writer.flush()
                }
                val text = socket.inputStream.bufferedReader(Charsets.UTF_8).readText().take(256 * 1024)
                ConnectorResult(
                    json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("response", text) },
                    text = text,
                )
            }
        }
    }
}

internal class ShellConnectorTransport(
    context: Context,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val appContext = context.applicationContext

    override val descriptor = ConnectorDescriptor(
        id = "shell:local",
        name = "Shell / STDIO",
        kind = ConnectorKind.SHELL,
        transport = "sh",
        capabilities = listOf(ConnectorCapability("exec", "执行命令", "在工作区运行 shell 命令并返回有界输出。", destructive = true)),
    )

    override suspend fun probe(): ConnectorHealth = ConnectorHealth(true, "系统 shell 可用")

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val command = request.arguments.optString("command")
        require(command.isNotBlank()) { "命令不能为空" }
        val timeout = request.arguments.optLong("timeout_ms", 30_000L).coerceIn(1_000L, 120_000L)
        val process = ProcessBuilder("sh", "-c", command)
            .directory(File(appContext.filesDir, "workspace").apply { mkdirs() })
            .redirectErrorStream(true)
            .start()
        val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "eta-connector-shell-reader").apply { isDaemon = true } }
        try {
            val bytes = reader.submit<ByteArray> {
                ByteArrayOutputStream().use { output ->
                    val stream = process.inputStream
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 512 * 1024) throw IOException("命令输出超过 512 KiB")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }.get(timeout, TimeUnit.MILLISECONDS)
            val exited = process.waitFor(1, TimeUnit.SECONDS)
            val exit = if (exited) process.exitValue() else null
            val text = bytes.toString(Charsets.UTF_8)
            ConnectorResult(JSONObject().put("exit_code", exit ?: JSONObject.NULL).put("output", text), text)
        } catch (_: TimeoutException) {
            process.destroyForcibly()
            throw IOException("命令执行超时")
        } finally {
            reader.shutdownNow()
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal class BroadcastConnectorTransport(
    context: Context,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val appContext = context.applicationContext
    override val descriptor = ConnectorDescriptor(
        id = "broadcast:local",
        name = "Broadcast",
        kind = ConnectorKind.BROADCAST,
        transport = "android-broadcast",
        capabilities = listOf(ConnectorCapability("send", "发送广播", "按显式 action 和目标包发送广播。", destructive = true)),
    )

    override suspend fun probe(): ConnectorHealth = ConnectorHealth(true, "BroadcastManager 可用")

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        val action = request.arguments.optString("action")
        require(action.isNotBlank()) { "广播 action 不能为空" }
        val intent = Intent(action)
        request.arguments.optString("package").takeIf(String::isNotBlank)?.let { intent.setPackage(it) }
        val extras = request.arguments.optJSONObject("extras") ?: JSONObject()
        extras.keys().forEach { key -> intent.putExtra(key, extras.opt(key)?.toString()) }
        appContext.sendBroadcast(intent)
        ConnectorResult(JSONObject().put("sent", action))
    }
}

internal class MediaStoreConnectorTransport(
    context: Context,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val appContext = context.applicationContext
    override val descriptor = ConnectorDescriptor(
        id = "media:local",
        name = "MediaStore",
        kind = ConnectorKind.MEDIASTORE,
        transport = "content-provider",
        capabilities = listOf(ConnectorCapability("query", "查询媒体", "查询图片、视频或音频的 MediaStore 元数据。")),
    )

    override suspend fun probe(): ConnectorHealth = ConnectorHealth(true, "MediaStore 可用")

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, false)
        val type = request.arguments.optString("type", "images")
        val collection = when (type) {
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val limit = request.arguments.optInt("limit", 100).coerceIn(1, 500)
        val rows = mutableListOf<JSONObject>()
        appContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE),
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                rows += JSONObject()
                    .put("id", cursor.getLong(0))
                    .put("name", cursor.getString(1))
                    .put("mimeType", cursor.getString(2))
                    .put("size", cursor.getLong(3))
            }
        }
        ConnectorResult(JSONObject().put("items", rows))
    }
}

internal class SqliteConnectorTransport(
    context: Context,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val databaseFile = context.getDatabasePath("eta.db")
    override val descriptor = ConnectorDescriptor(
        id = "sqlite:eta",
        name = "Nexus SQLite",
        kind = ConnectorKind.SQLITE,
        transport = "sqlite-readonly",
        capabilities = listOf(ConnectorCapability("query", "只读查询", "对 Nexus 本地数据库执行受限 SELECT 查询。")),
    )

    override suspend fun probe(): ConnectorHealth = ConnectorHealth(databaseFile.isFile, if (databaseFile.isFile) "数据库可读" else "数据库尚未创建")

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, false)
        val sql = request.arguments.optString("sql").trim()
        require(sql.startsWith("SELECT ", ignoreCase = true) || sql.startsWith("WITH ", ignoreCase = true)) { "只允许只读查询" }
        require(';' !in sql && !Regex("(?i)\\b(ATTACH|PRAGMA|DROP|DELETE|UPDATE|INSERT|REPLACE|ALTER)\\b").containsMatchIn(sql)) {
            "查询包含不允许的语句"
        }
        val rows = mutableListOf<JSONObject>()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                val max = request.arguments.optInt("limit", 200).coerceIn(1, 1_000)
                while (cursor.moveToNext() && rows.size < max) {
                    rows += JSONObject().apply {
                        cursor.columnNames.forEachIndexed { index, name -> put(name, cursor.getString(index)) }
                    }
                }
            }
        }
        ConnectorResult(JSONObject().put("rows", rows))
    }
}
