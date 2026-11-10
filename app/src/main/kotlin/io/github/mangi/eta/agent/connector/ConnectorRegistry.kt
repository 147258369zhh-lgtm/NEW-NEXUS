package io.github.mangi.eta.agent.connector

import android.content.Context

internal class ConnectorRegistry(
    context: Context,
    private val security: ConnectorSecurityManager = ConnectorSecurityManager(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val transports = mutableMapOf<String, ConnectorTransport>()

    fun descriptors(): List<ConnectorDescriptor> = buildList {
        add(AndroidLocalConnectorTransport(appContext, security).descriptor)
        add(
            ConnectorDescriptor(
                id = "mcp:aggregate",
                name = "MCP",
                kind = ConnectorKind.MCP,
                transport = "stdio/http",
                capabilities = emptyList(),
            ),
        )
        add(
            ConnectorDescriptor(
                id = "a2a:aggregate",
                name = "A2A",
                kind = ConnectorKind.A2A,
                transport = "JSON-RPC/SSE",
                capabilities = emptyList(),
            ),
        )
        add(
            ConnectorDescriptor(
                id = "openapi:registry",
                name = "OpenAPI",
                kind = ConnectorKind.OPENAPI,
                transport = "REST",
                capabilities = emptyList(),
            ),
        )
    }

    fun localTransport(): ConnectorTransport =
        transports.getOrPut("android:local") { AndroidLocalConnectorTransport(appContext, security) }

    fun webSocket(endpoint: String): ConnectorTransport =
        transports.getOrPut("websocket:$endpoint") { WebSocketConnectorTransport(endpoint, security) }

    fun openApi(connectorId: String): ConnectorTransport =
        transports.getOrPut("openapi:$connectorId") {
            OpenApiConnectorTransport(appContext, connectorId, security)
        }

    fun security(): ConnectorSecurityManager = security

    override fun close() {
        transports.values.forEach { runCatching { it.close() } }
        transports.clear()
    }
}
