package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.LocalRuntimeBackend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeFailureTelemetryTest {
    @Test fun failureCodesDistinguishCancellationContextLoadAndGeneration() {
        assertEquals("CANCELLED", LocalRuntimeFailureTelemetry.code(AgentRunCancelledException()))
        assertEquals("CONTEXT_OVERFLOW", LocalRuntimeFailureTelemetry.code(
            AgentModelFailure("CONTEXT_OVERFLOW", false, "full"),
        ))
        assertEquals("MODEL_LOAD_FAILED", LocalRuntimeFailureTelemetry.code(
            IllegalStateException("模型加载失败：无法创建 native handle"),
        ))
        assertEquals("GENERATION_FAILED", LocalRuntimeFailureTelemetry.code(
            IllegalStateException("native decode failed"),
        ))
    }

    @Test fun partialNativeCountersAreKeptAndMissingCacheCountersStayUnknown() {
        val failure = AgentModelFailure("CONTEXT_OVERFLOW", false, "full")
        val metrics = LocalRuntimeFailureTelemetry.metrics(
            format = "GGUF",
            modelId = "local",
            failure = failure,
            backend = LocalRuntimeBackend.GPU,
            backendDetail = "GPU · OpenCL",
            loadMs = 900,
            firstTokenMs = 125,
            stats = JSONObject()
                .put("promptTokens", 640)
                .put("generatedTokens", 48)
                .put("prefillUs", 80_000)
                .put("generateUs", 2_400_000)
                .put("kvUsed", 688),
            contextWindow = 4_096,
            totalMs = 2_650,
        )

        assertEquals(125L, metrics.firstTokenMs)
        assertEquals(640, metrics.promptTokens)
        assertEquals(48, metrics.generatedTokens)
        assertEquals(688, metrics.kvCacheUsedTokens)
        assertNull(metrics.promptCacheHitTokens)
        assertNull(metrics.promptCacheReusedTokens)
        assertEquals("CONTEXT_OVERFLOW", metrics.failureCode)
        assertEquals(20.0, metrics.tokensPerSecond!!, 0.0001)
    }

    @Test fun cancellationCodeWinsEvenWhenNativeMessageMentionsContext() {
        val metrics = LocalRuntimeFailureTelemetry.metrics(
            format = "MNN",
            modelId = "local",
            failure = AgentRunCancelledException(),
            stats = JSONObject().put("promptTokens", 10),
        )

        assertEquals("CANCELLED", metrics.failureCode)
        assertEquals("cancelled", metrics.finishReason)
        assertTrue(metrics.promptCacheHitTokens == null)
    }
}
