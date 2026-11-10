package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.PROMPT_INJECTION_MARKER
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val metricsScope: io.github.mangi.eta.agent.runtime.AgentMetricScope? = null,
) {
    var lastDemotedToolResults: Int = 0
        private set

    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
        selection: IntRange? = null,
    ): JSONArray {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) {
            throw SignedToolContext.compactionFailure(messages)
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation") &&
                !it.optBoolean(PROMPT_INJECTION_MARKER)
        }
        val selected = if (selection == null) {
            // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之前的已完成批次。
            val automaticEnd = (history.size downTo 1).firstOrNull { canSplit(history, it) }
                ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
            ContextCompactionSelection.Range(systemCount, systemCount + automaticEnd)
        } else {
            runCatching { ContextCompactionSelection.resolve(messages, systemCount, selection) }
                .getOrElse { throw failure("CONTEXT_RANGE_INVALID", it.message ?: "压缩范围不安全。") }
        }
        val start = selected.start - systemCount
        val end = selected.endExclusive - systemCount
        val protectedUser = if (selection == null) {
            // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
            history.getOrNull(latestUser)?.takeIf { latestUser < end }
        } else null
        val source = JSONArray(history.subList(start, end).filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val normalized = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val demoted = ContextBudgetPolicy.demoteOversizedToolResults(normalized)
        lastDemotedToolResults = demoted.demotedCount
        val safe = demoted.messages
        val summarizer = AgentContextSummarizer(config, provider, controller, roleplay, metricsScope)
        val budget = ContextBudgetPolicy.forCompaction(config.knownContextWindow)
        val summary = summarizeInChunks(summarizer, safe, budget)
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val sourceReferences = safe.asSequence()
            .map(ContextBudgetPolicy::sourceReference)
            .distinct()
            .take(MAX_SUMMARY_SOURCE_REFERENCES)
            .joinToString(", ")
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        history.take(start).forEach { result.put(GeminiEphemeralState.withoutState(
            AnthropicEphemeralState.withoutContentBlocks(it),
        )) }
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = buildString {
                append("[Nexus 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n")
                append(summary)
                if (sourceReferences.isNotBlank()) {
                    append("\n\n[Nexus 可溯源引用：").append(sourceReferences).append(']')
                }
                if (demoted.demotedCount > 0) {
                    append("\n[Nexus 已降级 ").append(demoted.demotedCount)
                        .append(" 条超长工具结果；原始结果仍保留在运行存档或对应引用中。]")
                }
            },
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
        )))
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(GeminiEphemeralState.withoutState(
                AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems),
            ))
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return result
    }

    private fun summarizeInChunks(
        summarizer: AgentContextSummarizer,
        history: List<AgentModelClient.ConversationMessage>,
        budget: ContextBudget,
    ): String {
        var summaries = summarizeChunks(
            summarizer = summarizer,
            chunks = ContextBudgetPolicy.chunk(history, budget.summaryInputTokens),
        )
        var level = 0
        while (
            summaries.size > 1 &&
            level < MAX_SUMMARY_HIERARCHY_LEVELS &&
            ContextBudgetPolicy.estimateTextTokens(summaries.joinToString("\n\n")) > budget.summaryInputTokens
        ) {
            val summaryMessages = summaries.mapIndexed { index, text ->
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "[历史分块 ${index + 1}/${summaries.size} 的摘要]\n$text",
                    contextSummary = true,
                )
            }
            val chunks = ContextBudgetPolicy.chunk(summaryMessages, budget.summaryInputTokens)
            if (chunks.size >= summaries.size) break
            summaries = summarizeChunks(summarizer, chunks)
            level += 1
        }
        return summaries.joinToString(separator = "\n\n")
    }

    private fun summarizeChunks(
        summarizer: AgentContextSummarizer,
        chunks: List<List<AgentModelClient.ConversationMessage>>,
    ): List<String> {
        if (chunks.isEmpty()) return emptyList()
        if (chunks.size == 1) {
            return listOf(summarizeChunk(summarizer, chunks.single()))
        }
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_SUMMARY_REQUESTS, chunks.size)) { work ->
            Thread(work, "eta-context-summary").apply { isDaemon = true }
        }
        return try {
            chunks.map { chunk ->
                executor.submit<String> { summarizer.summarize(chunk) }
            }.mapIndexed { index, future ->
                try {
                    future.get()
                } catch (failure: ExecutionException) {
                    controller.throwIfCancelled()
                    throw (failure.cause ?: failure)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun summarizeChunk(
        summarizer: AgentContextSummarizer,
        chunk: List<AgentModelClient.ConversationMessage>,
    ): String = try {
        summarizer.summarize(chunk)
    } catch (failure: Throwable) {
        controller.throwIfCancelled()
        throw failure
    }

    companion object {
        private const val MAX_SUMMARY_SOURCE_REFERENCES = 24
        private const val MAX_SUMMARY_HIERARCHY_LEVELS = 4
        private const val MAX_PARALLEL_SUMMARY_REQUESTS = 3
        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (
                (last.optString("role") == "user" && !last.optBoolean(PROMPT_INJECTION_MARKER)) ||
                history.getOrNull(end)?.optString("role") == "tool"
            ) return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
