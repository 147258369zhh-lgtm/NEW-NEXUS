package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.agent.capability.ApiConnectorDefinition
import io.github.mangi.eta.agent.capability.ApiConnectorService
import org.json.JSONArray
import org.json.JSONObject

/** Changes existing API switches through the same service used by the capability center. */
internal object ApiConnectorSettingsControlProvider : EtaControlProvider {
    override val providerId = "api_connector_settings"

    override fun capabilities(): List<EtaControlRegistration> = listOf(
        registration("settings.api_connector.enabled", "启用或停用 API 连接", action = false),
        registration("settings.api_connector.action", "启用或停用 API 操作", action = true),
    )

    private fun registration(id: String, title: String, action: Boolean): EtaControlRegistration {
        val properties = JSONObject()
            .put("connectorId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 64))
            .put("enabled", JSONObject().put("type", "boolean"))
            .put("expectedEnabled", JSONObject().put("type", "boolean"))
            .put("expectedBinding", JSONObject().put("type", "string").put("minLength", 64).put("maxLength", 64))
        val required = mutableListOf("connectorId", "enabled", "expectedEnabled", "expectedBinding")
        if (action) {
            properties.put("actionName", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 64))
            required.add("actionName")
        }
        return EtaControlRegistration(
            info = EtaControlCapabilityInfo(id, title, "智能", "切换已配置的连接或操作；不读取或修改凭据",
                EtaControlDanger.WRITE, JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray(required)).put("additionalProperties", false), false),
            snapshot = { context, args -> snapshot(context, args.getString("connectorId"),
                if (action) args.getString("actionName") else null) },
            execute = { context, args ->
                val name = if (action) args.getString("actionName") else null
                change(context, args.getString("connectorId"), name, args.getBoolean("enabled"),
                    args.getBoolean("expectedEnabled"), args.getString("expectedBinding"))
            },
            rollback = { context, before ->
                val name = if (action) before.getString("actionName") else null
                val original = before.getBoolean("enabled")
                change(context, before.getString("connectorId"), name, original, !original,
                    before.getString("binding")).put("rolledBack", true)
            },
        )
    }

    internal fun snapshot(connector: ApiConnectorDefinition, actionName: String? = null): JSONObject {
        val selected = actionName?.let { name -> connector.actions.singleOrNull { it.name == name }
            ?: throw EtaControlException("API_ACTION_NOT_FOUND", "API 操作不存在") }
        // Exclude only the switch being changed and the update time; any other definition change
        // invalidates the previous read and prevents an undo from affecting a different operation.
        val bindingValue = JSONObject().put("id", connector.id).put("name", connector.name)
            .put("description", connector.description).put("baseUrl", connector.baseUrl)
            .put("enabled", if (selected == null) false else connector.enabled)
            .put("authType", connector.authType).put("authHeaderName", connector.authHeaderName)
            .put("sourceUrl", connector.sourceUrl ?: JSONObject.NULL).put("createdAt", connector.createdAt)
            .put("oauth", connector.oauth?.let { oauth -> JSONObject()
                .put("authorizationEndpoint", oauth.authorizationEndpoint).put("tokenEndpoint", oauth.tokenEndpoint)
                .put("clientId", oauth.clientId).put("scopes", JSONArray(oauth.scopes))
                .put("usesClientSecret", oauth.usesClientSecret) } ?: JSONObject.NULL)
            .put("actions", JSONArray(connector.actions.map { item -> JSONObject()
                .put("name", item.name).put("description", item.description).put("method", item.method)
                .put("path", item.path).put("inputSchemaJson", item.inputSchemaJson)
                .put("pathParameters", JSONArray(item.pathParameters)).put("queryParameters", JSONArray(item.queryParameters))
                .put("headerParameters", JSONArray(item.headerParameters)).put("bodyParameters", JSONArray(item.bodyParameters))
                .put("requestBodyRequired", item.requestBodyRequired)
                .put("enabled", if (item.name == actionName) false else item.enabled)
                .put("destructive", item.destructive) }))
        val binding = SettingsControlSnapshots.hash(bindingValue)
        return JSONObject().put("connectorId", connector.id).put("enabled", selected?.enabled ?: connector.enabled)
            .put("binding", binding).apply { if (actionName != null) put("actionName", actionName) }
    }

    private fun snapshot(context: Context, connectorId: String, actionName: String?): JSONObject =
        synchronized(ApiConnectorService.MUTATION_LOCK) {
            val connector = ApiConnectorService(context).connectors().singleOrNull { it.id == connectorId }
                ?: throw EtaControlException("API_CONNECTOR_NOT_FOUND", "API 连接不存在")
            snapshot(connector, actionName)
        }

    private fun change(context: Context, connectorId: String, actionName: String?, enabled: Boolean,
        expectedEnabled: Boolean, expectedBinding: String): JSONObject = synchronized(ApiConnectorService.MUTATION_LOCK) {
        val service = ApiConnectorService(context)
        val connector = service.connectors().singleOrNull { it.id == connectorId }
            ?: throw EtaControlException("API_CONNECTOR_NOT_FOUND", "API 连接不存在")
        val current = snapshot(connector, actionName)
        if (current.getBoolean("enabled") != expectedEnabled || current.getString("binding") != expectedBinding) {
            throw EtaControlException("API_CONNECTOR_CHANGED", "连接或操作已变化，请重新读取后再修改")
        }
        val updated = if (actionName == null) service.setEnabled(connectorId, enabled)
        else service.setActionEnabled(connectorId, actionName, enabled)
        snapshot(updated.single { it.id == connectorId }, actionName)
    }
}
