package io.github.mangi.eta.agent.capability

import android.content.Context
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Persists non-secret REST connector metadata. Credentials live in Android Keystore. */
internal class ApiConnectorStore(context: Context) {
    private val file = File(
        context.applicationContext.filesDir,
        "capability-center/api-connectors.json",
    )

    @Synchronized
    fun load(): List<ApiConnectorDefinition> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val values = root.optJSONArray("connectors") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::parseConnector)?.let(::add)
                }
            }.distinctBy(ApiConnectorDefinition::id)
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(connectors: List<ApiConnectorDefinition>): List<ApiConnectorDefinition> {
        val validated = connectors.map(::validate).distinctBy(ApiConnectorDefinition::id)
        require(validated.size <= MAX_CONNECTORS) { "最多添加 $MAX_CONNECTORS 个 API 连接" }
        require(validated.sumOf { it.actions.size } <= MAX_ACTIONS) {
            "所有 API 连接合计最多包含 $MAX_ACTIONS 个操作"
        }
        write(validated)
        return validated.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    @Synchronized
    fun upsert(connector: ApiConnectorDefinition): List<ApiConnectorDefinition> {
        val current = load()
        val existing = current.indexOfFirst { it.id == connector.id }
        val next = if (existing >= 0) {
            current.toMutableList().also { it[existing] = connector }
        } else {
            current + connector
        }
        return save(next)
    }

    @Synchronized
    fun delete(connectorId: String): List<ApiConnectorDefinition> =
        save(load().filterNot { it.id == connectorId })

    fun validate(connector: ApiConnectorDefinition): ApiConnectorDefinition {
        require(CONNECTOR_ID.matches(connector.id)) { "API 连接 ID 无效" }
        val name = connector.name.trim()
        require(name.length in 1..80 && name.none(Char::isISOControl)) {
            "API 连接名称应为 1-80 个可见字符"
        }
        val description = connector.description.trim()
        require(description.length <= 1_000 && description.none(Char::isISOControl)) {
            "API 连接说明不能超过 1000 个字符"
        }
        val baseUrl = validateBaseUrl(connector.baseUrl)
        val authType = connector.authType.takeIf { it in ApiConnectorAuthType.values }
            ?: throw ApiConnectorException("INVALID_AUTH", "不支持的认证方式")
        val headerName = connector.authHeaderName.trim()
        if (authType == ApiConnectorAuthType.HEADER) {
            require(SAFE_HEADER.matches(headerName) && headerName.uppercase(Locale.ROOT) !in FORBIDDEN_HEADERS) {
                "API Key 请求头名称无效"
            }
        }
        val oauth = connector.oauth?.let(::validateOAuth)
        require((authType == ApiConnectorAuthType.OAUTH2) == (oauth != null)) {
            "OAuth 认证方式与授权配置必须同时提供"
        }
        require(connector.actions.size <= MAX_ACTIONS_PER_CONNECTOR) {
            "单个 API 连接最多包含 $MAX_ACTIONS_PER_CONNECTOR 个操作"
        }
        val actions = connector.actions.map(::validateAction)
        require(actions.map(ApiConnectorAction::name).distinct().size == actions.size) {
            "API 操作名称不能重复"
        }
        return connector.copy(
            name = name,
            description = description,
            baseUrl = baseUrl,
            authType = authType,
            authHeaderName = headerName,
            oauth = oauth,
            actions = actions,
            updatedAt = connector.updatedAt.coerceAtLeast(connector.createdAt),
        )
    }

    private fun validateAction(action: ApiConnectorAction): ApiConnectorAction {
        val name = action.name.trim()
        require(ACTION_NAME.matches(name)) {
            "API 操作名称必须为小写字母、数字、下划线或连字符"
        }
        require(action.method.uppercase(Locale.ROOT) in ApiHttpMethod.values) {
            "API 操作使用了不支持的方法"
        }
        val path = action.path.trim()
        require(path.startsWith('/') && path.length <= 1_024 && path.none(Char::isISOControl)) {
            "API 操作路径必须是不超过 1024 字符的绝对相对路径"
        }
        require('?' !in path && '#' !in path && '\\' !in path && ".." !in path) {
            "API 操作路径包含不安全片段"
        }
        val placeholders = PATH_PARAMETER.findAll(path).map { it.groupValues[1] }.toList()
        require(placeholders.distinct().size == placeholders.size) { "API 路径参数不能重复" }
        require(pathParameters(action.pathParameters).toSet() == placeholders.toSet()) {
            "API 路径参数声明与路径模板不一致"
        }
        val schema = runCatching { JSONObject(action.inputSchemaJson) }
            .getOrElse { throw ApiConnectorException("INVALID_SCHEMA", "API 操作参数 Schema 不是 JSON object") }
        require(schema.optString("type", "object") == "object") {
            "API 操作参数 Schema 的 type 必须为 object"
        }
        require(action.inputSchemaJson.toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES) {
            "API 操作参数 Schema 超过 64 KiB"
        }
        val declaredParameters = parameters(action.pathParameters) +
            pathParameters(action.queryParameters) +
            pathParameters(action.headerParameters) +
            pathParameters(action.bodyParameters)
        require(declaredParameters.distinct().size == declaredParameters.size) { "API 参数只能出现在一个位置" }
        require(action.headerParameters.all { SAFE_HEADER.matches(it) && it.uppercase(Locale.ROOT) !in FORBIDDEN_HEADERS }) {
            "API 操作包含不安全的自定义请求头"
        }
        return action.copy(
            name = name,
            description = action.description.trim().take(1_000),
            method = action.method.uppercase(Locale.ROOT),
            path = path,
            pathParameters = parameters(action.pathParameters),
            queryParameters = parameters(action.queryParameters),
            headerParameters = parameters(action.headerParameters),
            bodyParameters = parameters(action.bodyParameters),
            destructive = action.method.uppercase(Locale.ROOT) !in setOf(ApiHttpMethod.GET, "HEAD"),
        )
    }

    private fun validateBaseUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw ApiConnectorException("INVALID_BASE_URL", "API 基础地址无效") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "API 基础地址必须使用 HTTPS"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "API 基础地址不能包含凭据、查询参数或片段"
        }
        require(uri.port in setOf(-1, 443)) { "API 基础地址只允许标准 HTTPS 端口" }
        return uri.toASCIIString().trimEnd('/')
    }

    private fun validateOAuth(raw: ApiOAuth2Configuration): ApiOAuth2Configuration {
        val authorizationEndpoint = validateOAuthEndpoint(raw.authorizationEndpoint, "授权")
        val tokenEndpoint = validateOAuthEndpoint(raw.tokenEndpoint, "令牌")
        val clientId = raw.clientId.trim()
        require(clientId.length in 1..512 && clientId.none(Char::isISOControl)) {
            "OAuth Client ID 应为 1-512 个可见字符"
        }
        val scopes = raw.scopes.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        require(scopes.size <= MAX_OAUTH_SCOPES && scopes.all {
            it.length <= MAX_OAUTH_SCOPE_LENGTH && it.none(Char::isISOControl)
        }) {
            "OAuth Scope 过多或包含无效字符"
        }
        return raw.copy(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            scopes = scopes,
        )
    }

    private fun validateOAuthEndpoint(raw: String, label: String): String {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw ApiConnectorException("INVALID_OAUTH_ENDPOINT", "OAuth $label 地址无效") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "OAuth $label 地址必须使用 HTTPS"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "OAuth $label 地址不能包含凭据、查询参数或片段"
        }
        require(uri.port in setOf(-1, 443)) { "OAuth $label 地址只允许标准 HTTPS 端口" }
        return uri.toASCIIString()
    }

    private fun parameters(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private fun parseConnector(value: JSONObject): ApiConnectorDefinition? = runCatching {
        val actions = value.optJSONArray("actions") ?: JSONArray()
        ApiConnectorDefinition(
            id = value.optString("id"),
            name = value.optString("name"),
            description = value.optString("description"),
            baseUrl = value.optString("baseUrl"),
            enabled = value.optBoolean("enabled", true),
            authType = value.optString("authType", ApiConnectorAuthType.NONE),
            authHeaderName = value.optString("authHeaderName", "X-API-Key"),
            oauth = value.optJSONObject("oauth")?.let(::parseOAuth),
            actions = buildList {
                for (index in 0 until actions.length()) {
                    actions.optJSONObject(index)?.let(::parseAction)?.let(::add)
                }
            },
            sourceUrl = value.optionalString("sourceUrl"),
            createdAt = value.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = value.optLong("updatedAt", System.currentTimeMillis()),
        ).let(::validate)
    }.getOrNull()

    private fun parseAction(value: JSONObject): ApiConnectorAction = ApiConnectorAction(
        name = value.optString("name"),
        description = value.optString("description"),
        method = value.optString("method"),
        path = value.optString("path"),
        inputSchemaJson = value.optString("inputSchemaJson", "{\"type\":\"object\"}"),
        pathParameters = value.stringList("pathParameters"),
        queryParameters = value.stringList("queryParameters"),
        headerParameters = value.stringList("headerParameters"),
        bodyParameters = value.stringList("bodyParameters"),
        requestBodyRequired = value.optBoolean("requestBodyRequired", false),
        enabled = value.optBoolean("enabled", false),
        destructive = value.optBoolean("destructive", true),
    )

    private fun parseOAuth(value: JSONObject): ApiOAuth2Configuration = ApiOAuth2Configuration(
        authorizationEndpoint = value.optString("authorizationEndpoint"),
        tokenEndpoint = value.optString("tokenEndpoint"),
        clientId = value.optString("clientId"),
        scopes = value.stringList("scopes"),
        usesClientSecret = value.optBoolean("usesClientSecret", false),
    )

    private fun write(connectors: List<ApiConnectorDefinition>) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("connectors", JSONArray().also { values ->
                connectors.forEach { connector ->
                    values.put(
                        JSONObject()
                            .put("id", connector.id)
                            .put("name", connector.name)
                            .put("description", connector.description)
                            .put("baseUrl", connector.baseUrl)
                            .put("enabled", connector.enabled)
                            .put("authType", connector.authType)
                            .put("authHeaderName", connector.authHeaderName)
                            .put("oauth", connector.oauth?.toJson() ?: JSONObject.NULL)
                            .put("sourceUrl", connector.sourceUrl ?: JSONObject.NULL)
                            .put("createdAt", connector.createdAt)
                            .put("updatedAt", connector.updatedAt)
                            .put("actions", JSONArray().also { actionValues ->
                                connector.actions.forEach { action ->
                                    actionValues.put(
                                        JSONObject()
                                            .put("name", action.name)
                                            .put("description", action.description)
                                            .put("method", action.method)
                                            .put("path", action.path)
                                            .put("inputSchemaJson", action.inputSchemaJson)
                                            .put("pathParameters", JSONArray(action.pathParameters))
                                            .put("queryParameters", JSONArray(action.queryParameters))
                                            .put("headerParameters", JSONArray(action.headerParameters))
                                            .put("bodyParameters", JSONArray(action.bodyParameters))
                                            .put("requestBodyRequired", action.requestBodyRequired)
                                            .put("enabled", action.enabled)
                                            .put("destructive", action.destructive),
                                    )
                                }
                            }),
                    )
                }
            })
        val parent = requireNotNull(file.parentFile) { "API 连接配置目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建 API 连接配置目录" }
        val temporary = File.createTempFile("eta-api-connectors-", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.stringList(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun ApiOAuth2Configuration.toJson(): JSONObject = JSONObject()
        .put("authorizationEndpoint", authorizationEndpoint)
        .put("tokenEndpoint", tokenEndpoint)
        .put("clientId", clientId)
        .put("scopes", JSONArray(scopes))
        .put("usesClientSecret", usesClientSecret)

    private fun pathParameters(values: List<String>): List<String> =
        values.map(String::trim).filter(String::isNotBlank)

    private companion object {
        const val VERSION = 1
        const val MAX_CONNECTORS = 24
        const val MAX_ACTIONS = 256
        const val MAX_ACTIONS_PER_CONNECTOR = 128
        const val MAX_SCHEMA_BYTES = 64 * 1024
        const val MAX_OAUTH_SCOPES = 32
        const val MAX_OAUTH_SCOPE_LENGTH = 256
        val CONNECTOR_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val ACTION_NAME = Regex("[a-z][a-z0-9_-]{0,63}")
        val SAFE_HEADER = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")
        val PATH_PARAMETER = Regex("\\{([A-Za-z_][A-Za-z0-9_-]{0,63})\\}")
        val FORBIDDEN_HEADERS = setOf(
            "AUTHORIZATION", "HOST", "CONTENT-LENGTH", "TRANSFER-ENCODING",
            "CONNECTION", "COOKIE", "PROXY-AUTHORIZATION",
        )
    }
}
