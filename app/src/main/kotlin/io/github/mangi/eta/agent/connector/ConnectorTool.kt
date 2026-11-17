package io.github.mangi.eta.agent.connector

import android.content.Context
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal object ConnectorTool {
    fun execute(context: Context, raw: JSONObject): JSONObject = runBlocking(RuntimeInvocationScope.coroutineContext()) {
        val configStore = ConnectorConfigStore(context)
        val saved = raw.optString("connection_id").takeIf(String::isNotBlank)?.let { id ->
            configStore.load().firstOrNull { it.id == id && it.enabled } ?: error("连接配置不存在或已停用：$id")
        }
        val protocol = (saved?.protocol ?: raw.optString("protocol")).lowercase()
        val security = ConnectorSecurityManager()
        val savedToken = saved?.let(configStore::tokenOrThrow)
        val transport = if (saved != null) createSavedConnector(context, saved, savedToken, security) else when (protocol) {
            "dsh" -> io.github.mangi.eta.agent.dsh.DshConnectorTransport(io.github.mangi.eta.agent.dsh.DshHostRepository(context).client(requireNotNull(saved) { "请先添加 DSH 主机" }))
            "rest" -> RestConnectorTransport(saved?.endpoint ?: required(raw, "endpoint"), savedToken, security)
            "websocket" -> WebSocketConnectorTransport(
                saved?.endpoint ?: required(raw, "endpoint"),
                security,
                raw.optString("token").takeIf(String::isNotBlank) ?: savedToken,
            )
            "jsonrpc" -> JsonRpcConnectorTransport(saved?.endpoint ?: required(raw, "endpoint"), raw.optString("token").takeIf(String::isNotBlank) ?: savedToken, security)
            "graphql" -> GraphQlConnectorTransport(saved?.endpoint ?: required(raw, "endpoint"), raw.optString("token").takeIf(String::isNotBlank) ?: savedToken, security)
            "sse" -> SseConnectorTransport(saved?.endpoint ?: required(raw, "endpoint"), raw.optString("token").takeIf(String::isNotBlank) ?: savedToken, security)
            "unix_socket" -> UnixSocketConnectorTransport(saved?.socketPath ?: required(raw, "socket_path"), security)
            "shell", "stdio" -> ShellConnectorTransport(context, security)
            "broadcast" -> BroadcastConnectorTransport(context, security)
            "mediastore" -> MediaStoreConnectorTransport(context, security)
            "sqlite" -> SqliteConnectorTransport(context, security)
            "intent", "deep_link", "content_provider", "saf", "package" -> AndroidLocalConnectorTransport(context, security)
            else -> throw IllegalArgumentException("不支持的连接协议：$protocol")
        }
        transport.use { active ->
            val controller = RuntimeInvocationScope.controller
            val cancellation = controller?.register(active::close)
            try {
            controller?.throwIfCancelled()
            if (raw.optString("action") == "probe" || protocol !in setOf("jsonrpc", "graphql")) {
                val probe = active.probe()
                controller?.throwIfCancelled()
                require(probe.available) { probe.detail }
                if (raw.optString("action") == "probe") {
                    return@use JSONObject()
                        .put("ok", true)
                        .put("protocol", protocol)
                        .put("detail", probe.detail)
                        .put("checkedAt", probe.checkedAt)
                }
            }
            val capabilityId = mapCapability(protocol, raw.optString("capability"))
            if (requiresConfirmation(protocol, capabilityId)) {
                val approved = EtaAppControlConfirmation.awaitApproval(
                    EtaControlCapabilityInfo(
                        id = "connector_call:$protocol",
                        title = "执行外部连接操作",
                        category = "connections",
                        summary = "将通过 $protocol 执行 $capabilityId；请确认目标与参数。",
                        danger = EtaControlDanger.CONFIRM,
                        parameters = raw,
                        canRead = false,
                    ),
                    raw,
                )
                require(approved) { "用户未批准连接操作" }
            }
            val arguments = raw.optJSONObject("arguments") ?: JSONObject().also { args ->
                raw.keys().forEach { key ->
                    if (key !in CONNECTOR_FIELDS) args.put(key, raw.opt(key))
                }
            }
            if (requiresConfirmation(protocol, capabilityId)) arguments.put("confirmed", true)
            val request = ConnectorInvocation(
                capabilityId = capabilityId,
                arguments = arguments,
            )
            val result = active.invoke(request)
            controller?.throwIfCancelled()
            resultEnvelope(protocol, result)
            } finally { cancellation?.close() }
        }
    }

    internal fun resultEnvelope(protocol: String, result: ConnectorResult): JSONObject = JSONObject()
        .put("ok", result.success)
        .put("protocol", protocol)
        .put("result", result.json)
        .apply {
            if (!result.success) {
                put("code", result.errorCode ?: "CONNECTOR_REMOTE_ERROR")
                result.errorMessage?.let { put("message", it.take(1000)) }
            }
            result.text?.let { put("text", it.take(256_000)) }
        }

    private fun mapCapability(protocol: String, requested: String): String {
        if (requested.isNotBlank()) return requested
        return when (protocol) {
            "dsh" -> "catalog"
            "rest" -> "request"
            "websocket", "unix_socket", "broadcast" -> "send"
            "jsonrpc" -> "call"
            "graphql" -> "query"
            "sse" -> "listen"
            "shell", "stdio" -> "exec"
            "mediastore" -> "query"
            "sqlite" -> "query"
            "package" -> "package_info"
            "content_provider" -> "query_uri"
            "saf" -> "read_uri"
            "intent", "deep_link" -> "open_uri"
            else -> requested
        }
    }

    private fun required(raw: JSONObject, name: String): String =
        raw.optString(name).trim().takeIf(String::isNotBlank) ?: error("缺少参数：$name")

    private fun requiresConfirmation(protocol: String, capability: String): Boolean =
        protocol in setOf("rest", "websocket", "jsonrpc", "graphql", "unix_socket", "shell", "stdio", "broadcast", "intent", "deep_link") &&
            capability !in setOf("package_info", "query_uri", "read_uri")

    private val CONNECTOR_FIELDS = setOf(
        "protocol", "endpoint", "token", "socket_path", "capability", "action", "arguments",
        "connection_id",
    )
}

internal object ConnectorToolCatalog {
    fun appendTo(tools: org.json.JSONArray) {
        tools.put(
            io.github.mangi.eta.agent.model.AgentToolSchema.function(
                name = "connector_call",
                description = "Connect to and call Nexus Connector SDK transports: configured DSH hosts (read-only), REST, WebSocket, JSON-RPC, GraphQL, SSE, Unix Socket, " +
                    "Shell/STDIO, Android Broadcast, MediaStore, read-only SQLite, Intent/Deep Link, SAF and ContentProvider. " +
                    "Connection setup is performed where required; action=probe explicitly tests health. Risky operations require local user confirmation; model arguments cannot approve them.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("protocol", JSONObject().put("type", "string"))
                            .put("connection_id", JSONObject().put("type", "string"))
                            .put("endpoint", JSONObject().put("type", "string"))
                            .put("token", JSONObject().put("type", "string"))
                            .put("socket_path", JSONObject().put("type", "string"))
                            .put("capability", JSONObject().put("type", "string"))
                            .put("action", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("probe", "invoke"))))
                            .put("arguments", JSONObject().put("type", "object")),
                    )
                    .put("required", org.json.JSONArray().put("action")),
            ),
        )
    }
}
