package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetPolicyTest {
    @Test fun largeBudgetsDoNotOverflow() {
        val budget = checkNotNull(ContextBudgetPolicy.of(Int.MAX_VALUE))
        assertTrue(budget.summaryInputTokens > 0)
        assertTrue(budget.summaryInputTokens <= budget.usableInputTokens)
        assertEquals(Int.MAX_VALUE, ContextBudgetPolicy.estimateImages(Int.MAX_VALUE))
    }
    @Test fun observedUsageCannotHideNewMessagesThatHaveNotBeenCountedByTheProvider() {
        val budget = ContextBudgetPolicy.forCompaction(16_000)
        assertTrue(ContextBudgetPolicy.shouldCompact(budget, 10, budget.triggerInputTokens))
    }

    @Test fun oneOversizedRecordIsSplitAsTranscriptDataWithoutDroppingItsTail() {
        val message = AgentModelClient.ConversationMessage("user", "HEAD" + "大段历史".repeat(4_000) + "TAIL")
        val chunks = ContextBudgetPolicy.chunk(listOf(message), 1_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { ContextBudgetPolicy.estimateMessages(it) <= 1_000 })
        assertTrue(chunks.first().first().content.contains("HEAD"))
        assertTrue(chunks.last().last().content.contains("TAIL"))
        assertTrue(chunks.flatten().all { it.role == "assistant" && it.toolCallsJson.isEmpty() })
    }
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
        assertEquals(11_200, budget.triggerInputTokens)
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
    fun imageTransportSizeDoesNotExhaustTheContextBudget() {
        fun message(reference: String) = AgentConversationCodec.userMessage(
            "这是什么？", listOf(AgentModelClient.ModelImage(reference, "image/png", reference.length)),
        )
        val short = message("data:image/png;base64,AAAA")
        val screenshot = message("data:image/png;base64," + "A".repeat(300_000))
        val screenshotBefore = screenshot.toString()
        val estimate = ContextBudgetPolicy.estimateMessages(JSONArray().put(screenshot))
        assertEquals(ContextBudgetPolicy.estimateMessages(JSONArray().put(short)), estimate)
        assertTrue(estimate >= ContextBudgetPolicy.IMAGE_INPUT_ESTIMATE_TOKENS)
        assertFalse(ContextBudgetPolicy.shouldCompact(ContextBudgetPolicy.forCompaction(null), null, estimate))
        assertEquals(estimate, ContextBudgetPolicy.estimateMessages(listOf(AgentConversationCodec.fromJsonObject(screenshot))))
        assertEquals(screenshotBefore, screenshot.toString())
    }

    @Test
    fun multipleImagesAndLongTextStillConsumeTheirOwnBudgets() {
        val image = JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "https://example.invalid/picture.png"))
        fun message(text: String, images: Int) = JSONArray().put(JSONObject().put("role", "user").put(
            "content", JSONArray().put(JSONObject().put("type", "text").put("text", text)).apply {
                repeat(images) { put(image) }
            },
        ))
        val one = ContextBudgetPolicy.estimateMessages(message("短问题", 1))
        val two = ContextBudgetPolicy.estimateMessages(message("短问题", 2))
        assertTrue(two - one >= ContextBudgetPolicy.IMAGE_INPUT_ESTIMATE_TOKENS)
        assertTrue(ContextBudgetPolicy.shouldCompact(
            ContextBudgetPolicy.forCompaction(null), null,
            ContextBudgetPolicy.estimateMessages(message("中".repeat(20_000), 1)),
        ))
    }

    @Test
    fun smallWindowSummaryBudgetFitsInsideTheWindow() {
        val budget = checkNotNull(ContextBudgetPolicy.of(4_096))
        assertTrue(budget.summaryInputTokens < budget.usableInputTokens)
        assertTrue(budget.summaryInputTokens >= 768)
        assertTrue(AgentContextSummarizer.summaryCharLimit(4_096) < 1_500)
    }
}
