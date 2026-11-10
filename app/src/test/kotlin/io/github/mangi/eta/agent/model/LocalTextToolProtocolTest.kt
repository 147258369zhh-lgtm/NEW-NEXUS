package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextToolProtocolTest {
    @Test
    fun `all bundled text engines advertise the emulated tool call stream`() {
        assertTrue(LiteRtLmAgentProvider.capabilities.streamingToolCalls)
        assertTrue(LlamaCppAgentProvider.capabilities.streamingToolCalls)
        assertTrue(MnnAgentProvider.capabilities.streamingToolCalls)
    }

    @Test
    fun `projection publishes tools and hides marker fragments from streaming text`() {
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是 Eta。"))
            .put(JSONObject().put("role", "user").put("content", "帮我搜索最新的安卓资料"))
        val tools = JSONArray().put(tool("web_search", "搜索公开网页并返回标题、链接和摘要")).put(tool("get_current_context", "读取当前时间"))

        val (projected, exposed) = LocalTextToolProtocol.projectMessages(source, tools, 4_096)

        assertTrue(exposed.contains("web_search"))
        val system = projected.getJSONObject(0).getString("content")
        assertTrue(system.contains("<eta_tool_call>"))
        assertTrue(system.contains("web_search"))

        val filter = LocalTextToolProtocol.StreamingFilter()
        assertEquals("先说结论", filter.accept("先说结论<eta_"))
        assertEquals("", filter.accept("tool_call>{\"name\":\"web_search\"}"))
    }

    @Test
    fun `canonical marker produces multiple executable calls`() {
        val raw = "我先读取两项信息。\n" +
            "<eta_tool_call>{\"name\":\"get_current_context\",\"arguments\":{\"detail\":\"time\"}}</eta_tool_call>\n" +
            "<eta_tool_call>{\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"Android 17\\\"}\"}}</eta_tool_call>"

        val decoded = LocalTextToolProtocol.decode(raw, setOf("get_current_context", "web_search"))

        assertEquals("我先读取两项信息。", decoded.visibleText)
        assertEquals(listOf("get_current_context", "web_search"), decoded.calls.map { it.name })
        assertEquals("Android 17", JSONObject(decoded.calls[1].argumentsJson).getString("query"))
        val assistant = LocalTextToolProtocol.assistantMessage(decoded, "stop")
        assertEquals("tool_calls", assistant.getString("finish_reason"))
        assertEquals(2, assistant.getJSONArray("tool_calls").length())
    }

    @Test
    fun `common chat template wrappers and fenced JSON are executable`() {
        val wrapped = LocalTextToolProtocol.decode(
            "<function_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"Eta\"}}</function_call>",
            setOf("web_search"),
        )
        assertEquals("web_search", wrapped.calls.single().name)

        val fenced = LocalTextToolProtocol.decode(
            "我来查询。\n```json\n{\"name\":\"web_search\",\"arguments\":{\"query\":\"Android 17\"}}\n```",
            setOf("web_search"),
        )
        assertEquals("我来查询。", fenced.visibleText)
        assertEquals("web_search", fenced.calls.single().name)
        assertEquals("Android 17", JSONObject(fenced.calls.single().argumentsJson).getString("query"))
    }

    @Test
    fun `ordinary fenced JSON is not executed as a tool`() {
        val decoded = LocalTextToolProtocol.decode(
            "示例：\n```json\n{\"name\":\"project\",\"version\":1}\n```",
            setOf("web_search"),
        )

        assertTrue(decoded.calls.isEmpty())
        assertTrue(decoded.visibleText.contains("project"))
    }

    @Test
    fun `device intent exposes the required launch tool in a small context`() {
        val tools = JSONArray().put(tool("launch_app", "打开或启动一个已安装应用"))
        repeat(30) { index -> tools.put(tool("noise_$index", "无关工具${'x'.toString().repeat(150)}")) }
        val source = JSONArray().put(JSONObject().put("role", "user").put("content", "帮我打开微信"))

        val (projected, exposed) = LocalTextToolProtocol.projectMessages(source, tools, 4_096)

        assertTrue(exposed.contains("launch_app"))
        assertTrue(projected.getJSONObject(0).getString("content").contains("launch_app"))
    }

    @Test
    fun `clear local device commands bootstrap a real tool call before inference`() {
        val tools = JSONArray()
            .put(tool("get_current_context", "读取当前时间"))
            .put(tool("launch_app", "启动应用"))
            .put(tool("web_search", "搜索网页"))

        val launch = LocalTextToolProtocol.bootstrapCall(
            JSONArray().put(JSONObject().put("role", "user").put("content", "帮我打开微信")),
            tools,
        )
        assertEquals("launch_app", launch?.name)
        assertEquals("微信", JSONObject(launch!!.argumentsJson).getString("app_name"))

        val time = LocalTextToolProtocol.bootstrapCall(
            JSONArray().put(JSONObject().put("role", "user").put("content", "现在几点？")),
            tools,
        )
        assertEquals("get_current_context", time?.name)

        val search = LocalTextToolProtocol.bootstrapCall(
            JSONArray().put(JSONObject().put("role", "user").put("content", "搜索 Android 17 新功能")),
            tools,
        )
        assertEquals("web_search", search?.name)
        assertEquals("Android 17 新功能", JSONObject(search!!.argumentsJson).getString("query"))
    }

    @Test
    fun `negated commands are not executed by the bootstrap`() {
        val tools = JSONArray().put(tool("launch_app", "启动应用"))
        val call = LocalTextToolProtocol.bootstrapCall(
            JSONArray().put(JSONObject().put("role", "user").put("content", "不要打开微信")),
            tools,
        )

        assertEquals(null, call)
    }

    @Test
    fun `bootstrap never replays an earlier user command after a tool result`() {
        val tools = JSONArray().put(tool("launch_app", "启动应用"))
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "打开微信"))
            .put(JSONObject().put("role", "tool").put("content", "{\"ok\":true}"))

        assertEquals(null, LocalTextToolProtocol.bootstrapCall(messages, tools))
    }

    @Test
    fun `local provider returns a tool-use response for a bootstrapped command`() {
        val tools = JSONArray().put(tool("get_current_context", "读取当前时间"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "现在几点？"))
        val request = ProviderRequest(
            config = AgentModelClient.ModelConfig(
                baseUrl = "builtin://litert",
                apiKey = "",
                model = "record",
                systemPrompt = "你是 Eta。",
                supportsTools = true,
            ),
            messages = messages,
            tools = tools,
        )
        val events = mutableListOf<ProviderEvent>()

        val response = LiteRtLmAgentProvider.complete(request, AgentRunController(), events::add)

        assertEquals(AssistantStopReason.TOOL_USE, response.stopReason)
        assertEquals(
            "get_current_context",
            response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("name"),
        )
        assertTrue(events.any { it is ProviderEvent.BlockEnd && it.kind == AssistantBlockKind.TOOL_CALL })
        assertTrue(events.any { it is ProviderEvent.Completed && it.reason == "tool_calls" })
    }

    @Test
    fun `plain response remains visible and is not treated as a tool call`() {
        val decoded = LocalTextToolProtocol.decode("这是普通回答。", setOf("web_search"))

        assertEquals("这是普通回答。", decoded.visibleText)
        assertTrue(decoded.calls.isEmpty())
    }

    @Test
    fun `malformed or undeclared calls are rejected before execution`() {
        val malformed = assertThrows(AgentModelFailure::class.java) {
            LocalTextToolProtocol.decode("<eta_tool_call>{broken}</eta_tool_call>", setOf("web_search"))
        }
        assertEquals("LOCAL_TOOL_PROTOCOL_INVALID", malformed.code)

        val undeclared = assertThrows(AgentModelFailure::class.java) {
            LocalTextToolProtocol.decode(
                "<eta_tool_call>{\"name\":\"delete_everything\",\"arguments\":{}}</eta_tool_call>",
                setOf("web_search"),
            )
        }
        assertEquals("LOCAL_TOOL_NOT_AVAILABLE", undeclared.code)

        val incomplete = assertThrows(AgentModelFailure::class.java) {
            LocalTextToolProtocol.decode("<eta_tool_call>{\"name\":\"web_search\"}", setOf("web_search"))
        }
        assertEquals("LOCAL_TOOL_PROTOCOL_INCOMPLETE", incomplete.code)
    }

    @Test
    fun `tool results continue as ordinary runtime turns without OpenAI tool role`() {
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统"))
            .put(JSONObject().put("role", "user").put("content", "查一下"))
            .put(
                JSONObject().put("role", "assistant").put("content", "")
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject().put("id", "call-1").put("type", "function")
                                .put("function", JSONObject().put("name", "web_search").put("arguments", "{\"query\":\"Eta\"}")),
                        ),
                    ),
            )
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "{\"ok\":true}"))

        val (projected, _) = LocalTextToolProtocol.projectMessages(
            source,
            JSONArray().put(tool("web_search", "搜索网页")),
            8_192,
        )

        val assistant = projected.getJSONObject(2)
        assertTrue(assistant.getString("content").contains("<eta_tool_call>"))
        assertEquals("user", projected.getJSONObject(3).getString("role"))
        assertTrue(projected.getJSONObject(3).getString("content").contains("工具结果"))
        assertFalse(projected.toString().contains("\"role\":\"tool\""))
    }

    @Test
    fun `local runtime history is normalized to alternating user and assistant roles`() {
        val tools = JSONArray().put(tool("web_search", "搜索网页"))
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统"))
            .put(JSONObject().put("role", "user").put("content", "上一轮请求"))
            .put(JSONObject().put("role", "user").put("content", "后置提示词"))
            .put(JSONObject().put("role", "user").put("content", "当前问题"))
            .put(JSONObject().put("role", "user").put("content", "补充条件"))
            .put(JSONObject().put("role", "assistant").put("content", "回答"))
            .put(JSONObject().put("role", "assistant").put("content", "继续回答"))
            .put(JSONObject().put("role", "tool").put("content", "工具结果一"))
            .put(JSONObject().put("role", "tool").put("content", "工具结果二"))

        val (projected, _) = LocalTextToolProtocol.projectMessages(source, tools, 8_192)
        val roles = (0 until projected.length()).map { projected.getJSONObject(it).getString("role") }

        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertTrue(projected.getJSONObject(1).getString("content").contains("当前问题"))
        assertTrue(projected.getJSONObject(2).getString("content").contains("继续回答"))
        assertTrue(projected.getJSONObject(3).getString("content").contains("工具结果二"))
    }

    @Test
    fun `assistant welcome is preceded by a neutral user turn`() {
        val source = JSONArray()
            .put(JSONObject().put("role", "assistant").put("content", "欢迎回来。"))
            .put(JSONObject().put("role", "user").put("content", "继续任务"))

        val (projected, _) = LocalTextToolProtocol.projectMessages(source, JSONArray(), 4_096)

        assertEquals("user", projected.getJSONObject(0).getString("role"))
        assertEquals("assistant", projected.getJSONObject(1).getString("role"))
        assertEquals("user", projected.getJSONObject(2).getString("role"))
    }

    private fun tool(name: String, description: String): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("query", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("query")),
                ),
        )
}
