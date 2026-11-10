package io.github.mangi.eta.agent.memory

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryExtractionHttpTest {
    @Test fun streamedExtractionUsesRealProviderWithoutToolsAndReturnsVerifiableCandidate() {
        val captured = AtomicReference<JSONObject>()
        val source = "以后请使用中文回答，先给结论，再给必要的解释。"
        val item = JSONObject().put("target", "memory").put("scope", "global").put("layer", "core")
            .put("kind", "preference").put("topic", "回复语言").put("content", "用户偏好中文回答")
            .put("evidence", "以后请使用中文回答")
        val result = JSONObject().put("items", JSONArray().put(item)).toString()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            captured.set(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }))
            val choice = JSONObject().put("index", 0).put("delta", JSONObject().put("content", result)).put("finish_reason", "stop")
            val body = ("data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\ndata: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val config = AgentModelClient.ModelConfig(baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "fixture", model = "fixture", systemPrompt = "",
                hostedWebSearchEnabled = true, extraBodyJson = """{"tools":[{"type":"web_search"}]}""",
                customBody = listOf(CustomBody("messages", JsonPrimitive("BAD_MESSAGES"))))
            val entries = MemoryAutomaticExtractor.extract(config, source, listOf("global"), AgentRunController())
            assertEquals("用户偏好中文回答", entries.single().content)
            assertEquals("以后请使用中文回答", entries.single().evidence)
            val request = requireNotNull(captured.get())
            assertFalse(request.has("tools"))
            assertFalse(request.toString().contains("BAD_MESSAGES"))
            assertTrue(request.getJSONArray("messages").toString().contains(source))
        } finally { server.stop(0) }
    }
}
