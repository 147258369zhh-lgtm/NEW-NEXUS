package io.github.mangi.eta.agent.capability

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class ApiToolExecutorTest {
    private lateinit var server: HttpServer
    private val request = AtomicReference<CapturedRequest>()

    @Before
    fun setUp() {
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

        val result = executor.execute(
            AgentModelClient.ToolCall("retry", "oauth_read", "{}"),
        )

        val payload = JSONObject(result.content)
        assertTrue(payload.getBoolean("ok"))
        assertEquals(200, payload.getInt("http_status"))
        assertEquals(listOf("Bearer stale", "Bearer fresh"), authorizations)
        assertEquals(listOf(false, true), refreshRequests)
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val trace: String?,
        val body: String,
    )
}
