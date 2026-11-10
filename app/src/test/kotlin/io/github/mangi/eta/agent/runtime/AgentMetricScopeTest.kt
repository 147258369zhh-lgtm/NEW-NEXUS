package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentMetricScopeTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "PRIVATE_KEY",
        model = "fixture", providerId = "p", systemPrompt = "PRIVATE_PROMPT", contextWindow = 128_000)
    private fun telemetry() = AgentRunTelemetry(AgentRuntimeWire.RunRequest(runId = "root", prompt = "PRIVATE_PROMPT", images = emptyList(), config = config),
        priceResolver = { _, _ -> AgentTokenPrice.create("USD", "2", "8", "0.5") })
    private fun usage(input: Int) = AgentEvent.UsageReceived(1,
        AgentTokenUsage(inputTokens = input, outputTokens = 1, billingBasis = AgentBillingTokens.OPENAI))

    @Test fun nestedConcurrentChildrenKeepFullTotalsSeparateFromRootAndAreNotLimitedToEight() {
        val recorder = telemetry()
        recorder.accept(usage(10))
        val child = requireNotNull(recorder.scope.child("subagent", config, 1, "call"))
        child.accept(usage(20))
        val nested = requireNotNull(child.child("compaction", config))
        nested.accept(usage(30)); nested.finish(true)
        child.finish(true)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..20).map { value -> pool.submit {
                val scope = requireNotNull(recorder.scope.child("subagent", config, 1, "call-$value"))
                scope.accept(usage(value)); scope.finish(true)
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val finished = recorder.finish(true)
        assertEquals(10L, finished.metrics.inputTokens)
        assertEquals(22, finished.children.size)
        assertEquals(260L, finished.children.sumOf { it.metrics.inputTokens ?: 0 })
        val first = finished.children[0]
        assertEquals("call", first.toolCallId)
        assertEquals(first.scopeId, finished.children[1].parentScopeId)
        assertTrue(finished.children.all { it.status == AgentMetricStatus.SUCCEEDED })
        assertFalse(finished.toString().contains("PRIVATE_"))
    }

    @Test fun cancelledRootFreezesActiveChildrenAndRejectsLateUsageFinishAndNestedRequests() {
        val recorder = telemetry()
        val scope = requireNotNull(recorder.scope.child("subagent", config))
        scope.accept(usage(20))
        val finished = recorder.finish(false, cancelled = true)
        assertEquals(AgentMetricStatus.CANCELLED, finished.children.single().status)
        assertNull(finished.trace.single { it.kind == "child_finish" }.success)
        scope.accept(usage(999)); scope.finish(true)
        assertNull(scope.child("compaction", config))
        assertNull(recorder.scope.child("subagent", config))
        assertEquals(finished, recorder.snapshot())
    }

    @Test fun realCompactionPipelineRegistersEveryChunkAndLeavesMainRequestUsageSeparate() {
        val recorder = telemetry()
        var summaries = 0
        val history = (1..12).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "资料 $turn"),
            AgentModelClient.ConversationMessage("assistant", "历史事实".repeat(20_000)),
        ) }
        val provider = provider { request, emit ->
            val summary = request.purpose == ProviderRequestPurpose.COMPACTION
            if (summary) summaries++
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = if (summary) 100 else 10, outputTokens = 2,
                billingBasis = AgentBillingTokens.OPENAI)))
            response(if (summary) "完成此前工作，等待后续指令。" else "已完成")
        }
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("tools forbidden") },
            history = history, provider = provider, metricsScope = recorder.scope, onEvent = recorder::accept)
        val finished = recorder.finish(true)
        assertEquals("已完成", result.content)
        assertTrue(summaries > 1)
        assertEquals(summaries, finished.children.size)
        assertEquals(10L, finished.metrics.inputTokens)
        assertTrue(finished.children.all { it.kind == "compaction" && it.metrics.inputTokens == 100L && it.metrics.requestAttempts == 1 })
        assertEquals(summaries * 100L, finished.children.sumOf { it.metrics.inputTokens ?: 0 })
    }

    @Test fun invalidPaidSummaryKeepsUsageAndFailedStatusWithoutReplacingHistory() {
        val recorder = telemetry()
        val summarizer = AgentContextSummarizer(config, provider { _, emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, outputTokens = 20, billingBasis = AgentBillingTokens.OPENAI)))
            response("不完整", "length")
        }, AgentRunController(), false, recorder.scope)
        assertThrows(AgentModelFailure::class.java) { summarizer.summarize(listOf(AgentModelClient.ConversationMessage("user", "资料"))) }
        val child = recorder.finish(false).children.single()
        assertEquals(AgentMetricStatus.FAILED, child.status)
        assertEquals(100L, child.metrics.inputTokens)
        assertNotNull(AgentTokenCostEstimator.estimate(child.metrics, child.price).amount)
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", stop))
    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
}
