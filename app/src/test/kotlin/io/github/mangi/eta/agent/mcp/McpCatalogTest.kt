package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpCatalogTest {
    @Test fun resourceOnlyServerPaginatesAndPromptPreviewUsesDeclaredArguments() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        withServer({ exchange, request ->
            requests += request
            val params = request.getJSONObject("params")
            val result = when (request.getString("method")) {
                "resources/list" -> if (!params.has("cursor")) {
                    JSONObject("""{"resources":[{"uri":"memo://one"}],"nextCursor":"next"}""")
                } else {
                    assertEquals("next", params.getString("cursor"))
                    JSONObject("""{"resources":[{"uri":"memo://two"}]}""")
                }
                "prompts/list" -> JSONObject("""{"prompts":[{"name":"summarize","arguments":[{"name":"topic","required":true,"description":"Subject"},{"name":"style"}]}]}""")
                "prompts/get" -> {
                    assertEquals("summarize", params.getString("name"))
                    assertEquals("中文🙂", params.getJSONObject("arguments").getString("topic"))
                    assertFalse(params.getJSONObject("arguments").has("style"))
                    JSONObject("""{"messages":[{"role":"user","content":{"type":"text","text":"preview"}}]}""")
                }
                else -> error("unexpected method ${request.getString("method")}")
            }
            exchange.result(request, result.put("resultType", "complete"))
        }) { configured ->
            McpHttpClient(configured, null).use { client ->
                val catalog = client.discoverCatalog()
                assertEquals(listOf("memo://one", "memo://two"), catalog.resources.map { it.uri })
                assertFalse(catalog.resourcesTruncated)
                val prompt = catalog.prompts.single()
                assertEquals("Subject", prompt.argumentDefinitions.first().description)
                val requestCount = requests.size
                assertThrows(IllegalArgumentException::class.java) { client.getPrompt(prompt, emptyMap()) }
                assertThrows(IllegalArgumentException::class.java) { client.getPrompt(prompt, mapOf("other" to "value")) }
                assertThrows(IllegalArgumentException::class.java) { client.getPrompt(prompt, mapOf("topic" to "中".repeat(50_000))) }
                assertEquals(requestCount, requests.size)
                assertEquals("[user]\npreview", client.getPrompt(prompt, mapOf("topic" to "中文🙂")).text)
            }
        }
        assertFalse(requests.any { it.optString("method") == "tools/list" })
    }

    @Test fun onlyMethodNotFoundMeansUnsupported() {
        withServer({ exchange, request ->
            if (request.getString("method") == "resources/list") exchange.rpcError(request, -32601)
            else exchange.result(request, JSONObject("""{"resultType":"complete","prompts":[]}"""))
        }) { configured ->
            McpHttpClient(configured, null).use {
                val catalog = it.discoverCatalog()
                assertFalse(catalog.resourcesSupported)
                assertTrue(catalog.promptsSupported)
            }
        }
        for (code in listOf(401, 403, 500)) {
            withServer({ exchange, _ -> exchange.respond(code, "failure") }) { configured ->
                McpHttpClient(configured, null).use {
                    val error = assertThrows(McpHttpStatusException::class.java) { it.discoverCatalog() }
                    assertEquals(code, error.code)
                }
            }
        }
        withServer({ exchange, request -> exchange.rpcError(request, -32602) }) { configured ->
            McpHttpClient(configured, null).use {
                assertThrows(McpJsonRpcException::class.java) { it.discoverCatalog() }
            }
        }
    }

    @Test fun malformedCatalogFailsRatherThanLookingEmptyOrUnsupported() {
        for (result in listOf(
            """{"resultType":"complete"}""",
            """{"resultType":"pending","resources":[]}""",
            """{"resultType":"complete","resources":[{"uri":123}]}""",
            JSONObject().put("resultType", "complete").put("resources", JSONArray().put(JSONObject().put("uri", "x".repeat(4097)))).toString(),
        )) {
            withServer({ exchange, request -> exchange.result(request, JSONObject(result)) }) { configured ->
                McpHttpClient(configured, null).use { client ->
                    assertNotNull(runCatching { client.discoverCatalog() }.exceptionOrNull())
                }
            }
        }
    }

    @Test fun repeatedCursorIsRejectedAndPageLimitIsVisible() {
        val pageCount = AtomicInteger()
        withServer({ exchange, request ->
            pageCount.incrementAndGet()
            exchange.result(request, JSONObject("""{"resultType":"complete","resources":[],"nextCursor":"same"}"""))
        }) { configured ->
            McpHttpClient(configured, null).use {
                assertThrows(IllegalArgumentException::class.java) { it.discoverCatalog() }
            }
        }
        assertEquals(2, pageCount.get())
        pageCount.set(0)
        withServer({ exchange, request ->
            val result = if (request.getString("method") == "resources/list") {
                val index = pageCount.incrementAndGet()
                JSONObject("""{"resources":[{"uri":"memo://$index"}],"nextCursor":"page-$index"}""")
            } else JSONObject("""{"prompts":[]}""")
            exchange.result(request, result.put("resultType", "complete"))
        }) { configured ->
            McpHttpClient(configured, null).use {
                val catalog = it.discoverCatalog()
                assertEquals(8, catalog.resources.size)
                assertTrue(catalog.resourcesTruncated)
                assertFalse(catalog.promptsTruncated)
            }
        }
    }

    @Test fun itemLimitDoesNotClaimFullListing() {
        for (count in listOf(128, 129)) {
            withServer({ exchange, request ->
                val result = if (request.getString("method") == "resources/list") {
                    JSONObject().put("resources", JSONArray().apply {
                        repeat(count) { put(JSONObject().put("uri", "memo://$it")) }
                    })
                } else JSONObject("""{"prompts":[]}""")
                exchange.result(request, result.put("resultType", "complete"))
            }) { configured ->
                McpHttpClient(configured, null).use {
                    val catalog = it.discoverCatalog()
                    assertEquals(128, catalog.resources.size)
                    assertEquals(count > 128, catalog.resourcesTruncated)
                }
            }
        }
    }

    @Test fun invalidPromptIdentifiersAndArgumentDefinitionsFail() {
        val invalid = listOf(
            JSONObject().put("name", "x".repeat(161)),
            JSONObject("""{"name":"summary","arguments":[{"name":"topic"},{"name":"topic"}]}"""),
            JSONObject("""{"name":"summary","arguments":[{"name":"topic","required":"yes"}]}"""),
        )
        invalid.forEach { prompt ->
            withServer({ exchange, request ->
                val result = if (request.getString("method") == "resources/list") JSONObject("""{"resources":[]}""")
                else JSONObject().put("prompts", JSONArray().put(prompt))
                exchange.result(request, result.put("resultType", "complete"))
            }) { configured ->
                McpHttpClient(configured, null).use {
                    assertThrows(IllegalArgumentException::class.java) { it.discoverCatalog() }
                }
            }
        }
    }

    @Test fun legacyDirectReadInitializesAndRecoversExpiredSessionOnce() {
        val initializations = AtomicInteger()
        val reads = AtomicInteger()
        withServer({ exchange, request ->
            when (request.getString("method")) {
                "initialize" -> {
                    val index = initializations.incrementAndGet()
                    exchange.responseHeaders.set("Mcp-Session-Id", "session-$index")
                    exchange.result(request, JSONObject("""{"protocolVersion":"2025-11-25","capabilities":{"resources":{}}}"""))
                }
                "notifications/initialized" -> exchange.respond(202, "")
                "resources/read" -> {
                    val index = reads.incrementAndGet()
                    assertEquals("session-$index", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                    if (index == 1) exchange.respond(404, "expired")
                    else exchange.result(request, JSONObject("""{"contents":[{"text":"recovered"}]}"""))
                }
                else -> error("unexpected request")
            }
        }) { configured ->
            McpHttpClient(configured.copy(protocolMode = McpProtocolMode.LEGACY), null).use {
                assertEquals("recovered", it.readResource("memo://one"))
            }
        }
        assertEquals(2, initializations.get())
        assertEquals(2, reads.get())
    }

    @Test fun autoCatalogNegotiatesLegacyWithoutRequiringTools() {
        val methods = CopyOnWriteArrayList<String>()
        withServer({ exchange, request ->
            val method = request.getString("method")
            methods += method
            when (method) {
                "initialize" -> exchange.result(request, JSONObject("""{"protocolVersion":"2025-11-25","capabilities":{"resources":{}}}"""))
                "notifications/initialized" -> exchange.respond(202, "")
                "resources/list" -> if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) {
                    exchange.respond(400, "initialize first")
                } else exchange.result(request, JSONObject("""{"resources":[{"uri":"memo://legacy"}]}"""))
                else -> error("unadvertised capability requested: $method")
            }
        }) { configured ->
            McpHttpClient(configured, null).use {
                val catalog = it.discoverCatalog()
                assertEquals("memo://legacy", catalog.resources.single().uri)
                assertFalse(catalog.promptsSupported)
            }
        }
        assertEquals(listOf("resources/list", "initialize", "notifications/initialized", "resources/list"), methods)
    }

    @Test fun explicitProtocolModeOverridesCachedVersion() {
        withServer({ exchange, request ->
            assertEquals("resources/read", request.getString("method"))
            assertEquals(McpProtocolMode.LATEST, exchange.requestHeaders.getFirst("MCP-Protocol-Version"))
            exchange.result(request, JSONObject("""{"resultType":"complete","contents":[{"text":"modern"}]}"""))
        }) { configured ->
            McpHttpClient(configured.copy(protocolMode = McpProtocolMode.LATEST, lastProtocolVersion = McpProtocolMode.LEGACY), null).use {
                assertEquals("modern", it.readResource("memo://one"))
            }
        }
    }

    @Test fun cancellingInspectionClosesStalledHttpAndSseReads() = runBlocking {
        for (sse in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            withServer({ exchange, _ ->
                if (sse) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(": keepalive\n\n".toByteArray())
                    exchange.responseBody.flush()
                }
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            }) { configured ->
                try {
                    val job = launch(Dispatchers.Default) {
                        inspectMcp(configured, null) {
                            try { it.readResource("memo://one") } finally { finished.countDown() }
                        }
                    }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    withTimeout(2000) { job.cancelAndJoin() }
                    assertTrue("HTTP operation did not stop after cancellation", finished.await(2, TimeUnit.SECONDS))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun inspectionDeadlineCancelsKeepaliveStream() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        withServer({ exchange, _ ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(": keepalive\n\n".toByteArray())
            exchange.responseBody.flush()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }) { configured ->
            try {
                var timedOut = false
                try {
                    inspectMcp(configured, null, timeoutMillis = 1000L) {
                        try { it.readResource("memo://one") } finally { finished.countDown() }
                    }
                } catch (_: TimeoutCancellationException) { timedOut = true }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                assertTrue(timedOut)
                assertTrue(finished.await(2, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }

    private inline fun withServer(
        crossinline handler: (HttpExchange, JSONObject) -> Unit,
        block: (McpServerSetting) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                if (exchange.requestMethod == "DELETE") exchange.respond(200, "")
                else handler(exchange, JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }))
            }
            start()
        }
        try {
            block(McpServerSetting(id = "test", name = "Test", url = "http://127.0.0.1:${server.address.port}/mcp"))
        } finally { server.stop(0) }
    }

    private fun HttpExchange.result(request: JSONObject, result: JSONObject) = respond(
        200, JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result).toString(),
    )

    private fun HttpExchange.rpcError(request: JSONObject, code: Int) = respond(
        200, JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
            .put("error", JSONObject().put("code", code).put("message", "test failure")).toString(),
    )

    private fun HttpExchange.respond(code: Int, body: String) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
