package io.github.mangi.eta.agent.capability

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.McpCredentialsUnavailable
import io.github.mangi.eta.data.repository.McpSecretStore
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class ApiConnectorService(
    context: Context,
    baseClient: OkHttpClient = AgentHttpClient.client,
    connectorStore: ApiConnectorStore? = null,
    secretStore: McpSecretStore? = null,
) {
    private val appContext = context.applicationContext
    private val store = connectorStore ?: ApiConnectorStore(appContext)
    private val secrets = secretStore ?: McpSecretStore(appContext)
    private val oauthClient = ApiOAuth2Client(appContext, baseClient, secretStore = secrets)
    private val httpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun connectors(): List<ApiConnectorDefinition> = store.load()

    suspend fun importOpenApi(
        requestedName: String,
        specUrl: String,
        authType: String,
        authHeaderName: String,
        credential: String,
        oauth: ApiOAuth2Configuration? = null,
        oauthClientSecret: String = "",
    ): ApiConnectorImportResult = withContext(Dispatchers.IO) {
        val sourceUrl = validateSpecUrl(specUrl)
        val text = fetchSpecification(sourceUrl)
        val id = "api-" + UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val imported = OpenApiConnectorParser.parse(
            specification = text,
            sourceUrl = sourceUrl,
            requestedName = requestedName,
            connectorId = id,
            authType = authType,
            authHeaderName = authHeaderName,
        )
        synchronized(MUTATION_LOCK) {
            val connectorId = imported.connector.id
            val previousConnectors = store.load()
            val previousSecret = secrets.encryptedSnapshot(apiConnectorSecretKey(connectorId))
            try {
                if (imported.connector.authType == ApiConnectorAuthType.OAUTH2) {
                    val configuration = oauth
                        ?: throw ApiConnectorException("OAUTH_CONFIG_MISSING", "OAuth 授权配置缺失")
                    if (configuration.usesClientSecret && oauthClientSecret.isBlank()) {
                        throw ApiConnectorException("OAUTH_CLIENT_SECRET_MISSING", "请填写 OAuth Client Secret")
                    }
                    val normalized = store.validate(imported.connector.copy(oauth = configuration))
                    store.upsert(normalized)
                    oauthClient.setClientSecret(connectorId, oauthClientSecret)
                } else {
                    updateCredential(imported.connector, credential, previousSecret)
                    store.upsert(imported.connector)
                }
            } catch (failure: Throwable) {
                runCatching { secrets.restoreEncryptedSnapshot(apiConnectorSecretKey(connectorId), previousSecret) }
                runCatching { store.save(previousConnectors) }
                throw failure
            }
            imported
        }
    }

    fun setEnabled(connectorId: String, enabled: Boolean): List<ApiConnectorDefinition> = synchronized(MUTATION_LOCK) {
        val current = store.load().firstOrNull { it.id == connectorId }
            ?: throw ApiConnectorException("CONNECTOR_NOT_FOUND", "API 连接不存在")
        store.upsert(current.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    fun setActionEnabled(
        connectorId: String,
        actionName: String,
        enabled: Boolean,
    ): List<ApiConnectorDefinition> = synchronized(MUTATION_LOCK) {
        val current = store.load().firstOrNull { it.id == connectorId }
            ?: throw ApiConnectorException("CONNECTOR_NOT_FOUND", "API 连接不存在")
        var matched = false
        val actions = current.actions.map { action ->
            if (action.name == actionName) {
                matched = true
                action.copy(enabled = enabled)
            } else {
                action
            }
        }
        if (!matched) throw ApiConnectorException("ACTION_NOT_FOUND", "API 操作不存在")
        store.upsert(
            current.copy(actions = actions, updatedAt = System.currentTimeMillis()),
        )
    }

    fun delete(connectorId: String): List<ApiConnectorDefinition> {
        if (store.load().none { it.id == connectorId }) {
            throw ApiConnectorException("CONNECTOR_NOT_FOUND", "API 连接不存在")
        }
        return deleteMany(listOf(connectorId))
    }

    /**
     * Removes metadata and encrypted credentials as one best-effort operation.
     * Missing ids are already removed, so plugin cleanup can be retried safely.
     */
    fun deleteMany(connectorIds: Collection<String>): List<ApiConnectorDefinition> = synchronized(MUTATION_LOCK) {
        val ids = connectorIds.map(String::trim).filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return@synchronized store.load()
        val current = store.load()
        val existing = current.filter { it.id in ids }
        val snapshots = existing.associate { connector ->
            val key = apiConnectorSecretKey(connector.id)
            key to secrets.encryptedSnapshot(key)
        }
        try {
            existing.forEach { connector ->
                if (connector.authType == ApiConnectorAuthType.OAUTH2) {
                    oauthClient.clear(connector.id)
                } else {
                    secrets.clear(apiConnectorSecretKey(connector.id))
                }
            }
            store.save(current.filterNot { it.id in ids })
        } catch (failure: Throwable) {
            snapshots.forEach { (key, snapshot) ->
                runCatching { secrets.restoreEncryptedSnapshot(key, snapshot) }
            }
            throw failure
        }
    }

    /** Removes local credentials while keeping the connector and its reviewed tool boundary. */
    fun disconnect(connectorId: String): List<ApiConnectorDefinition> = synchronized(MUTATION_LOCK) {
        val connector = store.load().firstOrNull { it.id == connectorId }
            ?: throw ApiConnectorException("CONNECTOR_NOT_FOUND", "API 连接不存在")
        when (connector.authType) {
            ApiConnectorAuthType.NONE -> store.load()
            ApiConnectorAuthType.OAUTH2 -> {
                oauthClient.clear(connector.id)
                store.load()
            }
            else -> {
                val key = apiConnectorSecretKey(connector.id)
                val previous = secrets.encryptedSnapshot(key)
                try {
                    secrets.clear(key)
                } catch (failure: Throwable) {
                    runCatching { secrets.restoreEncryptedSnapshot(key, previous) }
                    throw failure
                }
                store.load()
            }
        }
    }

    fun credentialAvailable(connector: ApiConnectorDefinition): Boolean = when (connector.authType) {
        ApiConnectorAuthType.NONE -> true
        ApiConnectorAuthType.OAUTH2 -> runCatching {
            oauthClient.credentialAvailable(connector.id)
        }.getOrDefault(false)
        else -> runCatching {
            !secrets.bearerToken(apiConnectorSecretKey(connector.id)).isNullOrBlank()
        }.getOrDefault(false)
    }

    fun beginAuthorization(connectorId: String): ApiOAuthAuthorizationRequest =
        oauthClient.beginAuthorization(connectorId)

    fun completeAuthorization(callback: Uri?): ApiOAuthCallbackResult =
        oauthClient.completeAuthorization(callback)

    fun updateCredential(
        connector: ApiConnectorDefinition,
        credential: String,
    ) {
        if (connector.authType == ApiConnectorAuthType.OAUTH2) {
            throw ApiConnectorException("OAUTH_AUTHORIZATION_REQUIRED", "OAuth 连接请使用重新授权")
        }
        synchronized(MUTATION_LOCK) {
            val key = apiConnectorSecretKey(connector.id)
            val previousSecret = secrets.encryptedSnapshot(key)
            try {
                updateCredential(connector, credential, previousSecret)
            } catch (failure: Throwable) {
                runCatching { secrets.restoreEncryptedSnapshot(key, previousSecret) }
                throw failure
            }
        }
    }

    private fun updateCredential(
        connector: ApiConnectorDefinition,
        credential: String,
        previousSecret: String?,
    ) {
        val key = apiConnectorSecretKey(connector.id)
        require(connector.authType != ApiConnectorAuthType.OAUTH2) {
            "OAuth 连接不能直接写入访问令牌"
        }
        if (connector.authType == ApiConnectorAuthType.NONE) {
            secrets.clear(key)
        } else {
            require(credential.isNotBlank()) { "该连接需要填写访问令牌" }
            secrets.setBearerToken(key, credential)
        }
    }

    private fun fetchSpecification(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/vnd.oai.openapi+json")
            .header("User-Agent", "Nexus-Capability-Center/1")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw ApiConnectorException("SPEC_REDIRECT", "OpenAPI 文档地址发生重定向，已拒绝")
            }
            if (!response.isSuccessful) {
                throw ApiConnectorException(
                    "SPEC_HTTP_ERROR",
                    "OpenAPI 文档读取失败（HTTP ${response.code}）",
                )
            }
            if (response.body.contentLength() > MAX_SPEC_BYTES) {
                throw ApiConnectorException("SPEC_TOO_LARGE", "OpenAPI 文档超过 2 MiB 上限")
            }
            val cache = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SPEC_BYTES) {
                        throw ApiConnectorException("SPEC_TOO_LARGE", "OpenAPI 文档超过 2 MiB 上限")
                    }
                    cache.write(buffer, 0, count)
                }
            }
            return cache.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun validateSpecUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw ApiConnectorException("INVALID_SPEC_URL", "OpenAPI 文档地址无效") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw ApiConnectorException("INVALID_SPEC_URL", "OpenAPI 文档必须使用 HTTPS")
        }
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
            throw ApiConnectorException("INVALID_SPEC_URL", "OpenAPI 文档地址包含不允许的凭据或端口")
        }
        return uri.toASCIIString()
    }

    internal companion object {
        val MUTATION_LOCK = Any()
        const val MAX_SPEC_BYTES = 2 * 1024 * 1024
    }
}

internal data class ApiConnectorRunTool(
    val modelName: String,
    val connector: ApiConnectorDefinition,
    val action: ApiConnectorAction,
)

internal class ApiConnectorSnapshot(
    val tools: List<ApiConnectorRunTool>,
) {
    private val byModelName = tools.associateBy(ApiConnectorRunTool::modelName)

    fun resolve(modelName: String): ApiConnectorRunTool? = byModelName[modelName]

    fun bindings(context: Context): List<RunHostBinding> = tools.groupBy { it.connector.id }.map { (id, rows) ->
        val snapshot = ApiConnectorSnapshot(rows)
        val schemas = JSONArray().also(snapshot::appendModelTools)
        val descriptors = rows.map { tool ->
            val effect = if (tool.action.method == ApiHttpMethod.GET && !tool.action.destructive) io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ
                else io.github.zhh_lgtm.nexus.kernel.api.EffectClass.HIGH_RISK
            io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor(tool.modelName, "api", "${tool.connector.name}: ${tool.action.description}".take(180),
                tool.action.description.ifBlank { "API ${tool.action.name}" }, "nexus-api://${tool.modelName}/input", effectClass = effect,
                replayPolicy = NexusToolEffectClassifier.replayPolicy(effect), timeoutMs = 90_000,
                hostKind = io.github.zhh_lgtm.nexus.kernel.api.HostKind.CONNECTOR)
        }
        RunHostBinding(RunHostIdentity.of("api", id), descriptors, schemas, ApiToolExecutor(context, snapshot), available = { name ->
            val frozen = snapshot.resolve(name)!!
            val current = ApiConnectorStore(context).load().firstOrNull { it.id == id }
            current != null && current.enabled && current.baseUrl == frozen.connector.baseUrl &&
                current.authType == frozen.connector.authType && current.authHeaderName == frozen.connector.authHeaderName &&
                current.oauth == frozen.connector.oauth && current.activeActions.any { it == frozen.action }
        }, displayName = rows.first().connector.name)
    }

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val risk = if (tool.action.destructive) "可能修改外部数据。" else ""
            val description = buildString {
                append("REST API 连接「").append(tool.connector.name).append("」提供的操作。")
                if (tool.action.description.isNotBlank()) append(tool.action.description).append('。')
                append(risk)
            }.take(1_200)
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.action.inputSchemaJson)),
                    ),
            )
        }
    }

    companion object {
        val EMPTY = ApiConnectorSnapshot(emptyList())

        fun load(context: Context): ApiConnectorSnapshot {
            val runners = mutableListOf<ApiConnectorRunTool>()
            val service = ApiConnectorService(context)
            try {
                service.connectors()
                    .filter { it.enabled && service.credentialAvailable(it) }
                    .forEach { connector ->
                        connector.activeActions.forEach { action ->
                            if (runners.size >= MAX_RUN_TOOLS) return@forEach
                            runners += ApiConnectorRunTool(
                                modelName = modelToolName(connector.id, action.name),
                                connector = connector,
                                action = action,
                            )
                        }
                    }
            } catch (failure: ApiConnectorException) {
                if (failure.code != "CONNECTOR_CONFIG_CORRUPT") throw failure
                return EMPTY
            }
            return ApiConnectorSnapshot(runners)
        }

        internal fun modelToolName(connectorId: String, actionName: String): String {
            val connectorPart = connectorId.filter(Char::isLetterOrDigit).takeLast(6).ifBlank { "api" }
            val normalized = actionName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "call" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$connectorId\u0000$actionName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "api_${connectorPart}_${normalized}_$digest"
        }

        private const val MAX_RUN_TOOLS = 64
    }
}

internal class ApiToolExecutor(
    context: Context,
    private val snapshot: ApiConnectorSnapshot,
    baseClient: OkHttpClient = AgentHttpClient.client,
    oauthAccessTokenProvider: ((ApiConnectorDefinition, Boolean) -> String)? = null,
    secretStore: McpSecretStore? = null,
) : CapabilityToolProvider {
    private val appContext = context.applicationContext
    private val secrets = secretStore ?: McpSecretStore(appContext)
    private val oauthClient = ApiOAuth2Client(appContext, baseClient)
    private val oauthAccessToken = oauthAccessTokenProvider ?: { connector, forceRefresh ->
        oauthClient.accessToken(connector, forceRefresh)
    }
    private val hasCustomOauthProvider = oauthAccessTokenProvider != null
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val http = CapabilityHttpSession(client)

    override fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (http.isClosed) return failure("API_EXECUTOR_CLOSED", "该任务的外部连接已关闭")
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            "UNKNOWN_API_TOOL",
            "API 操作未在本次运行中启用",
        )
        // Tool descriptors are frozen for one run, but disconnect is an immediate owner action.
        // Recheck durable connector state and the credential before every outbound call so a
        // model cannot continue using a stale run snapshot after the owner revoked access.
        if (!connectorStillAuthorized(tool)) return failure("API_CREDENTIAL_REVOKED", "API 连接已断开或授权已撤销")
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "API 操作参数不是 JSON object") }
        return runCatching { call(tool, arguments) }
            .getOrElse { error ->
                val safe = error as? ApiConnectorException
                failure(if (http.isClosed) "API_EXECUTOR_CLOSED" else safe?.code ?: "API_CALL_FAILED",
                    if (http.isClosed) "该任务的外部连接已关闭" else safe?.message ?: "API 调用失败")
            }
    }

    override fun close() { http.close(); oauthClient.close() }

    private fun connectorStillAuthorized(tool: ApiConnectorRunTool): Boolean = runCatching {
        val current = ApiConnectorStore(appContext).load().firstOrNull { it.id == tool.connector.id }
            ?: return@runCatching false
        current.enabled && current.baseUrl == tool.connector.baseUrl &&
            current.authType == tool.connector.authType && current.authHeaderName == tool.connector.authHeaderName &&
            current.oauth == tool.connector.oauth && current.activeActions.any { it == tool.action } &&
            when (current.authType) {
                ApiConnectorAuthType.NONE -> true
                ApiConnectorAuthType.OAUTH2 -> hasCustomOauthProvider || oauthClient.credentialAvailable(current.id)
                else -> !secrets.bearerToken(apiConnectorSecretKey(current.id)).isNullOrBlank()
            }
    }.getOrDefault(false)

    private fun call(
        tool: ApiConnectorRunTool,
        arguments: JSONObject,
    ): AgentModelClient.ToolResult {
        val action = tool.action
        val schema = JSONObject(action.inputSchemaJson)
        val allowedProperties = schema.optJSONObject("properties")
            ?.keys()
            ?.asSequence()
            ?.toSet()
            .orEmpty()
        val required = schema.optJSONArray("required").stringSet()
        val unexpected = arguments.keys().asSequence().filterNot { it in allowedProperties }.toList()
        if (unexpected.isNotEmpty()) {
            return failure("INVALID_ARGUMENT", "包含未声明的参数：${unexpected.joinToString()}")
        }
        val missing = required.filter { !arguments.has(it) || arguments.opt(it) == JSONObject.NULL }
        if (missing.isNotEmpty()) {
            return failure("MISSING_ARGUMENT", "缺少必填参数：${missing.joinToString()}")
        }
        var path = action.path
        action.pathParameters.forEach { name ->
            val value = argumentText(arguments.opt(name))
                ?: throw ApiConnectorException("MISSING_ARGUMENT", "路径参数 $name 缺失")
            path = path.replace("{$name}", encodePathSegment(value))
        }
        if (PATH_PARAMETER.containsMatchIn(path)) {
            throw ApiConnectorException("INVALID_PATH", "API 路径仍有未替换参数")
        }
        val fullUrl = tool.connector.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val url = fullUrl.toHttpUrlOrNull()
            ?: throw ApiConnectorException("INVALID_PATH", "API 请求地址无效")
        if (!url.host.equals(tool.connector.baseUrl.toHttpUrlOrNull()?.host, ignoreCase = true)) {
            throw ApiConnectorException("HOST_CHANGED", "API 请求不能切换到其他主机")
        }
        val builder = url.newBuilder()
        action.queryParameters.forEach { name ->
            argumentText(arguments.opt(name))?.let { builder.addQueryParameter(name, it) }
        }
        val requestBody = JSONObject()
        action.bodyParameters.forEach { name ->
            if (arguments.has(name) && arguments.opt(name) != JSONObject.NULL) {
                requestBody.put(name, arguments.opt(name))
            }
        }
        val bodyJson = if (action.bodyParameters.isNotEmpty()) requestBody.toString() else ""
        if (action.requestBodyRequired && action.bodyParameters.isNotEmpty() && requestBody.length() == 0) {
            return failure("MISSING_ARGUMENT", "该 API 操作需要请求体参数")
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json, text/plain;q=0.8")
            .header("User-Agent", "Nexus-Capability-Center/1")
            .apply {
                action.headerParameters.forEach { name ->
                    argumentText(arguments.opt(name))?.let { header(name, it) }
                }
                when (tool.connector.authType) {
                    ApiConnectorAuthType.NONE -> Unit
                    ApiConnectorAuthType.BEARER -> header(
                        "Authorization",
                        "Bearer " + credential(tool.connector),
                    )
                    ApiConnectorAuthType.HEADER -> header(
                        tool.connector.authHeaderName,
                        credential(tool.connector),
                    )
                    ApiConnectorAuthType.OAUTH2 -> header(
                        "Authorization",
                        "Bearer " + oauthAccessToken(tool.connector, false),
                    )
                }
                val media = "application/json; charset=utf-8".toMediaType()
                when (action.method) {
                    ApiHttpMethod.GET -> Unit
                    ApiHttpMethod.DELETE -> method("DELETE", bodyJson.takeIf(String::isNotEmpty)?.toRequestBody(media))
                    ApiHttpMethod.POST -> method("POST", bodyJson.toRequestBody(media))
                    ApiHttpMethod.PUT -> method("PUT", bodyJson.toRequestBody(media))
                    ApiHttpMethod.PATCH -> method("PATCH", bodyJson.toRequestBody(media))
                    else -> throw ApiConnectorException("UNSUPPORTED_METHOD", "不支持的 HTTP 方法")
                }
            }
            .build()
        if (!connectorStillAuthorized(tool)) return failure("API_CREDENTIAL_REVOKED", "API 连接已断开或授权已撤销")
        return http.execute(request) { response ->
            if (response.isRedirect) {
                failure("API_REDIRECT", "API 返回重定向，为避免凭据泄漏已拒绝")
            } else if (response.code == 401 && tool.connector.authType == ApiConnectorAuthType.OAUTH2) {
                if (!connectorStillAuthorized(tool)) return@execute failure("API_CREDENTIAL_REVOKED", "API 连接已断开或授权已撤销")
                val retried = request.newBuilder()
                    .header("Authorization", "Bearer " + oauthAccessToken(tool.connector, true))
                    .build()
                if (!connectorStillAuthorized(tool)) return@execute failure("API_CREDENTIAL_REVOKED", "API 连接已断开或授权已撤销")
                http.execute(retried) { retryResponse -> responseResult(tool, action, retryResponse) }
            } else responseResult(tool, action, response)
        }
    }

    private fun responseResult(
        tool: ApiConnectorRunTool,
        action: ApiConnectorAction,
        response: okhttp3.Response,
    ): AgentModelClient.ToolResult {
        if (response.isRedirect) {
            return failure("API_REDIRECT", "API 返回重定向，为避免凭据泄漏已拒绝")
        }
        val body = readResponseBody(response)
        val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
        val disposition = response.header("Content-Disposition").orEmpty().take(1024)
        val fileResponse = response.isSuccessful && body.bytes.isNotEmpty() && (disposition.startsWith("attachment", true) ||
            mime.isNotBlank() && !mime.startsWith("text/") && !mime.endsWith("json") && !mime.endsWith("xml") &&
                !mime.endsWith("javascript") && !mime.endsWith("x-www-form-urlencoded"))
        val payload = JSONObject()
            .put("ok", response.isSuccessful)
            .put("connector", tool.connector.name)
            .put("operation", action.name)
            .put("method", action.method)
            .put("http_status", response.code)
        val parsed = if (fileResponse) null else runCatching { JSONObject(body.text) }.getOrNull()
            ?: runCatching { JSONArray(body.text) }.getOrNull()
        val attachment = if (fileResponse) {
            val fileName = Regex("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(disposition)
                ?.let { it.groupValues[1].ifBlank { it.groupValues[2].trim() } }
                ?: "response." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin")
            payload.put("content", "API 返回了文件").put("media_type", mime).put("size_bytes", body.bytes.size)
            listOf(AgentModelClient.ToolFile(fileName, mime.ifBlank { "application/octet-stream" }, body.bytes))
        } else {
            if (parsed != null) payload.put("content", parsed) else payload.put("content", body.text)
            emptyList()
        }
        if (!response.isSuccessful) payload.put("code", "API_HTTP_ERROR")
        return AgentModelClient.ToolResult(content = payload.toString(), sensitive = true, files = attachment)
    }

    private fun credential(connector: ApiConnectorDefinition): String = try {
        secrets.bearerToken(apiConnectorSecretKey(connector.id))
            ?: throw ApiConnectorException("CREDENTIAL_MISSING", "该 API 连接尚未配置访问令牌")
    } catch (_: McpCredentialsUnavailable) {
        throw ApiConnectorException("CREDENTIAL_UNAVAILABLE", "API 凭据不可用，请重新填写")
    }

    private fun readResponseBody(response: okhttp3.Response): ResponseBody {
        val declared = response.body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw ApiConnectorException("RESPONSE_TOO_LARGE", "API 响应超过 1 MiB 上限")
        }
        val output = ByteArrayOutputStream()
        var total = 0
        response.body.byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw ApiConnectorException("RESPONSE_TOO_LARGE", "API 响应超过 1 MiB 上限")
                }
                output.write(buffer, 0, count)
            }
        }
        val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val bytes = output.toByteArray()
        return ResponseBody(text = bytes.toString(charset), bytes = bytes)
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

    private fun argumentText(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                value.opt(index)?.let { item ->
                    if (item !is JSONObject && item !is JSONArray && item != JSONObject.NULL) add(item.toString())
                }
            }
        }.joinToString(",")
        else -> null
    }?.also {
        if ('\r' in it || '\n' in it || it.length > MAX_ARGUMENT_LENGTH) {
            throw ApiConnectorException("INVALID_ARGUMENT", "API 参数包含不允许的内容")
        }
    }

    private fun encodePathSegment(value: String): String = URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private data class ResponseBody(val text: String, val bytes: ByteArray)

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_ARGUMENT_LENGTH = 8_192
        val PATH_PARAMETER = Regex("\\{[A-Za-z_][A-Za-z0-9_-]{0,63}\\}")
    }
}
