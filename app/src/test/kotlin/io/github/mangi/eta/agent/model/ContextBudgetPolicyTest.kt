package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetPolicyTest {
    @Test
    fun unknownWindowUsesAConservativeCompactionBudget() {
        val fallback = ContextBudgetPolicy.forCompaction(null)
        assertEquals(ContextBudgetPolicy.UNKNOWN_WINDOW_FALLBACK_TOKENS, fallback.contextWindow)
        assertTrue(fallback.triggerInputTokens < ContextBudgetPolicy.UNKNOWN_WINDOW_FALLBACK_TOKENS)
        assertTrue(fallback.usableInputTokens > fallback.triggerInputTokens)
    }

    @Test
    fun outputReserveIsSubtractedBeforeAutomaticCompaction() {
        val budget = checkNotNull(ContextBudgetPolicy.of(16_000))
        assertTrue(budget.outputReserveTokens >= 2_000)
        assertTrue(budget.usableInputTokens < 16_000 - budget.outputReserveTokens)
        assertTrue(budget.triggerInputTokens < budget.usableInputTokens)
        assertFalse(ContextBudgetPolicy.shouldCompact(budget, 1_000, 1_000))
        assertTrue(ContextBudgetPolicy.shouldCompact(budget, null, budget.triggerInputTokens))
    }

    @Test
    fun estimateFallbackAndChunkingAreBoundedAndOrderStable() {
        val messages = (1..8).map { index ->
            AgentModelClient.ConversationMessage("user", "块 $index " + "内容".repeat(700))
        }
        val chunks = ContextBudgetPolicy.chunk(messages, 2_000)
        assertTrue(chunks.size > 1)
        assertEquals(messages, chunks.flatten())
        assertTrue(chunks.all { ContextBudgetPolicy.estimateMessages(it) <= 2_100 })
    }

    @Test
    fun oversizedToolResultsKeepStableTraceableReference() {
        val original = "HEAD" + "x".repeat(20_000) + "TAIL"
        val demoted = ContextBudgetPolicy.demoteOversizedToolResults(
            listOf(AgentModelClient.ConversationMessage("tool", original, toolCallId = "call-7")),
            maxCharsPerResult = 1_000,
        )
        assertEquals(1, demoted.demotedCount)
        val content = demoted.messages.single().content
        assertTrue(content.contains("call-7"))
        assertTrue(content.contains("sha256="))
        assertTrue(content.length < original.length)
    }

    @Test
    fun estimateIncludesJsonAndSystemMessagesConservatively() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "规则".repeat(100)))
            .put(JSONObject().put("role", "user").put("content", "任务".repeat(100)))
        val estimate = ContextBudgetPolicy.estimateMessages(messages)
        assertTrue(estimate > 120)
        assertTrue(ContextBudgetPolicy.estimateTextTokens("abc" + "中".repeat(9)) >= 4)
    }

    @Test
    fun chineseTextDoesNotUseTheFormerThreeCharactersPerTokenEstimate() {
        assertEquals(301, ContextBudgetPolicy.estimateTextTokens("中".repeat(300)))
    }

    @Test
    fun smallWindowSummaryBudgetFitsInsideTheWindow() {
        val budget = checkNotNull(ContextBudgetPolicy.of(4_096))
        assertTrue(budget.summaryInputTokens < budget.usableInputTokens)
        assertTrue(budget.summaryInputTokens >= 768)
        assertTrue(AgentContextSummarizer.summaryCharLimit(4_096) < 1_500)
    }
}
