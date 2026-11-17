package io.github.mangi.eta.agent.runtime

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCandidateReliabilityTest {
    private val now = 1_760_000_000_000L
    private val windowStart = AgentCandidateReliability.windowStart(now)

    @Test fun aggregatesOnlyExactProviderModelTerminalRequestsInsideTheWindow() {
        val price = AgentTokenPrice.create("USD", "2", "4", now = now)
        val usage = AgentRequestUsage(
            uncachedInputTokens = 1_000_000, billableOutputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0,
        )
        val samples = listOf(
            sample("providerA", AgentMetricStatus.SUCCEEDED, 800),
            sample("providerA", AgentMetricStatus.SUCCEEDED, 1_000, usage = usage, price = price, priceCaptured = true),
            sample("providerA", AgentMetricStatus.SUCCEEDED, 1_200),
            sample("providerA", AgentMetricStatus.SUCCEEDED, 900),
            sample("providerA", AgentMetricStatus.SUCCEEDED, 1_100),
            sample("providerA", AgentMetricStatus.FAILED, 5_000),
            // Cancelled, running and out-of-window attempts must never reach the statistics.
            sample("providerA", AgentMetricStatus.CANCELLED, 700),
            sample("providerA", AgentMetricStatus.RUNNING, null),
            sample("providerA", AgentMetricStatus.SUCCEEDED, 800, startedAt = windowStart - 1),
            // Same model id on another provider stays a separate candidate.
            sample("providerB", AgentMetricStatus.FAILED, 400),
            sample("providerB", AgentMetricStatus.FAILED, 500),
            sample("providerB", AgentMetricStatus.FAILED, 600),
            sample("providerB", AgentMetricStatus.FAILED, 700),
            sample("providerB", AgentMetricStatus.SUCCEEDED, 900),
            // Uncaptured prices never contribute cost and never count as zero cost.
            sample("providerC", AgentMetricStatus.SUCCEEDED, 1_000, usage = usage, price = price, priceCaptured = false),
            sample("providerC", AgentMetricStatus.SUCCEEDED, 1_000, usage = usage, price = price, priceCaptured = false),
        )

        val stats = AgentCandidateReliability.aggregate(samples, windowStart)
        val providerA = requireNotNull(stats[AgentCandidateReliabilityKey("providerA", "same-model")])
        val providerB = requireNotNull(stats[AgentCandidateReliabilityKey("providerB", "same-model")])
        val providerC = requireNotNull(stats[AgentCandidateReliabilityKey("providerC", "same-model")])

        assertEquals(6, providerA.sampleCount)
        assertEquals(5, providerA.succeeded)
        assertEquals(1, providerA.failed)
        assertEquals(5.0 / 6.0, requireNotNull(providerA.successRate), 1e-9)
        assertEquals(1_000L, providerA.medianTotalMs)
        assertEquals(1, providerA.pricedSampleCount)
        assertEquals(1.0 / 6.0, requireNotNull(providerA.priceCoverage), 1e-9)
        assertEquals("USD", providerA.costs.single().currency)
        assertEquals(0, providerA.costs.single().amount.compareTo(BigDecimal("2")))
        assertTrue(providerA.dataSufficient)

        assertEquals(5, providerB.sampleCount)
        assertEquals(1, providerB.succeeded)
        assertEquals(0.2, requireNotNull(providerB.successRate), 1e-9)
        assertEquals(900L, providerB.medianTotalMs)
        assertEquals(0, providerB.pricedSampleCount)
        assertEquals(0.0, requireNotNull(providerB.priceCoverage), 1e-9)
        assertTrue(providerB.costs.isEmpty())

        assertEquals(2, providerC.sampleCount)
        assertEquals(0, providerC.pricedSampleCount)
        assertTrue(providerC.costs.isEmpty())
        assertEquals(0.0, requireNotNull(providerC.priceCoverage), 1e-9)
        assertFalse(providerC.dataSufficient)
        assertNull(stats[AgentCandidateReliabilityKey("providerA", "other-model")])
    }

    @Test fun reliabilityOrderKeepsInsufficientSamplesInManualOrder() {
        val stats = mapOf(
            AgentCandidateReliabilityKey("providerA", "same-model") to stats("providerA", succeeded = 5, failed = 1),
            AgentCandidateReliabilityKey("providerB", "same-model") to stats("providerB", succeeded = 1, failed = 4),
            AgentCandidateReliabilityKey("providerC", "same-model") to stats("providerC", succeeded = 2, failed = 0),
        )
        val ordered = AgentCandidateReliability.orderByReliability(
            items = listOf("providerC", "providerA", "providerB"),
            keyOf = { AgentCandidateReliabilityKey(it, "same-model") },
            priorityOf = { listOf("providerC", "providerA", "providerB").indexOf(it) },
            stats = stats,
        )
        assertEquals(listOf("providerA", "providerB", "providerC"), ordered)
    }

    private fun stats(provider: String, succeeded: Int, failed: Int): AgentCandidateReliabilityStats {
        val total = succeeded + failed
        return AgentCandidateReliabilityStats(
            providerId = provider,
            modelId = "same-model",
            sampleCount = total,
            succeeded = succeeded,
            failed = failed,
            successRate = succeeded.toDouble() / total,
            medianTotalMs = 1_000L,
            pricedSampleCount = 0,
            priceCoverage = 0.0,
            costs = emptyList(),
            dataSufficient = total >= AgentCandidateReliability.MINIMUM_SAMPLES,
        )
    }

    private fun sample(
        provider: String,
        status: String,
        totalMs: Long?,
        startedAt: Long = now - 1_000,
        usage: AgentRequestUsage = AgentRequestUsage(),
        price: AgentTokenPrice? = null,
        priceCaptured: Boolean = false,
    ) = AgentCandidateRequestSample(
        providerId = provider,
        modelId = "same-model",
        startedAt = startedAt,
        status = status,
        totalMs = totalMs,
        usage = usage,
        price = price,
        priceCaptured = priceCaptured,
    )
}
