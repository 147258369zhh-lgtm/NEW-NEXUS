package io.github.mangi.eta.agent.runtime

import java.math.BigDecimal
import io.github.mangi.eta.ui.screens.metrics.metricMoney
import org.junit.Assert.*
import org.junit.Test

class AgentTokenPricingTest {
    private fun reported(usage: AgentTokenUsage): AgentRunMetrics {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.UsageReceived(1, usage))
        return collector.snapshot()
    }

    @Test fun openAiSubtractsCacheAndDoesNotChargeReasoningTwice() {
        val metrics = reported(AgentTokenUsage(inputTokens = 100, outputTokens = 40, cachedTokens = 80,
            reasoningTokens = 30, billingBasis = AgentBillingTokens.OPENAI))
        assertEquals(20L, metrics.uncachedInputTokens)
        assertEquals(40L, metrics.billableOutputTokens)
        val cost = AgentTokenCostEstimator.estimate(metrics, AgentTokenPrice.create("USD", "2", "8", "0.5"))
        assertEquals(BigDecimal("0.0004"), cost.amount?.stripTrailingZeros())
        assertTrue(cost.complete)
    }

    @Test fun anthropicChargesSeparateCacheReadAndWriteWithoutSubtractingUncachedInput() {
        val metrics = reported(AgentTokenUsage(inputTokens = 10, outputTokens = 20, cachedTokens = 100,
            cacheWriteTokens = 40, billingBasis = AgentBillingTokens.ANTHROPIC))
        assertEquals(10L, metrics.uncachedInputTokens)
        assertEquals(100L, metrics.cacheReadTokens)
        assertEquals(40L, metrics.cacheWriteTokens)
        val cost = AgentTokenCostEstimator.estimate(metrics, AgentTokenPrice.create("CNY", "2", "8", "0.5", "2.5"))
        assertEquals(BigDecimal("0.00033"), cost.amount?.stripTrailingZeros())
        assertTrue(cost.complete)
    }

    @Test fun geminiInteractionsAddsSeparateThoughtsAndKeepsHostedToolCostUnknown() {
        val usage = AgentTokenUsage(inputTokens = 7, outputTokens = 23, reasoningTokens = 49,
            billingBasis = AgentBillingTokens.GEMINI_INTERACTIONS)
        val normal = reported(usage)
        assertEquals(72L, normal.billableOutputTokens)
        assertEquals(1, normal.billingReportedRounds)
        val tool = reported(usage.copy(toolUseTokens = 5))
        assertNull(tool.uncachedInputTokens)
        assertEquals(0, tool.billingReportedRounds)
        assertFalse(AgentTokenCostEstimator.estimate(tool, AgentTokenPrice.create("USD", "2", "8")).complete)
    }

    @Test fun unknownProtocolAndImpossibleCountersNeverBecomeACompleteBill() {
        assertNull(reported(AgentTokenUsage(inputTokens = 20, outputTokens = 10)).billableOutputTokens)
        val bad = reported(AgentTokenUsage(inputTokens = 20, outputTokens = 10, cachedTokens = 30,
            billingBasis = AgentBillingTokens.OPENAI))
        assertNull(bad.uncachedInputTokens)
        assertNull(bad.cacheReadTokens)
        assertNull(bad.billableOutputTokens)
        val negative = AgentBillingTokens.from(AgentTokenUsage(inputTokens = -1, outputTokens = 10, billingBasis = AgentBillingTokens.OPENAI))
        assertFalse(negative.complete)
        assertNull(negative.output)
    }

    @Test fun laterCumulativeCacheUpdatesReplaceDerivedAmountsAndMissingBasisDoesNotEraseProtocol() {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 100, outputTokens = 20, billingBasis = AgentBillingTokens.OPENAI)))
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(cachedTokens = 80)))
        repeat(3) { collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(outputTokens = 30))) }
        val metrics = collector.snapshot()
        assertEquals(20L, metrics.uncachedInputTokens)
        assertEquals(80L, metrics.cacheReadTokens)
        assertEquals(30L, metrics.billableOutputTokens)
        assertEquals(1, metrics.billingReportedRounds)
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(outputTokens = -1)))
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(outputTokens = 30, billingBasis = AgentBillingTokens.OPENAI)))
        assertNull(collector.snapshot().billableOutputTokens)
    }

    @Test fun missingPricesAndPartialUsageAreKnownPortionsAndNeverZeroInventedForMissingUsage() {
        val price = AgentTokenPrice.create("USD", "2", "8")
        val metrics = reported(AgentTokenUsage(inputTokens = 100, outputTokens = 20, cachedTokens = 80, billingBasis = AgentBillingTokens.OPENAI))
        val cost = AgentTokenCostEstimator.estimate(metrics, price)
        assertEquals(BigDecimal("0.0002"), cost.amount?.stripTrailingZeros())
        assertFalse(cost.complete)
        assertNull(AgentTokenCostEstimator.estimate(metrics, null).amount)
        assertNull(AgentTokenCostEstimator.estimate(AgentRunMetrics(rounds = 1), price).amount)
        val zero = reported(AgentTokenUsage(inputTokens = 0, outputTokens = 0, billingBasis = AgentBillingTokens.OPENAI))
        assertTrue(AgentTokenCostEstimator.estimate(zero, price).complete)
        assertEquals(0, AgentTokenCostEstimator.estimate(zero, price).amount?.signum())
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 100, outputTokens = 20, billingBasis = AgentBillingTokens.OPENAI)))
        collector.accept(AgentEvent.RoundStarted(2, 2))
        val partial = AgentTokenCostEstimator.estimate(collector.snapshot(), price)
        assertFalse(partial.complete)
        assertNotNull(partial.amount)
    }

    @Test fun ratesRejectAmbiguityOverflowAndNonMoneyInputsButKeepRealZero() {
        listOf("-1", "+1", "NaN", "Infinity", "1e3", "1,000", "01", ".5", "1.", "0.000000001", "1000000.00000001").forEach { value ->
            assertTrue("unexpected valid rate: $value", runCatching { AgentTokenPrice.create("USD", value, "0") }.isFailure)
        }
        assertTrue(runCatching { AgentTokenPrice.create("NOPE", "1", "1") }.isFailure)
        assertTrue(runCatching { AgentTokenPrice.create("ZZZ", "1", "1") }.isFailure)
        val valid = AgentTokenPrice.create(" cny ", "1000000.00000000", "0.00000001", "0")
        assertEquals("CNY", valid.currency)
        assertEquals("1000000", valid.input)
        assertEquals("0", valid.cacheRead)
        assertNull(valid.cacheWrite)
        val tiny = AgentTokenCostEstimator.estimate(reported(AgentTokenUsage(inputTokens = 0, outputTokens = 1,
            billingBasis = AgentBillingTokens.OPENAI)), valid)
        assertEquals("0.00000000000001", tiny.amount?.toPlainString())
        assertEquals("0.00000000000001", metricMoney(requireNotNull(tiny.amount)))
    }

    @Test fun currenciesAreNeverImplicitlyConvertedAndMissingModelsRemainUncovered() {
        val metrics = reported(AgentTokenUsage(inputTokens = 1_000_000, outputTokens = 0, billingBasis = AgentBillingTokens.OPENAI))
        val models = listOf(AgentMetricModelEntity("a", 0, "p", "m", "M", metrics, AgentTokenPrice.create("USD", "2", "8"), true),
            AgentMetricModelEntity("b", 0, "p", "m", "M", metrics, AgentTokenPrice.create("CNY", "3", "8"), true))
        val summary = AgentTokenCostEstimator.summarize(models, 3)
        assertEquals(listOf("CNY", "USD"), summary.currencies.map { it.currency })
        assertEquals(listOf(BigDecimal("3"), BigDecimal("2")), summary.currencies.map { it.amount.stripTrailingZeros() })
        assertEquals(2, summary.completeModels)
        assertEquals(3, summary.totalModels)
    }

    @Test fun ambiguousRetryReceiptsNeverClaimACompleteTokenEstimate() {
        val usage = reported(AgentTokenUsage(inputTokens = 100, outputTokens = 20, billingBasis = AgentBillingTokens.OPENAI))
        val price = AgentTokenPrice.create("USD", "2", "8")
        assertFalse(AgentTokenCostEstimator.estimate(usage.copy(requestAttempts = 2), price).complete)
        assertFalse(AgentTokenCostEstimator.estimate(usage.copy(retries = 1), price).complete)
    }
}
