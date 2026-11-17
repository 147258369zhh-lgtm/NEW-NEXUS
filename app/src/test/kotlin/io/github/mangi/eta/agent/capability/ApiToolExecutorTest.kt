package io.github.mangi.eta.agent.capability

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.McpSecretStore
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.spec.SecretKeySpec
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ApiToolExecutorTest {
    private lateinit var server: HttpServer
    private val request = AtomicReference<CapturedRequest>()

    @Before
    fun setUp() {
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "capability-center/api-connectors.json").delete()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { instance ->
            instance.createContext("/api/projects/") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                request.set(
                    CapturedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.rawPath,
                        query = exchange.requestURI.rawQuery,
                        trace = exchange.requestHeaders.getFirst("X-Trace"),
                        body = body,
                    ),
                )
                val response = "{\"ok\":true,\"id\":\"p1\"}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(201, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            instance.start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun executesBoundedRestCallAndRejectsUndeclaredArguments() {
        val connector = ApiConnectorDefinition(
            id = "api-test",
            name = "Projects",
            baseUrl = "http://127.0.0.1:${server.address.port}/api",
        )
        val action = ApiConnectorAction(
            name = "create_project",
            description = "Create",
            method = ApiHttpMethod.POST,
            path = "/projects/{projectId}",
            inputSchemaJson = """{"type":"object","properties":{"projectId":{"type":"string"},"expand":{"type":"string"},"X-Trace":{"type":"string"},"name":{"type":"string"}},"required":["projectId","name"],"additionalProperties":false}""",
            pathParameters = listOf("projectId"),
            queryParameters = listOf("expand"),
            headerParameters = listOf("X-Trace"),
            bodyParameters = listOf("name"),
            requestBodyRequired = true,
            enabled = true,
            destructive = true,
        )
        val snapshot = ApiConnectorSnapshot(
            listOf(ApiConnectorRunTool("api_test_tool", connector, action)),
        )
        ApiConnectorStore(RuntimeEnvironment.getApplication()).upsert(connector.copy(actions = listOf(action)))
        val executor = ApiToolExecutor(RuntimeEnvironment.getApplication(), snapshot)

        val result = executor.execute(
            AgentModelClient.ToolCall(
                id = "call",
                name = "api_test_tool",
                argumentsJson = """{"projectId":"a b","expand":"members","X-Trace":"trace-1","name":"Demo"}""",
            ),
        )

        val response = JSONObject(result.content)
        assertTrue(response.getBoolean("ok"))
        assertEquals(201, response.getInt("http_status"))
        assertEquals("POST", request.get().method)
        assertEquals("/api/projects/a%20b", request.get().path)
        assertEquals("expand=members", request.get().query)
        assertEquals("trace-1", request.get().trace)
        assertEquals("Demo", JSONObject(request.get().body).getString("name"))
        assertTrue(result.sensitive)

        val rejected = executor.execute(
            AgentModelClient.ToolCall(
                id = "call-2",
                name = "api_test_tool",
                argumentsJson = """{"projectId":"p1","name":"Demo","unknown":true}""",
            ),
        )
        val failure = JSONObject(rejected.content)
        assertFalse(failure.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", failure.getString("code"))
    }

    @Test
    fun oauth401RefreshesExactlyOnceAndRetriesTheSameRequest() {
        val authorizations = CopyOnWriteArrayList<String>()
        val refreshRequests = mutableListOf<Boolean>()
        server.createContext("/oauth") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val status = if (authorizations.size == 1) 401 else 200
            val response = "{\"ok\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val connector = ApiConnectorDefinition(
            id = "api-oauth-executor",
            name = "OAuth API",
            baseUrl = "http://127.0.0.1:${server.address.port}/oauth",
            authType = ApiConnectorAuthType.OAUTH2,
            oauth = ApiOAuth2Configuration(
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "client",
            ),
        )
        val action = ApiConnectorAction(
            name = "read",
            description = "Read",
            method = ApiHttpMethod.GET,
            path = "/read",
            inputSchemaJson = "{\"type\":\"object\"}",
            enabled = true,
            destructive = false,
        )
        val executor = ApiToolExecutor(
            context = RuntimeEnvironment.getApplication(),
            snapshot = ApiConnectorSnapshot(listOf(ApiConnectorRunTool("oauth_read", connector, action))),
            oauthAccessTokenProvider = { _, forceRefresh ->
                refreshRequests += forceRefresh
                if (forceRefresh) "fresh" else "stale"
            },
        )

        ApiConnectorStore(RuntimeEnvironment.getApplication()).upsert(connector.copy(actions = listOf(action)))

        val result = executor.execute(
            AgentModelClient.ToolCall("retry", "oauth_read", "{}"),
        )

        val payload = JSONObject(result.content)
        assertTrue(payload.getBoolean("ok"))
        assertEquals(200, payload.getInt("http_status"))
        assertEquals(listOf("Bearer stale", "Bearer fresh"), authorizations)
        assertEquals(listOf(false, true), refreshRequests)
    }

    @Test fun cancellingTheRunClosesAnInFlightBodyAndRejectsAnyLaterExternalCall() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finishServer = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/blocking") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write('{'.code); exchange.responseBody.flush(); entered.countDown()
                finishServer.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        val connector = ApiConnectorDefinition("cancel-test", "Blocking API", baseUrl = "http://127.0.0.1:${server.address.port}/blocking")
        val action = ApiConnectorAction("read", "Read", ApiHttpMethod.GET, "/read", "{\"type\":\"object\"}", enabled = true, destructive = false)
        ApiConnectorStore(RuntimeEnvironment.getApplication()).upsert(connector.copy(actions = listOf(action)))
        val executor = ApiToolExecutor(RuntimeEnvironment.getApplication(), ApiConnectorSnapshot(listOf(ApiConnectorRunTool("api_blocking", connector, action))))
        val router = io.github.mangi.eta.agent.mcp.RoutingToolExecutor(AgentModelClient.ToolExecutor { error("Unexpected local call") }, listOf(executor))
        val controller = io.github.mangi.eta.agent.runtime.AgentRunController()
        val binding = controller.register(router::close)
        val call = AgentModelClient.ToolCall("call", "api_blocking", "{}")
        val result = java.util.concurrent.FutureTask { router.execute(call) }
        val worker = Thread(result, "api-cancel-test").apply { start() }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            controller.cancel()
            val response = JSONObject(result.get(3, java.util.concurrent.TimeUnit.SECONDS).content)
            assertFalse(response.getBoolean("ok"))
            assertEquals("API_EXECUTOR_CLOSED", response.getString("code"))
            assertEquals("API_EXECUTOR_CLOSED", JSONObject(router.execute(call.copy(id = "later")).content).getString("code"))
            assertEquals(1, calls.get())
        } finally { binding.close(); router.close(); finishServer.countDown(); worker.join(3000) }
    }

    @Test fun disconnectRevokesCredentialFromAnAlreadyCreatedRunSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val secrets = McpSecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
        val connector = ApiConnectorDefinition(
            id = "api-revoked-snapshot",
            name = "Revoked API",
            baseUrl = "http://127.0.0.1:${server.address.port}/api",
            authType = ApiConnectorAuthType.BEARER,
        )
        ApiConnectorStore(context).upsert(connector)
        secrets.setBearerToken(apiConnectorSecretKey(connector.id), "private-token")
        val action = ApiConnectorAction("read", "Read", ApiHttpMethod.GET, "/projects/item",
            "{\"type\":\"object\"}", enabled = true, destructive = false)
        val executor = ApiToolExecutor(context, ApiConnectorSnapshot(listOf(ApiConnectorRunTool("revoked_read", connector, action))), secretStore = secrets)

        ApiConnectorService(context, connectorStore = ApiConnectorStore(context), secretStore = secrets).disconnect(connector.id)
        val result = JSONObject(executor.execute(AgentModelClient.ToolCall("after-disconnect", "revoked_read", "{}")).content)

        assertFalse(result.getBoolean("ok"))
        assertEquals("API_CREDENTIAL_REVOKED", result.getString("code"))
        assertEquals(null, request.get())
        executor.close()
    }

    @Test fun deletingAnonymousConnectorRevokesAnExistingSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val action = ApiConnectorAction("read", "Read", ApiHttpMethod.GET, "/projects/item",
            "{\"type\":\"object\"}", enabled = true, destructive = false)
        val connector = ApiConnectorDefinition("anonymous-snapshot", "Anonymous API",
            baseUrl = "http://127.0.0.1:${server.address.port}/api", actions = listOf(action))
        val store = ApiConnectorStore(context)
        store.upsert(connector)
        ApiToolExecutor(context, ApiConnectorSnapshot(listOf(ApiConnectorRunTool("anonymous_read", connector, action)))).use { executor ->
            store.delete(connector.id)
            val result = JSONObject(executor.execute(AgentModelClient.ToolCall("deleted", "anonymous_read", "{}")).content)
            assertEquals("API_CREDENTIAL_REVOKED", result.getString("code"))
            assertEquals(null, request.get())
        }
    }

    @Test fun removingConnectorDuring401ResponsePreventsRefreshAndRetry() {
        val context = RuntimeEnvironment.getApplication()
        val store = ApiConnectorStore(context)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val action = ApiConnectorAction("read", "Read", ApiHttpMethod.GET, "/read", "{\"type\":\"object\"}", enabled = true, destructive = false)
        val connector = ApiConnectorDefinition("oauth-remove-on-401", "OAuth API", baseUrl = "http://127.0.0.1:${server.address.port}/revoke",
            authType = ApiConnectorAuthType.OAUTH2, oauth = ApiOAuth2Configuration("https://auth.example.com/authorize", "https://auth.example.com/token", "client"), actions = listOf(action))
        store.upsert(connector)
        server.createContext("/revoke") { exchange ->
            calls.incrementAndGet()
            store.delete(connector.id)
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        ApiToolExecutor(context, ApiConnectorSnapshot(listOf(ApiConnectorRunTool("oauth_revoke", connector, action))),
            oauthAccessTokenProvider = { _, force -> if (force) refreshes.incrementAndGet(); "old-token" }).use { executor ->
            val result = JSONObject(executor.execute(AgentModelClient.ToolCall("revoked", "oauth_revoke", "{}")).content)
            assertEquals("API_CREDENTIAL_REVOKED", result.getString("code"))
            assertEquals(1, calls.get())
            assertEquals(0, refreshes.get())
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val trace: String?,
        val body: String,
    )
}
