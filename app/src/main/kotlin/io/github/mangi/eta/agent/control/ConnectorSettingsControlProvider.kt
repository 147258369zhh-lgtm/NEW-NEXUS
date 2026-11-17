package io.github.mangi.eta.agent.control

import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object ConnectorSettingsControlProvider : EtaControlProvider {
    override val providerId = "connector_settings"

    override fun capabilities(): List<EtaControlRegistration> {
        fun string() = JSONObject().put("type", "string").put("maxLength", 4096)
        return listOf(EtaControlRegistration(
            info = EtaControlCapabilityInfo("settings.connector.configure", "外部连接配置", "连接", "修改已有连接的名称、地址和启用状态；令牌只能由本机设置填写",
                EtaControlDanger.CONFIRM, JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("connectionId", string().put("minLength", 1).put("maxLength", 256))
                    .put("expectedRevision", JSONObject().put("type", "integer").put("minimum", 0))
                    .put("value", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", string().put("minLength", 1).put("maxLength", 80))
                        .put("protocol", string().put("enum", JSONArray(SavedConnectorConfig.SUPPORTED_PROTOCOLS.sorted())))
                        .put("endpoint", string()).put("socketPath", string()).put("enabled", JSONObject().put("type", "boolean")))
                        .put("additionalProperties", false)))
                    .put("required", JSONArray(listOf("connectionId", "expectedRevision", "value"))).put("additionalProperties", false), false),
            snapshot = { context, args -> snapshot(ConnectorConfigStore(context), args.getString("connectionId"), args.optBoolean("privateChange")) },
            rollback = { context, before ->
                val store = ConnectorConfigStore(context)
                val id = before.getString("connectionId")
                val current = store.load().singleOrNull { it.id == id }
                if (!before.getBoolean("exists")) store.remove(id)
                else {
                    requireNotNull(current) { "连接已移除，请重新填写凭据" }
                    check(current.credentialRevision == before.getLong("authenticationEpoch")) { "连接凭据已变化，无法撤销" }
                    store.save(decode(before.getJSONObject("value"), id, current.configurationRevision))
                }
                snapshot(store, id)
            },
            execute = { context, args ->
                val store = ConnectorConfigStore(context)
                val id = args.getString("connectionId")
                val current = store.load().singleOrNull { it.id == id } ?: error("连接不存在，请先在本机添加")
                require(current.configurationRevision == args.getLong("expectedRevision")) { "连接配置已变化，请重新读取" }
                val patch = args.getJSONObject("value")
                val next = current.copy(name = if (patch.has("name")) patch.getString("name") else current.name,
                    protocol = if (patch.has("protocol")) patch.getString("protocol") else current.protocol,
                    endpoint = if (patch.has("endpoint")) patch.getString("endpoint") else current.endpoint,
                    socketPath = if (patch.has("socketPath")) patch.getString("socketPath") else current.socketPath,
                    enabled = if (patch.has("enabled")) patch.getBoolean("enabled") else current.enabled)
                require(next.endpoint == current.endpoint && next.protocol == current.protocol && next.socketPath == current.socketPath) {
                    "连接地址和协议请在本机修改并重新填写令牌"
                }
                store.save(next)
                snapshot(store, id)
            },
        ), EtaControlRegistration(
            info = EtaControlCapabilityInfo("state.connections", "外部连接目录", "连接", "读取连接身份、协议和修订，不读取令牌", EtaControlDanger.SAFE,
                JSONObject(), true),
            read = { context -> JSONObject().put("connections", JSONArray(ConnectorConfigStore(context).load().map {
                JSONObject().put("connectionId", it.id).put("name", it.name).put("protocol", it.protocol)
                    .put("enabled", it.enabled).put("revision", it.configurationRevision)
            })) },
            execute = { _, _ -> throw EtaControlException("CAPABILITY_READ_ONLY", "连接目录只能读取") },
        ))
    }

    internal fun parameters(value: SavedConnectorConfig): JSONObject = JSONObject().put("connectionId", value.id)
        .put("expectedRevision", value.configurationRevision).put("value", encode(value))

    internal fun snapshot(store: ConnectorConfigStore, id: String, privateChange: Boolean = false): JSONObject = JSONObject()
        .put("connectionId", id).apply {
            val current = store.load().singleOrNull { it.id == id }
            put("exists", current != null)
            if (current != null) put("value", encode(current)).put("authenticationEpoch", current.credentialRevision)
            if (privateChange || current?.let { safeEndpoint(it.endpoint) != it.endpoint } == true)
                put("credentials", "private configuration changed; re-entry required")
        }

    internal fun encode(value: SavedConnectorConfig): JSONObject = JSONObject().put("name", value.name).put("protocol", value.protocol)
        .put("endpoint", safeEndpoint(value.endpoint)).put("socketPath", value.socketPath).put("enabled", value.enabled)

    private fun decode(value: JSONObject, id: String, revision: Long): SavedConnectorConfig = SavedConnectorConfig(id,
        value.getString("name"), value.getString("protocol"), value.getString("endpoint"), value.getString("socketPath"),
        value.getBoolean("enabled"), configurationRevision = revision)

    private fun safeEndpoint(value: String): String = runCatching {
        val uri = URI(value)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString() else value
    }.getOrDefault("[unavailable]")
}
