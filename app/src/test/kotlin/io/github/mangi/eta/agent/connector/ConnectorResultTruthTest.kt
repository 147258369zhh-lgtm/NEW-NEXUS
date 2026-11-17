package io.github.mangi.eta.agent.connector

import android.app.Application
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.permission.AgentPermissionPolicy
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AgentPermissionMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConnectorResultTruthTest {
    @Test fun actualJsonRpcInvocationPreservesRemoteErrorWithoutADiscoveryOrActionRetry() = runBlocking {
        HttpFixture { request, _ -> 200 to JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
            .put("error", JSONObject().put("code", -32017).put("message", "Fixture failure").put("data", JSONObject().put("retryable", false)))
        }.use { fixture -> withFullAccess {
            val result = call("jsonrpc", fixture.url, JSONObject().put("method", "change-fixture"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("JSON_RPC_ERROR", result.getString("code"))
            val error = result.getJSONObject("result").getJSONObject("error")
            assertEquals(-32017, error.getInt("code"))
            assertFalse(error.getJSONObject("data").getBoolean("retryable"))
            assertEquals(listOf("change-fixture"), fixture.requests.map { it.getString("method") })
            val probe = call("jsonrpc", fixture.url, JSONObject(), "probe")
            assertTrue(probe.getBoolean("ok"))
            assertEquals(listOf("change-fixture", "rpc.discover"), fixture.requests.map { it.getString("method") })
        } }
    }

    @Test fun jsonRpcRejectsWrongVersionsIdsAndAmbiguousEnvelopesButAcceptsANullResult() = runBlocking {
        HttpFixture { request, _ ->
            val body = JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id")).put("result", JSONObject.NULL)
            when (request.getString("method")) {
                "version" -> body.put("jsonrpc", "1.0")
                "id" -> body.put("id", "another-request")
                "both" -> body.put("error", JSONObject().put("code", -1).put("message", "both"))
                "neither" -> body.remove("result")
                "bad-error" -> { body.remove("result"); body.put("error", JSONObject().put("code", "string").put("message", "bad")) }
            }
            200 to body
        }.use { fixture ->
            JsonRpcConnectorTransport(fixture.url, null, ConnectorSecurityManager()).use { transport ->
                listOf("version", "id", "both", "neither", "bad-error").forEach { method ->
                    val result = transport.invoke(ConnectorInvocation("call", JSONObject().put("method", method).put("confirmed", true)))
                    assertFalse(result.success)
                    assertEquals("INVALID_JSON_RPC_RESPONSE", result.errorCode)
                    assertFalse(ConnectorTool.resultEnvelope("jsonrpc", result).getBoolean("ok"))
                }
                val valid = transport.invoke(ConnectorInvocation("call", JSONObject().put("method", "null-result").put("confirmed", true)))
                assertTrue(valid.success)
                assertTrue(valid.json.has("result")); assertTrue(valid.json.isNull("result"))
                assertEquals(6, fixture.requests.size)
            }
        }
    }

    @Test fun actualGraphQlInvocationKeepsPartialDataAndErrorsWithoutAnImplicitTypenameProbe() = runBlocking {
        HttpFixture { _, _ -> 200 to JSONObject().put("data", JSONObject().put("available", 7).put("restricted", JSONObject.NULL))
            .put("errors", JSONArray().put(JSONObject().put("message", "Fixture field denied").put("path", JSONArray().put("restricted"))))
        }.use { fixture -> withFullAccess {
            val query = "query Fixture { available restricted }"
            val result = call("graphql", fixture.url, JSONObject().put("query", query))
            assertFalse(result.getBoolean("ok")); assertEquals("GRAPHQL_ERROR", result.getString("code"))
            assertEquals(7, result.getJSONObject("result").getJSONObject("data").getInt("available"))
            assertEquals("restricted", result.getJSONObject("result").getJSONArray("errors").getJSONObject(0).getJSONArray("path").getString(0))
            assertEquals(listOf(query), fixture.requests.map { it.getString("query") })
            assertTrue(call("graphql", fixture.url, JSONObject(), "probe").getBoolean("ok"))
            assertEquals(2, fixture.requests.size)
            assertEquals("query Query { __typename }", fixture.requests.last().getString("query"))
        } }
    }

    @Test fun restHttpErrorsKeepTheirBodyAndSuccessfulBusinessErrorFieldsAreNotGuessedAsFailures() = runBlocking {
        HttpFixture { _, exchange -> (if (exchange.requestURI.path == "/error") 422 else 200) to JSONObject().put("error", "fixture-business-field")
        }.use { fixture -> withFullAccess {
            val failed = call("rest", fixture.url + "error", JSONObject())
            assertFalse(failed.getBoolean("ok")); assertEquals("HTTP_422", failed.getString("code"))
            assertEquals(422, failed.getJSONObject("result").getInt("statusCode"))
            assertEquals("fixture-business-field", JSONObject(failed.getJSONObject("result").getString("content")).getString("error"))
            val successful = call("rest", fixture.url + "ok", JSONObject())
            assertTrue(successful.getBoolean("ok"))
            assertFalse(successful.has("code"))
            assertEquals(2, fixture.requests.size)
        } }
    }

    private fun call(protocol: String, endpoint: String, arguments: JSONObject, action: String = "invoke") = ConnectorTool.execute(
        RuntimeEnvironment.getApplication(), JSONObject().put("protocol", protocol).put("endpoint", endpoint).put("action", action).put("arguments", arguments))

    private suspend fun withFullAccess(block: () -> Unit) {
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val selected = AgentPermissionPolicy.mode()
        try { AgentPermissionPolicy.setMode(AgentPermissionMode.FULL_ACCESS); block() }
        finally { AgentPermissionPolicy.setMode(selected) }
    }

    private class HttpFixture(handler: (JSONObject, HttpExchange) -> Pair<Int, JSONObject>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JSONObject>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                val text = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val request = if (text.isBlank()) JSONObject() else JSONObject(text)
                requests += request
                val (status, body) = handler(request, exchange)
                val bytes = body.toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/"
        override fun close() { server.stop(0); (server.executor as ExecutorService).shutdownNow() }
    }
}
