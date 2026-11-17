package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.connector.*
import org.json.JSONArray
import org.json.JSONObject

internal class DshConnectorTransport(private val client: DshHostClient) : ConnectorTransport {
    private var catalog: DshCatalog? = null
    override val descriptor = ConnectorDescriptor("dsh:${client.endpoint}", "DSH", ConnectorKind.DSH, "dsh-go-v1",
        listOf(ConnectorCapability("catalog", "插件目录", "读取 DSH 原生插件目录"), ConnectorCapability("view", "读取面板", "读取已声明的 DSH 面板数据")))
    override suspend fun probe(): ConnectorHealth = try {
        catalog = client.contributions()
        ConnectorHealth(true, "DSH 主机已连接")
    } catch (failure: Exception) { ConnectorHealth(false, (failure as? DshException)?.message ?: "DSH 主机不可用") }
    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = when (request.capabilityId) {
        "catalog" -> ConnectorResult(JSONObject().put("plugins", JSONArray((catalog ?: client.contributions()).plugins.map { plugin ->
            JSONObject().put("pkg", plugin.pkg).put("title", plugin.title).put("state", plugin.state)
        })))
        "view" -> {
            val pkg = request.arguments.getString("pkg"); val id = request.arguments.getString("id")
            val catalog = catalog ?: client.contributions()
            require(catalog.plugins.any { it.pkg == pkg && it.contributions.any { item -> item.surface == "panels" && item.id == id && item.ready } })
            val view = client.view(pkg, id, request.arguments.optJSONObject("scope") ?: JSONObject())
            ConnectorResult(JSONObject().put("pkg", pkg).put("id", id).put("title", view.title).put("data", view.data ?: JSONObject.NULL))
        }
        else -> error("DSH 连接器只提供目录与面板读取")
    }
    override fun close() = client.close()
}
