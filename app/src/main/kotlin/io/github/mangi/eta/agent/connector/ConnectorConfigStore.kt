package io.github.mangi.eta.agent.connector

import android.content.Context
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedConnectorConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val endpoint: String = "",
    val socketPath: String = "",
    val enabled: Boolean = true,
    val credentialRevision: Long = 0,
    val configurationRevision: Long = 0,
) {
    fun validate() {
        require(id.length <= 256 && id.none(Char::isISOControl)) { "连接编号无效" }
        require(name.isNotBlank() && name.length <= 80) { "连接名称无效" }
        require(endpoint.length <= 4096 && socketPath.length <= 4096 && credentialRevision >= 0 && configurationRevision >= 0) { "连接配置超出范围" }
        require(protocol in SUPPORTED_PROTOCOLS) { "不支持的连接协议" }
        if (protocol in NETWORK_PROTOCOLS) require(endpoint.isNotBlank()) { "请填写连接地址" }
        if (protocol == "unix_socket") require(socketPath.isNotBlank()) { "请填写 Unix Socket 路径" }
        if (protocol == "dsh") io.github.mangi.eta.agent.dsh.DshHostClient.normalizeEndpoint(endpoint)
    }

    companion object {
        val NETWORK_PROTOCOLS = setOf("rest", "websocket", "jsonrpc", "graphql", "sse", "dsh")
        val SUPPORTED_PROTOCOLS = NETWORK_PROTOCOLS + setOf(
            "unix_socket", "intent", "broadcast", "mediastore", "sqlite", "shell", "stdio",
        )
    }
}

internal class ConnectorConfigStore(context: Context, private val secrets: A2ASecretStore = A2ASecretStore(context)) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<SavedConnectorConfig> = synchronized(LOCK) {
        val raw = preferences.getString(KEY_CONNECTIONS, "[]") ?: error("连接配置无法读取，请先核对备份")
        require(raw.length <= 2 * 1024 * 1024) { "连接配置文件过大" }
        val array = try { JSONArray(raw) } catch (failure: Exception) { throw IllegalStateException("连接配置损坏，请先核对备份，原文件未覆盖", failure) }
        require(array.length() <= 256) { "连接配置数量超过限制" }
        buildList {
            for (index in 0 until array.length()) {
                array.getJSONObject(index).let { item ->
                    add(
                        SavedConnectorConfig(
                            id = item.getString("id").also { require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "连接编号损坏" } },
                            name = item.getString("name"),
                            protocol = item.getString("protocol"),
                            endpoint = item.optString("endpoint"),
                            socketPath = item.optString("socketPath"),
                            enabled = item.optBoolean("enabled", true),
                            credentialRevision = item.optLong("credentialRevision", 0),
                            configurationRevision = item.optLong("configurationRevision", 0),
                        ),
                    )
                }
            }
        }.also { values ->
            values.forEach { it.validate() }
            require(values.map { it.id }.distinct().size == values.size) { "连接编号重复，原配置未覆盖" }
        }
    }

    fun save(config: SavedConnectorConfig, bearerToken: String? = null): SavedConnectorConfig = synchronized(LOCK) {
        var normalized = config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            name = config.name.trim(),
            protocol = config.protocol.trim().lowercase(),
            endpoint = config.endpoint.trim(),
            socketPath = config.socketPath.trim(),
        )
        normalized.validate()
        val values = load().toMutableList()
        val index = values.indexOfFirst { it.id == normalized.id }
        val existing = values.getOrNull(index)
        require(existing == null || existing.configurationRevision == normalized.configurationRevision) { "连接配置已变化，请重新打开" }
        val connectionChanged = existing != null && (existing.protocol != normalized.protocol || existing.endpoint != normalized.endpoint || existing.socketPath != normalized.socketPath)
        val replaceSecret = !bearerToken.isNullOrEmpty() || connectionChanged
        normalized = normalized.copy(credentialRevision = Math.addExact(existing?.credentialRevision ?: 0, if (replaceSecret) 1L else 0L),
            configurationRevision = Math.addExact(existing?.configurationRevision ?: 0, 1L))
        if (index >= 0) values[index] = normalized else values += normalized
        val alias = migratedAlias(normalized.id)
        val previous = secrets.encryptedSnapshot(alias)
        try {
            if (!bearerToken.isNullOrEmpty()) secrets.save(alias, bearerToken) else if (connectionChanged) secrets.delete(alias)
            persist(values)
        } catch (failure: Exception) { if (replaceSecret) runCatching { secrets.restoreEncryptedSnapshot(alias, previous) }; throw failure }
        return normalized
    }

    fun remove(id: String) = synchronized(LOCK) {
        val alias = migratedAlias(id); val previous = secrets.encryptedSnapshot(alias)
        try { secrets.delete(alias); persist(load().filterNot { it.id == id }) }
        catch (failure: Exception) { runCatching { secrets.restoreEncryptedSnapshot(alias, previous) }; throw failure }
    }

    fun token(config: SavedConnectorConfig): String? = runCatching { tokenOrThrow(config) }.getOrNull()
    fun tokenOrThrow(config: SavedConnectorConfig): String? = synchronized(LOCK) {
        val live = load().singleOrNull { it.id == config.id } ?: error("连接配置已移除")
        check(live.credentialRevision == config.credentialRevision && live.endpoint == config.endpoint && live.protocol == config.protocol &&
            live.configurationRevision == config.configurationRevision) { "连接配置或凭据已变化" }
        secrets.load(migratedAlias(config.id, requireUsable = true))
    }
    fun updateToken(id: String, value: String?, identityChanged: Boolean = true, expectedRevision: Long? = null) = synchronized(LOCK) {
        val current = load()
        val config = requireNotNull(current.singleOrNull { it.id == id }) { "连接配置已移除" }
        require(expectedRevision == null || config.credentialRevision == expectedRevision) { "连接凭据已变化" }
        val alias = migratedAlias(id); val previous = secrets.encryptedSnapshot(alias)
        try {
            if (value.isNullOrEmpty()) secrets.delete(alias) else secrets.save(alias, value)
            if (identityChanged) persist(current.map { if (it.id == id) config.copy(credentialRevision = Math.addExact(config.credentialRevision, 1),
                configurationRevision = Math.addExact(config.configurationRevision, 1)) else it })
        } catch (failure: Exception) { runCatching { secrets.restoreEncryptedSnapshot(alias, previous) }; throw failure }
    }
    fun exportCredentials(): Map<String, String> = load().mapNotNull { config -> tokenOrThrow(config)?.let { config.id to it } }.toMap()

    private fun persist(values: List<SavedConnectorConfig>) {
        require(values.size <= 256) { "连接配置数量超过限制" }
        val array = JSONArray()
        values.forEach { config ->
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("protocol", config.protocol)
                    .put("endpoint", config.endpoint)
                    .put("socketPath", config.socketPath)
                    .put("enabled", config.enabled)
                    .put("credentialRevision", config.credentialRevision)
                    .put("configurationRevision", config.configurationRevision),
            )
        }
        val encoded = array.toString()
        require(encoded.length <= 2 * 1024 * 1024) { "连接配置文件过大" }
        check(preferences.edit().putString(KEY_CONNECTIONS, encoded).commit()) {
            "连接配置保存失败"
        }
        mutableChanges.value++
    }

    private fun secretAlias(id: String): String = "c2-" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }.take(60)

    private fun legacyAlias(id: String): String = "connector_${id.lowercase().replace(Regex("[^a-z0-9._-]"), "_")}".take(64)

    private fun migratedAlias(id: String, requireUsable: Boolean = false): String {
        val current = secretAlias(id)
        if (secrets.encryptedSnapshot(current) != null) return current
        val values = load()
        if (values.none { it.id == id }) return current
        val legacy = legacyAlias(id)
        val blocked = preferences.getStringSet("ambiguous_legacy_aliases", emptySet()).orEmpty()
        if (legacy in blocked || values.count { legacyAlias(it.id) == legacy } > 1) {
            if (legacy !in blocked) check(preferences.edit().putStringSet("ambiguous_legacy_aliases", blocked + legacy).commit()) { "连接凭据隔离失败" }
            // An ambiguous old credential is never assigned to either connection, even after one is deleted.
            check(!requireUsable || secrets.encryptedSnapshot(legacy) == null) { "旧连接凭据归属不明，请重新填写令牌" }
            return current
        }
        secrets.encryptedSnapshot(legacy)?.let { encrypted ->
            secrets.restoreEncryptedSnapshot(current, encrypted)
            secrets.delete(legacy)
        }
        return current
    }

    companion object {
        private val mutableChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
        internal val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = mutableChanges
        private val LOCK = Any()
        private const val PREFERENCES_NAME = "eta_connector_configs"
        private const val KEY_CONNECTIONS = "connections"
    }
}

internal fun createSavedConnector(
    context: Context,
    config: SavedConnectorConfig,
    bearerToken: String?,
    security: ConnectorSecurityManager,
): ConnectorTransport = ConfiguredConnectorTransport(config, ConnectorConfigStore(context), when (config.protocol) {
    "dsh" -> io.github.mangi.eta.agent.dsh.DshConnectorTransport(io.github.mangi.eta.agent.dsh.DshHostRepository(context).client(config))
    "rest" -> RestConnectorTransport(config.endpoint, bearerToken, security)
    "websocket" -> WebSocketConnectorTransport(config.endpoint, security, bearerToken)
    "jsonrpc" -> JsonRpcConnectorTransport(config.endpoint, bearerToken, security)
    "graphql" -> GraphQlConnectorTransport(config.endpoint, bearerToken, security)
    "sse" -> SseConnectorTransport(config.endpoint, bearerToken, security)
    "unix_socket" -> UnixSocketConnectorTransport(config.socketPath, security)
    "broadcast" -> BroadcastConnectorTransport(context, security)
    "mediastore" -> MediaStoreConnectorTransport(context, security)
    "sqlite" -> SqliteConnectorTransport(context, security)
    "shell", "stdio" -> ShellConnectorTransport(context, security)
    "intent" -> AndroidLocalConnectorTransport(context, security)
    else -> error("不支持的连接协议：${config.protocol}")
})
