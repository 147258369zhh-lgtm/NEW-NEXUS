package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestMetricsTest {
    @Test fun successfulRequestCapturesLatencyUsagePriceAndStreamingSpeed() {
        var clock = 0L
        val price = AgentTokenPrice.create("USD", "1", "2", now = 10)
        val telemetry = AgentRunTelemetry(request(), monotonicClock = { clock },
            wallClock = { 1_000 + clock }, priceResolver = { _, _ -> price })
        telemetry.accept(AgentEvent.RoundStarted(1, 1))
        telemetry.accept(AgentEvent.ProviderRequestStarted(1))
        clock = 120
        telemetry.accept(AgentEvent.ProviderResponseStarted(1, 200))
        clock = 350
        telemetry.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 1, "private"))
        clock = 700
        telemetry.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(
            inputTokens = 1_200, outputTokens = 100, cachedTokens = 200,
            billingBasis = AgentBillingTokens.OPENAI,
        )))
        clock = 900
        telemetry.accept(AgentEvent.AssistantReceived(1, 4, "", emptyList()))

        val request = telemetry.finish(true).requests.single()
        assertEquals(AgentMetricStatus.SUCCEEDED, request.status)
        assertEquals(200, request.httpCode)
        assertEquals(120L, request.responseMs)
        assertEquals(350L, request.firstTextMs)
        assertEquals(900L, request.totalMs)
        assertEquals(1_000L, request.startedAt)
        assertEquals(price.revision, request.price?.revision)
        assertEquals("USD", request.price?.currency)
        assertTrue(request.priceCaptured)
        assertEquals(1_200L, request.usage.inputTokens)
        assertEquals(100L, request.usage.outputTokens)
        assertEquals(1_000L, request.usage.uncachedInputTokens)
        assertEquals(200L, request.usage.cacheReadTokens)
        assertEquals(100L, request.usage.billableOutputTokens)
        assertEquals(100.0 / 0.55, request.outputTokensPerSecond!!, 0.001)
    }

    @Test fun retryCreatesSeparateRowsAndPreservesTheFailedAttemptReason() {
        var clock = 0L
        val telemetry = AgentRunTelemetry(request(), monotonicClock = { clock })
        telemetry.accept(AgentEvent.ProviderRequestStarted(1))
        clock = 80
        telemetry.accept(AgentEvent.ProviderResponseStarted(1, 503))
        clock = 100
        telemetry.accept(AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "server_5xx"))
        clock = 2_100
        telemetry.accept(AgentEvent.ProviderRequestStarted(2))
        clock = 2_220
        telemetry.accept(AgentEvent.ProviderResponseStarted(2, 200))
        clock = 2_500
        telemetry.accept(AgentEvent.AssistantReceived(2, 1, "", emptyList()))

        val requests = telemetry.finish(true).requests
        assertEquals(2, requests.size)
        assertEquals(1, requests[0].attempt)
        assertEquals(AgentMetricStatus.FAILED, requests[0].status)
        assertEquals("server_5xx", requests[0].failureCode)
        assertEquals(2, requests[1].attempt)
        assertEquals(AgentMetricStatus.SUCCEEDED, requests[1].status)
        assertEquals(200, requests[1].httpCode)
    }

    @Test fun unfinishedProviderAttemptIsClosedAsInterruptedWithoutInventingUsageOrCost() {
        var clock = 0L
        val telemetry = AgentRunTelemetry(request(), monotonicClock = { clock })
        telemetry.accept(AgentEvent.ProviderRequestStarted(1))
        clock = 450
        val snapshot = telemetry.finish(true)
        val request = snapshot.requests.single()
        assertEquals(AgentMetricStatus.INTERRUPTED, request.status)
        assertEquals("run_interrupted", request.failureCode)
        assertEquals(450L, request.totalMs)
        assertNull(request.httpCode)
        assertTrue(!request.usage.reported)
        assertNull(request.price)
    }

    @Test fun childRequestsStayScopedToTheRegisteredChildAndDoNotLeakProviderSecrets() {
        val telemetry = AgentRunTelemetry(request())
        val child = requireNotNull(telemetry.scope.child("compaction", request().config))
        child.accept(AgentEvent.ProviderRequestStarted(1))
        child.accept(AgentEvent.ProviderResponseStarted(1, 200))
        child.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20, outputTokens = 2,
            billingBasis = AgentBillingTokens.OPENAI)))
        child.accept(AgentEvent.AssistantReceived(1, 1, "", emptyList()))
        child.finish(true)

        val snapshot = telemetry.finish(true)
        val request = snapshot.requests.single()
        assertTrue(request.scopeId.isNotBlank())
        assertEquals(20L, request.usage.inputTokens)
        assertEquals(AgentMetricStatus.SUCCEEDED, request.status)
        assertNotNull(snapshot.children.single())
        assertTrue(!request.toString().contains("PRIVATE"))
    }

    @Test fun localRuntimeMetricsStayAttachedToTheirActualRequest() {
        val telemetry = AgentRunTelemetry(request())
        val metrics = LocalRuntimeMetrics(
            backend = LocalRuntimeBackend.GPU,
            modelFormat = "GGUF",
            modelId = "local-qwen",
            firstTokenMs = 125,
            promptTokens = 500,
            generatedTokens = 20,
            promptCacheHitTokens = 350,
        )
        telemetry.accept(AgentEvent.ProviderRequestStarted(1))
        telemetry.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(
            inputTokens = 500,
            outputTokens = 20,
            cachedTokens = 350,
            localRuntime = metrics,
        )))
        telemetry.accept(AgentEvent.AssistantReceived(1, 2, "", emptyList()))

        val request = telemetry.finish(true).requests.single()
        assertEquals(metrics, request.localRuntime)
        assertEquals(500L, request.usage.inputTokens)
        assertEquals(20L, request.usage.outputTokens)
    }

    private fun request() = AgentRuntimeWire.RunRequest(
        runId = "metric-run",
        prompt = "PRIVATE_PROMPT",
        images = emptyList(),
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://private.invalid", apiKey = "PRIVATE_KEY", model = "main",
            providerId = "provider", modelDisplayName = "Main", systemPrompt = "PRIVATE_SYSTEM_PROMPT",
        ),
    )
}
