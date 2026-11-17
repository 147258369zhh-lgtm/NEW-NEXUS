package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.capability.NexusLazyToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FirstToolReadinessTest {
    @Test fun signedProvidersPublishAuthorizedToolsBeforeTheFirstSignedCall() {
        for (endpoint in listOf(EndpointKind.ANTHROPIC_MESSAGES, EndpointKind.GEMINI_INTERACTIONS)) {
            val descriptors = listOf("get_current_context", "read_file", "write_file", "mcp_fixture_read")
                .map { descriptor(it) }
            val external = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", "mcp_fixture_read").put("parameters", JSONObject().put("type", "object"))))
            val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(descriptors),
                toolAllowed = { it != "read_file" }, discoveryAllowed = { true }, additionalSchemas = external,
                authorize = { if (it.id == "write_file") CapabilityAuthorization.Denied("REVOKED", "revoked")
                    else CapabilityAuthorization.Allowed })
            val published = mutableListOf<List<String>>()
            val executed = mutableListOf<String>()
            var round = 0
            val provider = object : AgentProviderClient {
                override val id = "signed-fixture"
                override val capabilities = ProviderCapabilities(endpoint, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController,
                    onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    published += names(request.effectiveTools)
                    val response = if (round++ == 0) signedCall(endpoint) else JSONObject()
                        .put("role", "assistant").put("content", "完成").put("finish_reason", "stop")
                    return ProviderResponse(response)
                }
            }
            val result = AgentModelClient.complete(config().copy(terminalTools = true), "读取当前状态", AgentModelClient.ToolExecutor {
                executed += it.name
                AgentModelClient.ToolResult("""{"ok":true}""")
            }, provider = provider, lazyToolCatalog = catalog, additionalTools = external)
            assertEquals(listOf("get_current_context"), executed)
            assertEquals(2, published.size)
            assertEquals(setOf("get_current_context", "mcp_fixture_read"), published.first().toSet())
            assertEquals(published.first(), published.last())
            assertTrue(result.transcript.any { it.role == "tool" && JSONObject(it.content).optBoolean("ok") })
        }
    }

    @Test fun nextConversationTurnCanUsePreviouslyActivatedToolImmediately() {
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(listOf(descriptor("get_current_context"))),
            toolAllowed = { true }, discoveryAllowed = { true })
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = JSONArray()
                .put(call("activate", "tool_activate", """{"tool_id":"get_current_context"}""")).toString()),
            AgentModelClient.ConversationMessage("tool",
                """{"ok":true,"active":true,"tool_id":"get_current_context"}""", toolCallId = "activate"),
            AgentModelClient.ConversationMessage("assistant", "工具已准备"),
        )
        var rounds = 0
        var executions = 0
        val provider = object : AgentProviderClient {
            override val id = "lazy-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertTrue("get_current_context" in names(request.effectiveTools))
                assertTrue("tool_activate" in names(request.effectiveTools))
                return ProviderResponse(if (rounds++ == 0) JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(call("read", "get_current_context")))
                    else JSONObject().put("role", "assistant").put("content", "完成").put("finish_reason", "stop"))
            }
        }
        AgentModelClient.complete(config(), "再读一次", AgentModelClient.ToolExecutor {
            executions++
            AgentModelClient.ToolResult("""{"ok":true}""")
        }, history = history, provider = provider, lazyToolCatalog = catalog)
        assertEquals(1, executions)
    }

    private fun signedCall(endpoint: EndpointKind): JSONObject = JSONObject().put("role", "assistant")
        .put("content", "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().put(call("read", "get_current_context")))
        .also { message ->
            if (endpoint == EndpointKind.GEMINI_INTERACTIONS) GeminiEphemeralState.attachSteps(message, JSONArray()
                .put(JSONObject().put("type", "thought").put("signature", "first-call-fixture"))
                .put(JSONObject().put("type", "function_call").put("id", "read")
                    .put("name", "get_current_context").put("arguments", JSONObject())))
            else AnthropicEphemeralState.attachContentBlocks(message, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "fixture").put("signature", "first-call-fixture"))
                .put(JSONObject().put("type", "tool_use").put("id", "read")
                    .put("name", "get_current_context").put("input", JSONObject())))
        }

    private fun call(id: String, name: String, arguments: String = "{}") = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))
    private fun names(tools: JSONArray) = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
    private fun descriptor(id: String) = ToolDescriptor(id, "fixture", id, id, "fixture://$id",
        effectClass = EffectClass.READ, replayPolicy = ReplayPolicy.AUTOMATIC, timeoutMs = 1000, hostKind = HostKind.NATIVE_ANDROID)
}
