package io.github.mangi.eta.agent.mcp

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.McpServerRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class McpFirstUseTest {
    @Test fun executorValidatesResultAgainstNegotiatedProtocolInsteadOfStaleSavedVersion() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                val result = when (request.getString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", McpProtocolMode.LEGACY).put("capabilities", JSONObject())
                    "notifications/initialized" -> { exchange.sendResponseHeaders(202, -1); exchange.close(); return@createContext }
                    "tools/call" -> {
                        calls.incrementAndGet()
                        JSONObject().put("content", org.json.JSONArray()).apply {
                            if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) put("resultType", "complete")
                        }
                    }
                    else -> error("Unexpected method")
                }
                val bytes = JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result).toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val definition = McpToolDefinition("read", inputSchemaJson = """{"type":"object"}""")
            for (mode in listOf(McpProtocolMode.LEGACY, McpProtocolMode.LATEST)) {
                val setting = McpServerSetting("mode", "Mode", "http://127.0.0.1:${server.address.port}/mcp", protocolMode = mode,
                    lastProtocolVersion = if (mode == McpProtocolMode.LEGACY) McpProtocolMode.LATEST else McpProtocolMode.LEGACY)
                McpToolExecutor(McpRunSnapshot(listOf(McpRunTool("mcp_mode", setting, definition, null))), available = { true }).use { executor ->
                    val result = executor.execute(AgentModelClient.ToolCall("mode", "mcp_mode", "{}"))
                    assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
                }
            }
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun stdioResourceAndPromptInitializeBeforeTheirFirstRequest() {
        for (method in listOf("resources/read", "prompts/get")) {
            val output = ByteArrayOutputStream()
            val process = object : Process() {
                override fun getOutputStream() = output
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() {}
                override fun isAlive() = true
            }
            val client = McpStdioClient(McpServerSetting("stdio", "Stdio", "", transportType = McpTransportType.STDIO))
            fun field(name: String) = McpStdioClient::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("process").set(client, process)
            (field("started").get(client) as AtomicBoolean).set(true)
            @Suppress("UNCHECKED_CAST")
            val responses = field("responses").get(client) as LinkedBlockingQueue<JSONObject>
            responses.add(JSONObject("""{"id":1,"result":{"protocolVersion":"2025-11-25"}}"""))
            responses.add(JSONObject(if (method == "resources/read")
                """{"id":2,"result":{"contents":[{"uri":"memo://one","text":"ready"}]}}"""
                else """{"id":2,"result":{"messages":[{"role":"user","content":{"type":"text","text":"ready"}}]}}"""))
            client.use {
                val result = if (method == "resources/read") it.readResourcePreview("memo://one")
                    else it.getPrompt(McpHttpClient.PromptSummary("summarize", "", "", emptyList()), emptyMap())
                assertTrue(result.text.contains("ready"))
                val methods = output.toString("UTF-8").lineSequence().filter(String::isNotBlank)
                    .map { line -> JSONObject(line).getString("method") }.toList()
                assertEquals(listOf("initialize", "notifications/initialized", method), methods)
            }
        }
    }

    @Test fun cancelledMcpCallPropagatesCancellationAndDoesNotReplay() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                requests.incrementAndGet(); started.countDown()
                release.await(4, TimeUnit.SECONDS)
                exchange.close()
            }
            start()
        }
        val worker = Executors.newSingleThreadExecutor()
        val controller = AgentRunController()
        val definition = McpToolDefinition("create_task", inputSchemaJson = """{"type":"object"}""")
        val setting = McpServerSetting("cancel", "Cancel", "http://127.0.0.1:${server.address.port}/mcp",
            lastProtocolVersion = McpProtocolMode.LATEST)
        val executor = McpToolExecutor(McpRunSnapshot(listOf(McpRunTool("mcp_cancel", setting, definition, null))), available = { true })
        try {
            val outcome = worker.submit<Throwable?> { runCatching {
                RuntimeInvocationScope.withController(controller) {
                    executor.execute(AgentModelClient.ToolCall("cancel", "mcp_cancel", "{}"))
                }
            }.exceptionOrNull() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(outcome.get(2, TimeUnit.SECONDS) is AgentRunCancelledException)
            assertEquals(1, requests.get())
        } finally { controller.cancel(); executor.close(); release.countDown(); worker.shutdownNow(); server.stop(0) }
    }

    @Test fun explicitToolProbeDoesNotReplayAfterResponseIsLost() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        McpServerRepository.init(context)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                requests.incrementAndGet()
                exchange.close() // The server could have committed before the response disappeared.
            }
            start()
        }
        val definition = McpToolDefinition("create_task", inputSchemaJson = """{"type":"object"}""")
        val setting = McpServerSetting("probe-first-use", "Probe", "http://127.0.0.1:${server.address.port}/mcp",
            lastProtocolVersion = McpProtocolMode.LATEST, tools = listOf(definition), enabledToolNames = setOf(definition.name))
        try {
            McpServerRepository.addUnconfigured(setting)
            assertTrue(runCatching { McpServerManager.callTool(setting.id, definition.name, JSONObject()) }.isFailure)
            assertEquals(1, requests.get())
        } finally { McpServerRepository.delete(setting.id); server.stop(0) }
    }
}
