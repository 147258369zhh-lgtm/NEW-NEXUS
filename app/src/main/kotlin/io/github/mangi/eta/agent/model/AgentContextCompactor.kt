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
    private val summaryConfig: AgentModelClient.ModelConfig = config,
    private val summaryProvider: AgentProviderClient = provider,
) {
    var lastDemotedToolResults: Int = 0
        private set
    var summaryFallbackUsed: Boolean = false
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
            // Keep the most recent complete answer after the preserved user request. Moving a
            // finished request to the end without its answer makes it look unexecuted next turn.
            val automaticEnd = automaticEnd(history, config.knownContextWindow)
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
        val summarizer = AgentContextSummarizer(
            config = summaryConfig,
            provider = summaryProvider,
            controller = controller,
            roleplay = roleplay,
            metricsScope = metricsScope,
            summaryTargetContextWindow = config.knownContextWindow,
        )
        val summary = try {
            summarizeInChunks(summarizer, safe)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException || failure is InterruptedException) throw failure
            controller.throwIfCancelled()
            if (sameModel(summaryConfig, config)) throw failure
            summaryFallbackUsed = true
            summarizeInChunks(
                AgentContextSummarizer(config, provider, controller, roleplay, metricsScope),
                safe,
            )
        }
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
        // If the answer itself was too large to retain, keep the original request before the
        // assistant summary. A trailing user message would incorrectly invite execution again.
        val completedRequestWasSummarized = protectedUser != null && end == history.size &&
            history.lastOrNull()?.optString("role") == "assistant"
        if (completedRequestWasSummarized) protectedUser?.let(result::put)
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
        if (!completedRequestWasSummarized) protectedUser?.let(result::put)
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
    ): String {
        val inputLimit = summarizer.historyInputBudget()
        var summaries = summarizeChunks(
            summarizer = summarizer,
            chunks = ContextBudgetPolicy.chunk(history, inputLimit, asSummaryData = true),
        )
        var level = 0
        while (
            summaries.size > 1 &&
            level < MAX_SUMMARY_HIERARCHY_LEVELS &&
            ContextBudgetPolicy.estimateTextTokens(summaries.joinToString("\n\n")) > inputLimit
        ) {
            val summaryMessages = summaries.mapIndexed { index, text ->
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "[历史分块 ${index + 1}/${summaries.size} 的摘要]\n$text",
                    contextSummary = true,
                )
            }
            val chunks = ContextBudgetPolicy.chunk(summaryMessages, inputLimit, asSummaryData = true)
            summaries = summarizeChunks(summarizer, chunks)
            level += 1
        }
        if (ContextBudgetPolicy.estimateTextTokens(summaries.joinToString("\n\n")) > inputLimit) {
            throw failure("CONTEXT_SUMMARY_TOO_LARGE", "分层摘要未能收敛到安全窗口，原始上下文已保留。")
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
            }.map { future ->
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
        private const val MAX_SUMMARY_HIERARCHY_LEVELS = 12
        private const val MAX_PARALLEL_SUMMARY_REQUESTS = 3
        fun automaticEnd(history: List<JSONObject>, contextWindow: Int?): Int? {
            val latestAnswer = history.lastOrNull()?.takeIf {
                it.optString("role") == "assistant" && !it.optBoolean("_eta_context_summary") &&
                    AgentConversationCodec.parseToolCalls(it).isEmpty() &&
                    ContextBudgetPolicy.estimateMessages(JSONArray().put(it)) <=
                        ContextBudgetPolicy.forCompaction(contextWindow).usableInputTokens / 3
            }
            // A short first answer and its request have no older history to reduce. Fixed
            // system/tool overhead must not trigger a paid summary of that finished pair.
            // Large answers remain eligible, as do completed tool batches in the first turn.
            val preferredEnd = history.size - if (latestAnswer != null) 1 else 0
            return (preferredEnd downTo 1).firstOrNull { canSplit(history, it) }
        }

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end == 0) return history.firstOrNull()?.optString("role") != "tool"
            if (end < 0 || end > history.size) return false
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

        private fun sameModel(first: AgentModelClient.ModelConfig, second: AgentModelClient.ModelConfig): Boolean =
            first.providerId == second.providerId &&
                first.baseUrl == second.baseUrl &&
                first.authMode == second.authMode &&
                first.model == second.model
    }
}
