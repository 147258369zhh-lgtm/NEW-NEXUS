package io.github.mangi.eta.agent.connector

import android.content.Context
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedConnectorConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val endpoint: String = "",
    val socketPath: String = "",
) {
    fun validate() {
        require(name.isNotBlank()) { "连接名称不能为空" }
        require(protocol in SUPPORTED_PROTOCOLS) { "不支持的连接协议" }
        if (protocol in NETWORK_PROTOCOLS) require(endpoint.isNotBlank()) { "请填写连接地址" }
        if (protocol == "unix_socket") require(socketPath.isNotBlank()) { "请填写 Unix Socket 路径" }
    }

    companion object {
        val NETWORK_PROTOCOLS = setOf("websocket", "jsonrpc", "graphql", "sse")
        val SUPPORTED_PROTOCOLS = NETWORK_PROTOCOLS + setOf(
            "unix_socket", "intent", "broadcast", "mediastore", "sqlite", "shell", "stdio",
        )
    }
}

internal class ConnectorConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = A2ASecretStore(appContext)

    fun load(): List<SavedConnectorConfig> = runCatching {
        val array = JSONArray(preferences.getString(KEY_CONNECTIONS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    add(
                        SavedConnectorConfig(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            protocol = item.optString("protocol"),
                            endpoint = item.optString("endpoint"),
                            socketPath = item.optString("socketPath"),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun save(config: SavedConnectorConfig, bearerToken: String? = null): SavedConnectorConfig {
        val normalized = config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            name = config.name.trim().take(80),
            protocol = config.protocol.trim().lowercase(),
            endpoint = config.endpoint.trim().take(4_096),
            socketPath = config.socketPath.trim().take(4_096),
        )
        normalized.validate()
        val values = load().toMutableList()
        val index = values.indexOfFirst { it.id == normalized.id }
        if (index >= 0) values[index] = normalized else values += normalized
        persist(values)
        if (!bearerToken.isNullOrEmpty()) secrets.save(secretAlias(normalized.id), bearerToken)
        return normalized
    }

    fun remove(id: String) {
        persist(load().filterNot { it.id == id })
        secrets.delete(secretAlias(id))
    }

    fun token(config: SavedConnectorConfig): String? = runCatching { secrets.load(secretAlias(config.id)) }.getOrNull()

    private fun persist(values: List<SavedConnectorConfig>) {
        val array = JSONArray()
        values.forEach { config ->
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("protocol", config.protocol)
                    .put("endpoint", config.endpoint)
                    .put("socketPath", config.socketPath),
            )
        }
        check(preferences.edit().putString(KEY_CONNECTIONS, array.toString()).commit()) {
            "连接配置保存失败"
        }
    }

    private fun secretAlias(id: String): String = "connector_${id.lowercase().replace(Regex("[^a-z0-9._-]"), "_")}".take(64)

    private companion object {
        const val PREFERENCES_NAME = "eta_connector_configs"
        const val KEY_CONNECTIONS = "connections"
    }
}

internal fun createSavedConnector(
    context: Context,
    config: SavedConnectorConfig,
    bearerToken: String?,
    security: ConnectorSecurityManager,
): ConnectorTransport = when (config.protocol) {
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
}
