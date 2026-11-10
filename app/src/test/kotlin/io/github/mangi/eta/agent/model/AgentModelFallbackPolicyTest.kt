package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentModelFallbackPolicyTest {
    @Test
    fun onlyTransientFailuresUseOptionalFallback() {
        assertTrue(AgentModelFallbackPolicy.decide(ModelFailureKind.NETWORK, true, true).useFallback)
        assertFalse(AgentModelFallbackPolicy.decide(ModelFailureKind.AUTHENTICATION, true, true).useFallback)
        assertFalse(AgentModelFallbackPolicy.decide(ModelFailureKind.NETWORK, false, true).useFallback)
    }

    @Test fun quotaAuthPermissionAndUnknownFailuresNeverSwitchProvider() {
        val permanent = listOf(
            AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}"""),
            AgentModelFailure.http(401, """{"error":{}}"""),
            AgentModelFailure.http(403, """{"error":{}}"""),
            AgentModelFailure("LOCAL_NETWORK_PERMISSION_DENIED", false, "permission needed"),
        )
        permanent.forEach {
            assertFalse(AgentModelFallbackPolicy.decide(AgentModelFallbackPolicy.classify(it), true, true).useFallback)
        }
        assertEquals(ModelFailureKind.UNKNOWN, AgentModelFallbackPolicy.classify(IllegalStateException("callback failed")))
        assertFalse(AgentModelFallbackPolicy.decide(ModelFailureKind.UNKNOWN, true, true).useFallback)
        assertEquals(ModelFailureKind.RATE_LIMIT, AgentModelFallbackPolicy.classify(AgentModelFailure.http(429, "{}")))
    }

    @Test fun exhaustedRetryRetainsOriginalTransientClassification() {
        val original = AgentModelFailure.http(503, "{}")
        val exhausted = AgentModelFailure(original.code, false, "exhausted", original)
        assertEquals(ModelFailureKind.SERVER, AgentModelFallbackPolicy.classify(exhausted))
        assertTrue(AgentModelFallbackPolicy.decide(AgentModelFallbackPolicy.classify(exhausted), true, true).useFallback)
    }

    @Test fun progressAndNonChatOperationsPreventRestart() {
        val progressEvents = listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TEXT, 0),
            AgentEvent.ToolStarted(1, "write", "write_file", "{}"),
            AgentEvent.ToolFinished(1, "read", "read_file", "done", 0, 0),
            AgentEvent.HostedToolStarted(1, "search", "web_search"),
            AgentEvent.UserSupplementReceived(1, "keep these files"),
            AgentEvent.ContextCompaction("run", AgentEvent.ContextCompaction.PHASE_STARTED),
        )
        progressEvents.forEach {
            val progress = ModelFallbackProgress()
            progress.accept(AgentEvent.RoundStarted(4, 12))
            progress.accept(it)
            assertEquals(4, progress.lastRound)
            assertFalse(AgentModelFallbackPolicy.decide(ModelFailureKind.NETWORK, true, true, progress.canRestart).useFallback)
        }
        assertFalse(AgentModelFallbackPolicy.decide(ModelFailureKind.NETWORK, true, true, isChat = false).useFallback)
        val progress = ModelFallbackProgress()
        progress.accept(AgentEvent.RoundStarted(4, 12))
        progress.accept(AgentEvent.ProviderResponseStarted(4, 503))
        assertTrue(progress.canRestart)
    }

    @Test fun backupUsesOwnCredentialsButRetainsConversationRulesAndNoCustomTools() {
        val primary = config().copy(contextWindow = 128_000, systemPrompt = "Keep the project directory and user constraints.")
        val backup = config().copy(
            providerId = "backup", apiKey = "backup-key", contextWindow = 8_000,
            terminalTools = true, browserTools = true, deviceDirectTools = true,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true, subagentTools = true,
            autoCompactionEnabled = true, hostedWebSearchEnabled = true,
            extraBodyJson = """{"tools":[{"type":"web_search"}]}""",
            customBody = listOf(CustomBody(key = "tool_choice", value = JsonPrimitive("required"))),
        )
        val constrained = AgentModelFallbackPolicy.constrainConfig(primary, backup)
        assertEquals("backup-key", constrained.apiKey)
        assertEquals(8000, constrained.contextWindow)
        assertTrue(constrained.systemPrompt.startsWith(primary.systemPrompt))
        assertEquals("", constrained.extraBodyJson)
        assertTrue(constrained.customBody.isEmpty())
        assertFalse(constrained.terminalTools || constrained.browserTools || constrained.deviceDirectTools ||
            constrained.deviceSensitiveReadTools || constrained.deviceSensitiveActionTools || constrained.subagentTools ||
            constrained.hostedWebSearchEnabled || constrained.autoCompactionEnabled)
        val request = ProviderRequest(backup, JSONArray(), JSONArray("""[{"type":"function"}]"""), purpose = ProviderRequestPurpose.FALLBACK)
        assertEquals(0, request.effectiveTools.length())
        assertEquals("", request.effectiveConfig.extraBodyJson)
        assertTrue(request.effectiveConfig.customBody.isEmpty())
    }

    @Test fun fallbackRejectsUnexpectedToolCallWithoutTouchingExecutor() {
        var executed = false
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config(), "continue", AgentModelClient.ToolExecutor {
                executed = true
                AgentModelClient.ToolResult("unsafe")
            }, provider = provider { _, _ ->
                JSONObject("""{"role":"assistant","content":"","finish_reason":"tool_calls","tool_calls":[{"id":"write","type":"function","function":{"name":"write_file","arguments":"{}"}}]}""")
            }, textOnlyFallback = true)
        }
        assertEquals("FALLBACK_TOOLS_DISABLED", (error.cause as AgentModelFailure).code)
        assertFalse(executed)
        assertTrue(error.transcript.isEmpty())
    }

    @Test fun fallbackUsesNewRoundsAndProcessesQueuedSupplement() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val result = AgentModelClient.complete(
            config(), "continue", AgentModelClient.ToolExecutor { error("no tools") },
            provider = provider { request, run ->
                assertEquals(ProviderRequestPurpose.FALLBACK, request.purpose)
                assertEquals(0, request.tools.length())
                if (calls++ == 0) run.steer("preserve the folder")
                else assertTrue(request.messages.toString().contains("preserve the folder"))
                JSONObject("""{"role":"assistant","content":"answer","finish_reason":"stop"}""")
            }, runController = controller, initialRound = 5, textOnlyFallback = true, operationId = "run", onEvent = events::add,
        )
        assertEquals(listOf(5, 6), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(listOf("assistant-run-5", "assistant-run-6"), result.transcript.filter { it.role == "assistant" }.map { it.messageId })
        assertTrue(result.transcript.any { it.role == "user" && it.content.contains("preserve the folder") })
        assertFalse(controller.steer("too late"))
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "primary-key", model = "test", systemPrompt = "",
        autoCompactionEnabled = false, subagentTools = false,
    )

    private fun provider(response: (ProviderRequest, AgentRunController) -> JSONObject) = object : AgentProviderClient {
        override val id = "fake"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            ProviderResponse(response(request, runController))
    }
}
