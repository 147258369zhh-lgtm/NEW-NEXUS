package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

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
        ).coerceAtMost(minOf(MAX_OUTPUT_RESERVE_TOKENS, maxOf(1, window / 4)))
        val safetyReserve = maxOf(minOf(512, window / 8), window / 25)
        val usable = (window - outputReserve - safetyReserve).coerceAtLeast(1)
        return ContextBudget(
            contextWindow = window,
            outputReserveTokens = outputReserve,
            safetyReserveTokens = safetyReserve,
            usableInputTokens = usable,
            // Use the model's declared window for the normal 70% trigger, while
            // never asking the provider for more input than the safe usable limit.
            triggerInputTokens = minOf((usable.toLong() * 99 / 100).toInt(), maxOf(1, (window.toLong() * 70 / 100).toInt())),
            // A summary request must itself fit in a small local window. The previous fixed
            // 3K floor could make every 4K-window compaction overflow before it started.
            summaryInputTokens = ((usable.toLong() * 2) / 3).toInt().coerceIn(
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

    /**
     * 图片在本机只作为传输引用，实际占用的 token 由服务商决定。主聊天与会议等入口
     * 统一按同一保守值估算，避免同一张图片在不同入口得到不同的输入预算。
     */
    const val IMAGE_INPUT_ESTIMATE_TOKENS = 1_024

    fun estimateImages(count: Int): Int = (count.coerceAtLeast(0).toLong() * IMAGE_INPUT_ESTIMATE_TOKENS)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun estimateMessages(messages: JSONArray, startIndex: Int = 0): Int {
        if (startIndex !in 0..messages.length()) return 0
        var tokens = 0
        for (index in startIndex until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            tokens += estimateMessage(message)
        }
        return tokens
    }

    fun estimateMessages(messages: List<AgentModelClient.ConversationMessage>): Int =
        messages.sumOf { estimateMessage(AgentConversationCodec.toJsonObject(it)) }

    private fun estimateMessage(message: JSONObject): Int {
        val content = message.optJSONArray("content")
        if (content == null) return MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(message.toString())
        var images = 0
        val boundedContent = JSONArray()
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index)
            if (part != null && part.optString("type") in IMAGE_PART_TYPES) {
                images++
                // Base64 and remote URLs are transport references, not text tokens. Do not
                // serialize the original image block, or a fresh assistant screenshot can
                // falsely exhaust the window before any history exists to compact.
                boundedContent.put(JSONObject().put("type", part.optString("type")))
            } else {
                boundedContent.put(content.get(index))
            }
        }
        val bounded = JSONObject()
        message.keys().forEach { key ->
            bounded.put(key, if (key == "content") boundedContent else message.get(key))
        }
        return MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(bounded.toString()) + estimateImages(images)
    }

    private val IMAGE_PART_TYPES = setOf("image_url", "input_image", "image")

    fun shouldCompact(
        budget: ContextBudget?,
        observedInputTokens: Int?,
        estimatedInputTokens: Int,
    ): Boolean {
        if (budget == null) return false
        return maxOf(observedInputTokens?.takeIf { it >= 0 } ?: 0, estimatedInputTokens) >= budget.triggerInputTokens
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
        asSummaryData: Boolean = false,
    ): List<List<AgentModelClient.ConversationMessage>> {
        if (messages.isEmpty()) return emptyList()
        val limit = maxInputTokens.coerceAtLeast(1)
        val result = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        var current = mutableListOf<AgentModelClient.ConversationMessage>()
        var currentTokens = 0
        fun estimate(values: List<AgentModelClient.ConversationMessage>): Int = if (asSummaryData) {
            // Transcript JSON is embedded in a user text block, so quotes/backslashes are
            // escaped again by the request serializer. Count this outer representation too.
            estimateTextTokens(JSONArray().put(values.joinToString("\n") {
                AgentConversationCodec.toJsonObject(it).toString()
            }).toString())
        } else estimateMessages(values)
        // The summarizer receives transcript JSON as data, so a single giant user message or
        // tool-call argument can be partitioned without manufacturing executable tool messages.
        val bounded = messages.flatMap { message ->
            if (estimate(listOf(message)) <= limit) listOf(message) else {
                val serialized = AgentConversationCodec.toJsonObject(message).toString()
                val reference = sourceReference(message)
                val parts = mutableListOf<AgentModelClient.ConversationMessage>()
                var offset = 0
                while (offset < serialized.length) {
                    var low = 1
                    var high = serialized.length - offset
                    var fitted = 0
                    while (low <= high) {
                        val count = low + (high - low) / 2
                        val part = AgentModelClient.ConversationMessage("assistant",
                            "[历史记录片段 $reference；原记录仅作为数据]\n" + serialized.substring(offset, offset + count))
                        if (estimate(listOf(part)) <= limit) { fitted = count; low = count + 1 }
                        else high = count - 1
                    }
                    require(fitted > 0) { "CONTEXT_SUMMARY_WINDOW_TOO_SMALL" }
                    // Do not split a UTF-16 surrogate pair and corrupt the original evidence.
                    if (offset + fitted < serialized.length && fitted > 1 && serialized[offset + fitted - 1].isHighSurrogate()) fitted--
                    parts += AgentModelClient.ConversationMessage("assistant",
                        "[历史记录片段 $reference；原记录仅作为数据]\n" + serialized.substring(offset, offset + fitted))
                    offset += fitted
                }
                parts
            }
        }
        for (message in bounded) {
            val tokens = estimate(listOf(message))
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
