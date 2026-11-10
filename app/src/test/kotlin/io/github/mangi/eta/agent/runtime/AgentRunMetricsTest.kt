package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMetricsTest {
    @Test
    fun aggregatesToolAndTokenUsage() {
        val collector = AgentRunMetricsCollector()
        listOf<AgentEvent>(
            AgentEvent.RoundStarted(2, 1),
            AgentEvent.ToolStarted(2, "1", "observe_screen", ""),
            AgentEvent.ToolFinished(2, "1", "observe_screen", "", 0, 0, success = true),
            AgentEvent.UsageReceived(2, AgentTokenUsage(contextTokens = 100, inputTokens = 20, outputTokens = 30, reasoningTokens = 0, cachedTokens = 0)),
        ).forEach(collector::accept)
        val metrics = collector.snapshot()
        assertEquals(1, metrics.rounds)
        assertEquals(1, metrics.toolCalls)
        assertEquals(20L, metrics.inputTokens)
        assertEquals(1f, requireNotNull(metrics.successRate()), 0.001f)
        assertTrue(metrics.usageComplete)
    }

    @Test fun cumulativeAndDuplicateReportsReplaceTheirRoundAndPreserveAbsentFields() {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.RoundStarted(1, 1))
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20, outputTokens = 4)))
        repeat(3) { collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(outputTokens = 10))) }
        collector.accept(AgentEvent.RoundStarted(2, 2))
        collector.accept(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 8, outputTokens = 0)))
        assertEquals(28L, collector.snapshot().inputTokens)
        assertEquals(10L, collector.snapshot().outputTokens)
        assertEquals(2, collector.snapshot().rounds)
        assertTrue(collector.snapshot().usageComplete)
    }

    @Test fun missingUsageIsNotZeroAndIncompleteCoverageIsVisible() {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.RoundStarted(1, 1))
        assertNull(collector.snapshot().inputTokens)
        assertNull(collector.snapshot().successRate())
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 0, outputTokens = -1)))
        collector.accept(AgentEvent.RoundStarted(2, 2))
        assertEquals(0L, collector.snapshot().inputTokens)
        assertNull(collector.snapshot().outputTokens)
        assertEquals(1, collector.snapshot().inputReportedRounds)
        assertEquals(0, collector.snapshot().outputReportedRounds)
        assertFalse(collector.snapshot().usageComplete)
    }

    @Test fun toolOutcomeKeepsKnownResultsAndSeparatesRoundAndHostedIdentity() {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.ToolFinished(1, "same", "test", "", 0, 0, true))
        collector.accept(AgentEvent.ToolStarted(1, "same", "test", ""))
        collector.accept(AgentEvent.ToolFinished(1, "same", "test", "", 0, 0, null))
        collector.accept(AgentEvent.HostedToolFinished(1, "same", "search", false))
        collector.accept(AgentEvent.ToolStarted(2, "same", "test", ""))
        val metrics = collector.snapshot()
        assertEquals(3, metrics.toolCalls)
        assertEquals(1, metrics.succeededTools)
        assertEquals(1, metrics.failedTools)
        assertEquals(1, metrics.unresolvedTools)
        assertEquals(0.5f, requireNotNull(metrics.successRate()), 0.001f)
    }

    @Test fun modelBreakdownReconcilesRequestsRetriesImagesAndUsageWithoutReassigningOldRounds() {
        val collector = AgentRunMetricsCollector()
        collector.accept(AgentEvent.RunStarted(1, 200, 0, false))
        collector.accept(AgentEvent.RoundStarted(1, 1))
        repeat(2) { collector.accept(AgentEvent.ProviderRequestStarted(1)) }
        collector.accept(AgentEvent.ModelRetryScheduled(1, 1, 2, 1000, "timeout"))
        collector.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = 50, inputTokens = 20, outputTokens = 10)))
        collector.accept(AgentEvent.ToolFinished(1, "tool", "test", "", 0, 0, true))
        collector.accept(AgentEvent.ToolImagesAttached(1, "test", 1, 30))
        collector.modelAttempt(1)
        collector.accept(AgentEvent.RoundStarted(1, 1)) // duplicate late event cannot move old usage
        collector.accept(AgentEvent.RunStarted(1, 200, 0, false))
        collector.accept(AgentEvent.RoundStarted(2, 1))
        collector.accept(AgentEvent.ProviderRequestStarted(2))
        collector.accept(AgentEvent.UsageReceived(2, AgentTokenUsage(contextTokens = 5, inputTokens = 2, outputTokens = 1)))
        repeat(2) { collector.accept(AgentEvent.ContextCompaction("compact1", "completed")) }
        val first = collector.snapshot(0)
        val second = collector.snapshot(1)
        assertEquals(20L, first.inputTokens)
        assertEquals(50, first.contextTokens)
        assertEquals(1, first.succeededTools)
        assertEquals(230L, first.imageBytes)
        assertEquals(2, first.requestAttempts)
        assertEquals(1, first.retries)
        assertEquals(2L, second.inputTokens)
        assertEquals(5, second.contextTokens)
        assertEquals(1, second.completedCompactions)
        assertEquals(collector.snapshot().imageBytes, first.imageBytes + second.imageBytes)
        assertEquals(collector.snapshot().requestAttempts, first.requestAttempts + second.requestAttempts)
    }
}
