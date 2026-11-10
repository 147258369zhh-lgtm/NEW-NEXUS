package io.github.mangi.eta.agent.connector

import android.content.Context
import io.github.mangi.eta.agent.capability.ApiConnectorRunTool
import io.github.mangi.eta.agent.capability.ApiConnectorService
import io.github.mangi.eta.agent.capability.ApiConnectorSnapshot
import io.github.mangi.eta.agent.capability.ApiToolExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class OpenApiConnectorTransport(
    context: Context,
    private val connectorId: String,
    private val security: ConnectorSecurityManager,
) : ConnectorTransport {
    private val appContext = context.applicationContext
    private val service = ApiConnectorService(appContext)

    override val descriptor: ConnectorDescriptor
        get() {
            val connector = service.connectors().firstOrNull { it.id == connectorId }
            return ConnectorDescriptor(
                id = connectorId,
                name = connector?.name ?: "OpenAPI",
                kind = ConnectorKind.OPENAPI,
                transport = "rest",
                capabilities = connector?.actions.orEmpty().map { action ->
                    ConnectorCapability(
                        id = action.name,
                        title = action.name,
                        description = action.description,
                        inputSchemaJson = action.inputSchemaJson,
                        readOnly = action.method == "GET",
                        destructive = action.destructive,
                    )
                },
                configured = connector != null,
            )
        }

    override suspend fun probe(): ConnectorHealth = withContext(Dispatchers.IO) {
        val connector = service.connectors().firstOrNull { it.id == connectorId }
        when {
            connector == null -> ConnectorHealth(false, "OpenAPI 连接不存在")
            !connector.enabled -> ConnectorHealth(false, "OpenAPI 连接已停用")
            !service.credentialAvailable(connector) -> ConnectorHealth(false, "OpenAPI 凭据不可用")
            connector.activeActions.isEmpty() -> ConnectorHealth(false, "尚未启用任何 OpenAPI 操作")
            else -> ConnectorHealth(true, "${connector.activeActions.size} 个操作已启用")
        }
    }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, confirmed = request.arguments.optBoolean("confirmed"))
        val connector = service.connectors().firstOrNull { it.id == connectorId }
            ?: throw IllegalArgumentException("OpenAPI 连接不存在")
        val action = connector.actions.firstOrNull { it.name == request.capabilityId && it.enabled }
            ?: throw IllegalArgumentException("OpenAPI 操作未启用")
        val modelName = ApiConnectorSnapshot.modelToolName(connector.id, action.name)
        val snapshot = ApiConnectorSnapshot(listOf(ApiConnectorRunTool(modelName, connector, action)))
        val result = ApiToolExecutor(appContext, snapshot).use { executor ->
            executor.execute(
                AgentModelClient.ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = modelName,
                    argumentsJson = request.arguments.toString(),
                ),
            )
        }
        ConnectorResult(
            json = runCatching { JSONObject(result.content) }.getOrElse { JSONObject().put("content", result.content) },
            text = result.content,
        )
    }
}
