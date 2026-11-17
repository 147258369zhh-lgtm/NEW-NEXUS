package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.capability.CapabilityHttpSession
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.fork.a2a.a2aPeerPolicyForManualEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout

internal data class DshCredentials(val token: String = "", val cookie: String? = null, val authority: String? = null, val expiresAt: Long = 0) {
    fun json(): String = JSONObject().put("token", token).put("cookie", cookie ?: JSONObject.NULL).put("authority", authority ?: JSONObject.NULL).put("expires_at", expiresAt).toString()
    companion object {
        fun parse(raw: String?): DshCredentials {
            if (raw.isNullOrBlank()) return DshCredentials()
            if (!raw.startsWith('{')) return DshCredentials(token = raw)
            val obj = JSONObject(raw)
            return DshCredentials(obj.optString("token"), obj.optString("cookie").takeIf { obj.opt("cookie") != JSONObject.NULL && it.isNotBlank() },
                obj.optString("authority").takeIf { obj.opt("authority") != JSONObject.NULL && it.isNotBlank() }, obj.optLong("expires_at"))
        }
    }
}

internal data class DshInvocationReply(val effects: DshEffects, val pendingId: String? = null, val pendingTimeoutMs: Long = 0)

/** Authenticates against the printed DSH launch URL; cookies never leave their exact origin. */
internal class DshHostClient(
    endpoint: String,
    credentials: DshCredentials,
    baseClient: OkHttpClient = AgentHttpClient.client,
    private val persistCredentials: (DshCredentials) -> Unit = {},
    private val available: () -> Boolean = { true },
) : AutoCloseable {
    val endpoint: HttpUrl = normalizeEndpoint(endpoint)
    private val authority = "${this.endpoint.scheme}://${this.endpoint.host}:${this.endpoint.port}"
    private val policy = a2aPeerPolicyForManualEndpoint(this.endpoint.toString())
    internal val client = baseClient.newBuilder().dns(policy.secureDns(baseClient.dns)).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE).cache(null).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val http = CapabilityHttpSession(client)
    private val uploads = CapabilityHttpSession(client.newBuilder().callTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build())
    private val media = CapabilityHttpSession(client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build())
    private val loginLock = Mutex()
    @Volatile private var credential = credentials
    internal fun isAvailable(): Boolean = !http.isClosed && available()

    suspend fun contributions(): DshCatalog = DshProtocol.catalog(get("contributions", capabilities()))
    suspend fun view(pkg: String, id: String, scope: JSONObject): DshView {
        require(DshProtocol.packageId.matches(pkg) && DshProtocol.id.matches(id))
        val query = capabilities().apply { DshProtocol.scope(scope).keys().forEach { put(it, scope.getString(it)) } }
        return DshProtocol.view(get("views/${pkg.split('/').joinToString("/") { encodeSegment(it) }}/${encodeSegment(id)}", query), pkg, id)
    }

    suspend fun invoke(contribution: DshContribution, scope: JSONObject, context: JSONObject, streamId: String?, action: String?, value: Any?, features: Set<String> = emptySet(), userGesture: Boolean = true): DshInvocationReply {
        require(contribution.ready && (contribution.surface != "panels" || action != null))
        require(action == null || DshProtocol.actionId.matches(action))
        require((value?.toString()?.toByteArray()?.size ?: 0) <= 4096)
        authenticate()
        val body = JSONObject().put("v", 1).put("pkg", contribution.pkg).put("id", contribution.id)
            .put("surface", if (contribution.surface == "panels") "panel" else contribution.surface)
            .put("scope", DshProtocol.scope(scope)).put("context", context).put("locale", "zh-CN").put("userGesture", userGesture)
        streamId?.let { body.put("stream", it) }; action?.let { body.put("action", it) }; value?.let { body.put("value", it) }
        require(body.toString().toByteArray().size <= 64 * 1024)
        val request = request(url("invoke")).post(body.toString().toRequestBody(JSON)).build()
        val root = exchange(request, 64 * 1024)
        require(root.optInt("v") == 1)
        val pending = root.optJSONObject("pending")
        val id = pending?.getString("id")?.also { require(it.length in 1..64 && it.none(Char::isISOControl)) }
        val effects = DshProtocol.effects(root.optJSONArray("effects") ?: JSONArray(), features = if (userGesture) features else emptySet())
        return DshInvocationReply(if (userGesture) effects else effects.copy(values = effects.values.filter { it.optString("type") in setOf("patch", "refresh") }), id,
            pending?.optLong("timeoutMs", 30000)?.coerceIn(1000, 60000) ?: 0)
    }

    suspend fun uploadPickedFile(requestId: String, streamId: String, file: DshPickedFile): JSONObject {
        require(DshProtocol.requestToken.matches(requestId) && streamId.length in 1..64)
        require(file.bytes in 0..DshProtocol.FILE_BYTES && file.file.length() == file.bytes)
        authenticate()
        val target = url("files/$requestId/upload").newBuilder().addQueryParameter("stream", streamId).addQueryParameter("name", file.name).build()
        val result = uploads.executeSuspending(request(target).post(file.file.asRequestBody("application/octet-stream".toMediaType())).build()) {
            readJson(it, 4096)
        }
        require(result.optInt("v") == 1)
        val receipt = result.getJSONObject("file")
        require(DshProtocol.requestToken.matches(receipt.optString("handle")) && receipt.optString("access") == "read"
            && receipt.optLong("bytes", -1) == file.bytes) { "文件上传回执不完整" }
        return receipt
    }

    suspend fun completePickedFiles(requestId: String, streamId: String, status: String): DshEffects {
        require(DshProtocol.requestToken.matches(requestId) && streamId.length in 1..64 && status in setOf("selected", "cancelled", "error"))
        authenticate()
        val target = url("files/$requestId/complete").newBuilder().addQueryParameter("stream", streamId).build()
        val result = exchange(request(target).post(JSONObject().put("status", status).toString().toRequestBody(JSON)).build(), 96 * 1024)
        require(result.optInt("v") == 1 && !result.has("pending"))
        return DshProtocol.effects(result.optJSONArray("effects") ?: JSONArray(), fileResult = true)
    }

    internal suspend fun webSocketRequest(): Request {
        authenticate()
        return request(url("stream")).build()
    }

    /** A rejected upgrade may clear only the exact cookie sent to this host's stream route. */
    internal suspend fun rejectWebSocketAuthentication(rejected: Request) = loginLock.withLock {
        require(rejected.url == url("stream")) { "DSH 鉴权拒绝不属于当前主机" }
        check(available()) { "DSH 连接或凭据已变化" }
        if (credential.authority == authority && credential.cookie != null && credential.cookie == rejected.header("Cookie")) {
            val cleared = credential.copy(cookie = null, authority = null, expiresAt = 0)
            // Retain the launch token, but do not let another client load the rejected cookie again.
            persistCredentials(cleared)
            credential = cleared
        }
    }

    suspend fun asset(pkg: String, path: String): ByteArray {
        require(DshProtocol.packageId.matches(pkg) && path.length <= 1024 && path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") && '\\' !in it })
        require(path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif"))
        authenticate()
        val request = request(url("assets/${pkg.split('/').joinToString("/") { encodeSegment(it) }}/${path.split('/').joinToString("/") { encodeSegment(it) }}")).build()
        return http.executeSuspending(request) { response ->
            if (!response.isSuccessful) throw error(response.code)
            val source = response.body.source()
            require(!source.request(1024L * 1024 + 1)) { "DSH 图片超过 1 MiB" }
            source.readByteArray()
        }
    }
    suspend fun downloadMedia(pkg: String, id: String, lease: String, key: String?, directory: File): File {
        require(DshProtocol.packageId.matches(pkg) && DshProtocol.id.matches(id) && DshProtocol.requestToken.matches(lease))
        require(key == null || key.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(directory.isDirectory || directory.mkdirs())
        val partial = AtomicReference<File?>()
        try {
            val file = withTimeout(30_000) {
                authenticate()
                val target = url("media/${pkg.split('/').joinToString("/") { encodeSegment(it) }}/${encodeSegment(id)}").newBuilder().addQueryParameter("lease", lease)
                    .apply { key?.let { addQueryParameter("key", it) } }.build()
                media.executeSuspending(request(target).build()) { response ->
                    if (!response.isSuccessful) throw error(response.code)
                    val mime = response.header("Content-Type")?.substringBefore(';')?.lowercase()?.trim()
                    require(mime in DshProtocol.mediaTypes) { "主机返回了不支持的音频格式" }
                    val length = response.body.contentLength()
                    require(length != 0L && length <= 16L * 1024 * 1024) { "音频文件超过大小限制或为空" }
                    val output = File.createTempFile("dsh-audio-", ".media", directory).also(partial::set)
                    var count = 0L
                    try {
                        response.body.byteStream().use { input -> output.outputStream().use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                check(isAvailable()) { "音频主机连接已变化" }
                                val size = input.read(buffer); if (size < 0) break
                                count += size; require(count <= 16L * 1024 * 1024) { "音频文件超过 16 MiB" }
                                sink.write(buffer, 0, size)
                            }
                        } }
                        require(count > 0 && (length < 0 || count == length)) { "音频文件没有完整下载" }
                        output
                    } catch (failure: Exception) { output.delete(); throw failure }
                }
            }
            partial.set(null); return file
        } finally { partial.getAndSet(null)?.delete() }
    }

    private suspend fun get(path: String, query: JSONObject): JSONObject {
        authenticate()
        val address = url(path).newBuilder().apply { query.keys().forEach { addQueryParameter(it, query.getString(it)) } }.build()
        return try { exchange(request(address).build(), 512 * 1024) }
        catch (failure: DshException) {
            if (failure.code != "DSH_AUTH_REQUIRED") throw failure
            loginLock.withLock { credential = credential.copy(cookie = null, authority = null) }
            authenticate()
            exchange(request(address).build(), 512 * 1024)
        }
    }
    private suspend fun exchange(request: Request, maximum: Int): JSONObject = http.executeSuspending(request) { readJson(it, maximum) }
    private fun readJson(response: okhttp3.Response, maximum: Int): JSONObject {
        if (!response.isSuccessful) throw error(response.code)
        val source = response.body.source()
        require(!source.request(maximum.toLong() + 1)) { "DSH 响应超过大小限制" }
        check(available()) { "DSH 连接或凭据已变化" }
        return DshProtocol.parse(source.readUtf8(), maximum)
    }
    private suspend fun authenticate() = loginLock.withLock {
        check(available()) { "DSH 连接已停用或凭据已变化" }
        if (credential.cookie != null && credential.authority == authority && credential.expiresAt > System.currentTimeMillis()) return@withLock
        if (credential.token.isBlank()) return@withLock
        val login = endpoint.newBuilder().addQueryParameter("token", credential.token).build()
        val minted = http.executeSuspending(Request.Builder().url(login).get().build()) { response ->
            if (response.code !in setOf(302, 303)) throw error(response.code)
            val cookie = Cookie.parseAll(login, response.headers).firstOrNull { it.name.startsWith("dsh-auth-") && it.matches(login) }
                ?: throw DshException("DSH_AUTH_REQUIRED", "主机没有返回有效的 DSH 登录凭据")
            DshCredentials(credential.token, "${cookie.name}=${cookie.value}", authority, cookie.expiresAt)
        }
        persistCredentials(minted)
        credential = minted
    }
    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("Accept", "application/json").apply {
        val current = credential
        if (current.authority == authority && current.cookie != null && current.expiresAt > System.currentTimeMillis()) header("Cookie", current.cookie)
    }
    private fun url(path: String): HttpUrl = endpoint.newBuilder().addEncodedPathSegments("dsh-go/v1/$path").build()
    override fun close() { http.close(); uploads.close(); media.close() }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun normalizeEndpoint(raw: String): HttpUrl {
            val value = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("请填写完整的 DSH 主机网址")
            require(value.username.isEmpty() && value.password.isEmpty() && value.fragment == null && value.encodedPath == "/") { "DSH 主机地址必须指向根目录" }
            require(value.query == null) { "请将启动令牌单独保存" }
            a2aPeerPolicyForManualEndpoint(raw).validateUrl(raw)
            return value
        }
        private fun encodeSegment(value: String) = okhttp3.HttpUrl.Builder().scheme("https").host("local.invalid").addPathSegment(value).build().encodedPath.removePrefix("/")
        private fun capabilities() = JSONObject().put("v", "1").put("app", io.github.mangi.eta.BuildConfig.VERSION_NAME.substringBefore('-'))
            .put("locale", "zh-CN").put("surfaces", DshProtocol.supportedSurfaces.joinToString(",")).put("components", DshProtocol.components.joinToString(","))
            .put("features", DshProtocol.supportedFeatures.joinToString(","))
        private fun error(status: Int): DshException = when (status) {
            401, 403 -> DshException("DSH_AUTH_REQUIRED", "DSH 主机授权失效，请更新启动令牌或检查地址")
            404 -> DshException("DSH_NOT_FOUND", "主机未提供该插件接口，或插件已停用")
            409 -> DshException("DSH_UNAVAILABLE", "该插件当前不可用，请刷新主机状态")
            429 -> DshException("DSH_RATE_LIMITED", "该插件请求过于频繁，请稍后重试")
            else -> DshException("DSH_HTTP_$status", "DSH 主机请求未完成（$status）")
        }
    }
}
