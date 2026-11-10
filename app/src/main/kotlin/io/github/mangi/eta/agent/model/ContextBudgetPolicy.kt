package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import org.json.JSONArray

/** 无 tokenizer 时保守估算模型请求预算；估算宁可提前压缩，也不能等待服务商溢出。 */
internal data class ContextBudget(
    val contextWindow: Int,
    val outputReserveTokens: Int,
    val safetyReserveTokens: Int,
    val usableInputTokens: Int,
    val triggerInputTokens: Int,
    val summaryInputTokens: Int,
)

internal data class ContextMessageDemotion(
    val messages: List<AgentModelClient.ConversationMessage>,
    val demotedCount: Int,
)

internal object ContextBudgetPolicy {
    /**
     * Providers do not always expose a model's context-window metadata. Falling back to a
     * conservative window keeps automatic compaction active instead of silently disabling it;
     * the cost is compacting earlier than a larger model actually needs.
     */
    const val UNKNOWN_WINDOW_FALLBACK_TOKENS = 16_000
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MIN_OUTPUT_RESERVE_TOKENS = 2_048
    private const val MAX_OUTPUT_RESERVE_TOKENS = 16_384
    private const val MIN_SUMMARY_INPUT_TOKENS = 768
    private const val MAX_SUMMARY_INPUT_TOKENS = 8_192
    private const val DEFAULT_TOOL_RESULT_CHARS = 12_000
    private const val TOOL_RESULT_HEAD_CHARS = 4_000
    private const val TOOL_RESULT_TAIL_CHARS = 2_000

    fun of(contextWindow: Int?): ContextBudget? {
        val window = contextWindow?.takeIf { it > 0 } ?: return null
        val outputReserve = (window / 8).coerceIn(
            minOf(MIN_OUTPUT_RESERVE_TOKENS, maxOf(256, window / 4)),
            maxOf(512, window / 4),
        ).coerceAtMost(MAX_OUTPUT_RESERVE_TOKENS)
        val safetyReserve = maxOf(512, window / 25)
        val usable = (window - outputReserve - safetyReserve).coerceAtLeast(1)
        return ContextBudget(
            contextWindow = window,
            outputReserveTokens = outputReserve,
            safetyReserveTokens = safetyReserve,
            usableInputTokens = usable,
            // Use the model's declared window for the normal 85% trigger, while
            // never asking the provider for more input than the safe usable limit.
            triggerInputTokens = minOf((usable * 99) / 100, maxOf(1, (window * 85) / 100)),
            // A summary request must itself fit in a small local window. The previous fixed
            // 3K floor could make every 4K-window compaction overflow before it started.
            summaryInputTokens = ((usable * 2) / 3).coerceIn(
                minOf(MIN_SUMMARY_INPUT_TOKENS, usable),
                minOf(MAX_SUMMARY_INPUT_TOKENS, usable),
            ),
        )
    }

    /** Uses the declared window when available and a safe automatic-compaction budget otherwise. */
    fun forCompaction(contextWindow: Int?): ContextBudget = checkNotNull(
        of(contextWindow?.takeIf { it > 0 } ?: UNKNOWN_WINDOW_FALLBACK_TOKENS),
    )

    /** UTF-8 bytes per token approximates Chinese at ~1 token/character and ASCII at ~3 chars. */
    fun estimateTextTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.toByteArray(Charsets.UTF_8).size + 2) / 3 + 1

    fun estimateMessages(messages: JSONArray, startIndex: Int = 0): Int {
        if (startIndex !in 0..messages.length()) return 0
        var tokens = 0
        for (index in startIndex until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            tokens += MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(message.toString())
        }
        return tokens
    }

    fun estimateMessages(messages: List<AgentModelClient.ConversationMessage>): Int =
        messages.sumOf { message ->
            MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(
                message.role + '\n' + message.content + '\n' + message.contentJson + '\n' +
                    message.reasoningContent + '\n' + message.toolCallsJson,
            )
        }

    fun shouldCompact(
        budget: ContextBudget?,
        observedInputTokens: Int?,
        estimatedInputTokens: Int,
    ): Boolean {
        if (budget == null) return false
        return (observedInputTokens?.takeIf { it >= 0 } ?: estimatedInputTokens) >= budget.triggerInputTokens
    }

    /**
     * 工具结果只作为证据输入摘要；超限时保留头尾、长度和稳定摘要，避免把整段日志再次塞进摘要请求。
     */
    fun demoteOversizedToolResults(
        messages: List<AgentModelClient.ConversationMessage>,
        maxCharsPerResult: Int = DEFAULT_TOOL_RESULT_CHARS,
    ): ContextMessageDemotion {
        var demoted = 0
        val target = messages.map { message ->
            if (message.role != "tool" || message.content.length <= maxCharsPerResult) {
                message
            } else {
                demoted += 1
                val digest = sha256(message.content)
                val head = message.content.take(TOOL_RESULT_HEAD_CHARS.coerceAtMost(maxCharsPerResult / 2))
                val tail = message.content.takeLast(TOOL_RESULT_TAIL_CHARS.coerceAtMost(maxCharsPerResult / 4))
                message.copy(
                    content = "[工具结果已降级；引用=${message.toolCallId.ifBlank { "unknown" }}；" +
                        "sha256=$digest；原长度=${message.content.length}]\n$head\n…\n$tail",
                    contentJson = "",
                    reasoningContent = "",
                )
            }
        }
        return ContextMessageDemotion(target, demoted)
    }

    fun chunk(
        messages: List<AgentModelClient.ConversationMessage>,
        maxInputTokens: Int,
    ): List<List<AgentModelClient.ConversationMessage>> {
        if (messages.isEmpty()) return emptyList()
        val limit = maxInputTokens.coerceAtLeast(256)
        val result = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        var current = mutableListOf<AgentModelClient.ConversationMessage>()
        var currentTokens = 0
        for (message in messages) {
            val tokens = estimateMessages(listOf(message))
            if (current.isNotEmpty() && currentTokens + tokens > limit) {
                result += current
                current = mutableListOf()
                currentTokens = 0
            }
            current += message
            currentTokens += tokens
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    fun sourceReference(message: AgentModelClient.ConversationMessage): String {
        val stableId = message.messageId.ifBlank {
            sha256(
                message.role + '\u0000' + message.content + '\u0000' +
                    message.contentJson + '\u0000' + message.reasoningContent,
            ).take(12)
        }
        return "${message.role}:$stableId:${sha256(message.content).take(12)}"
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
