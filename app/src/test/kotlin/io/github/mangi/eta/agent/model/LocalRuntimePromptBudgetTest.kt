package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimePromptBudgetTest {
    private data class Turn(val role: String, val text: String)

    @Test
    fun outputWindowAndNewestUserTurnAreReservedWhileOldestHistoryIsDropped() {
        val turns = listOf(
            Turn("system", "规则".repeat(80)),
            Turn("user", "旧问题".repeat(260)),
            Turn("assistant", "旧回答".repeat(260)),
            Turn("user", "当前问题"),
        )

        val fitted = LocalRuntimePromptBudget.fit(
            turns = turns,
            contextWindow = 1_024,
            maxOutputTokens = 512,
            roleOf = Turn::role,
            textOf = Turn::text,
            engine = "test",
            recordId = "fixture",
        )

        assertEquals(listOf("system", "user"), fitted.turns.map(Turn::role))
        assertEquals("当前问题", fitted.turns.last().text)
        assertEquals(2, fitted.omittedTurns)
    }

    @Test
    fun oversizedMandatoryCurrentTurnProducesContextOverflowFailure() {
        val failure = assertThrows(AgentModelFailure::class.java) {
            LocalRuntimePromptBudget.fit(
                turns = listOf(Turn("user", "输入".repeat(2_000))),
                contextWindow = 1_024,
                maxOutputTokens = 512,
                roleOf = Turn::role,
                textOf = Turn::text,
                engine = "test",
                recordId = "fixture",
            )
        }

        assertEquals("CONTEXT_OVERFLOW", failure.code)
    }

    @Test
    fun fourThousandTokenWindowKeepsSeveralNormalChatTurns() {
        val turns = listOf(
            Turn("system", "你是简洁可靠的手机助手。"),
            Turn("user", "请记住项目名是 Eta。"),
            Turn("assistant", "已记住。"),
            Turn("user", "再记住当前阶段是本地模型优化。"),
            Turn("assistant", "已记录。"),
            Turn("user", "现在总结一下。"),
        )

        val fitted = LocalRuntimePromptBudget.fit(
            turns = turns,
            contextWindow = 4_096,
            maxOutputTokens = 1_024,
            roleOf = Turn::role,
            textOf = Turn::text,
            engine = "test",
            recordId = "fixture",
        )

        assertEquals(0, fitted.omittedTurns)
        assertEquals(turns, fitted.turns)
    }

    @Test
    fun compactionKeepsRecentHistoryBeyondTheSystemPreface() {
        val turns = listOf(
            Turn("system", "系统说明".repeat(420)),
            Turn("user", "第一轮问题".repeat(30)),
            Turn("assistant", "第一轮回答".repeat(30)),
            Turn("user", "第二轮问题".repeat(30)),
            Turn("assistant", "第二轮回答".repeat(30)),
            Turn("user", "当前问题"),
        )

        val fitted = LocalRuntimePromptBudget.fit(
            turns = turns,
            contextWindow = 4_096,
            maxOutputTokens = 1_024,
            roleOf = Turn::role,
            textOf = Turn::text,
            engine = "test",
            recordId = "fixture",
        )

        assertTrue(fitted.turns.size > 2)
        assertEquals("当前问题", fitted.turns.last().text)
        assertTrue(fitted.omittedTurns < 4)
    }

    @Test
    fun representedSuffixAllowsNativeConversationReuseAfterTrimming() {
        val represented = listOf("user:2", "assistant:2", "user:3")
        val requested = listOf("user:1", "assistant:1") + represented

        assertTrue(continuesRuntimeHistory(requested, represented))
        assertFalse(continuesRuntimeHistory(requested, listOf("assistant:1", "user:2")))
    }

    @Test
    fun recurringIncrementalChunksRemainVisible() {
        val chunks = listOf("你", "好", "，", "世界", "你", "好", "！")
        val rendered = buildString {
            chunks.forEach { chunk -> append(liteRtStreamDelta(toString(), chunk)) }
        }

        assertEquals(chunks.joinToString(""), rendered)
        assertEquals("下一句", liteRtStreamDelta("已经显示", "下一句"))
    }

    @Test
    fun emptyCodeFencesAreRemovedButRealFencesArePreserved() {
        val raw = "前言\n```\n```\n```kotlin\nval answer = 42\n```\n```json\n  \n```"

        val visible = LocalRuntimePromptBudget.visibleOutput(raw)

        assertEquals("前言\n```kotlin\nval answer = 42\n```", visible)
        assertTrue(LocalRuntimePromptBudget.visibleOutput("```\n  \n```").isBlank())
        assertFalse(LocalRuntimePromptBudget.visibleOutput("可用答案").contains("```"))
    }

    @Test
    fun nativeContextFullMessageIsMappedToRetryableLoopFailure() {
        val mapped = LocalRuntimePromptBudget.failure(
            engine = "test",
            recordId = "fixture",
            failure = IllegalStateException(
                "Status Code: 8. Message: Max number of tokens reached, the context window is full.",
            ),
        ) as AgentModelFailure

        assertEquals("CONTEXT_OVERFLOW", mapped.code)
        assertFalse(mapped.retryable)
    }
}
