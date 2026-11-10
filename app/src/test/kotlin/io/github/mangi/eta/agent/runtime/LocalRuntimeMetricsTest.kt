package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeMetricsTest {
    @Test
    fun unavailableCacheCountersStayUnknownInsteadOfBecomingFakeZeroes() {
        val metrics = LocalRuntimeMetrics(
            backend = LocalRuntimeBackend.CPU,
            modelFormat = "LITERTLM",
            modelId = "gemma",
            promptTokens = 1_000,
            kvCacheUsedTokens = 2_000,
            kvCacheCapacityTokens = 4_096,
        )

        assertNull(metrics.promptCacheHitRate)
        assertEquals(2_000.0 / 4_096.0, metrics.kvCacheUsageRate!!, 0.0001)
    }

    @Test
    fun reportedCacheAndKvUsageAreBoundedRatios() {
        val metrics = LocalRuntimeMetrics(
            backend = LocalRuntimeBackend.GPU,
            modelFormat = "GGUF",
            modelId = "qwen",
            promptTokens = 800,
            promptCacheHitTokens = 640,
            kvCacheUsedTokens = 1_024,
            kvCacheCapacityTokens = 4_096,
        )

        assertEquals(0.8, metrics.promptCacheHitRate!!, 0.0001)
        assertEquals(0.25, metrics.kvCacheUsageRate!!, 0.0001)
    }

    @Test
    fun usageIsNotConsideredEmptyWhenOnlyHardwareMetricsExist() {
        val usage = AgentTokenUsage(localRuntime = LocalRuntimeMetrics(
            backend = LocalRuntimeBackend.NPU,
            modelFormat = "LITERTLM",
            modelId = "npu-model",
        ))

        assertTrue(!usage.isEmpty)
    }
}
