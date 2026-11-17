package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentConcurrencyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 在 turn 结束后注入；Run 预算控制每次模型请求、工具批次与总时长。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
    private val initialRound: Int = 1,
    private val metricsScope: io.github.mangi.eta.agent.runtime.AgentMetricScope? = null,
    private val budgetGuard: AgentRunBudgetGuard = AgentRunBudgetGuard(AgentRunBudgets.default(), initialRound),
    private val maxParallelAgents: Int = io.github.mangi.eta.data.model.SubagentSettings.DEFAULT_MAX_PARALLEL_AGENTS,
    /** 本地 ToolDescriptor 决定的批次并发槽位；默认全部串行，未知工具不会被动并行。 */
    private val toolBatchSlot: (String) -> ToolBatchSlot = { ToolBatchSlot.SERIAL },
    private val compactionConfig: AgentModelClient.ModelConfig = config,
    private val compactionProvider: AgentProviderClient = provider,
    private val captureModelMedia: (Int, List<AgentModelClient.ToolFile>) -> List<io.github.mangi.eta.agent.runtime.AgentArtifactReference> = { _, _ -> emptyList() },
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private val toolBatchLock = Any()
    private var pendingToolImageMessage: JSONObject? = null
    private var sideEffectfulToolStarted = false
    private var overflowCompactionAttempted = false
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
        metricsScope = metricsScope,
        compactionConfig = compactionConfig,
        compactionProvider = compactionProvider,
    )
    private var supplementIndex = initialSupplementIndex

    /** Capture the submitted assistant call before entering a potentially blocking input tool. */
    private fun executeWithInvocation(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val captured = if (call.name == "request_user_input" || call.name.startsWith("mcp_")) synchronized(toolBatchLock) {
            AgentConversationCodec.encodeTranscriptForStorage(AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds))
        } else ""
        return io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.withToolInvocation(
            io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.ToolInvocation(call.id, call.name, config.providerId, config.model, captured),
        ) { toolExecutor.execute(call) }
    }

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(selection: IntRange? = null): Result {
        context.compact(force = true, selection = selection)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = initialRound
        var precedingTools: JSONArray? = null

        while (true) {
            runController.throwIfCancelled()
            budgetGuard.checkRound(round)
            if (purpose.acceptsSteering) appendPendingSteeringMessage()
            var responseOutputStarted = false

            // 带签名的工具链沿用发出调用时的请求前缀，工具链完成后再刷新目录。
            val roundTools = if (SignedToolContext.hasPendingResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact(tools = roundTools)
            var roundInputTokens: Int? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            var completedRound: AgentModelRetry.Result? = null
            try {
                while (completedRound == null) {
                    val requestMessages = AssistantScreenContextProjection.project(
                        roleplayContext?.projectMessages(messages) ?: messages,
                    )
                    var hostedToolStarted = false
                    try {
                        completedRound = modelRetry.complete(
                            initialRound = round,
                            request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose,
                                contextOutputTokenCeiling = ContextBudgetPolicy.forCompaction(config.knownContextWindow).outputReserveTokens),
                            provider = provider,
                            controller = runController,
                            onEvent = { event ->
                                if (event is AgentEvent.RoundStarted) {
                                    budgetGuard.checkRound(event.round)
                                    roundInputTokens = null
                                }
                                onEvent(event)
                            },
                            onProviderEvent = { attemptRound, providerEvent ->
                                if (
                                    providerEvent is ProviderEvent.BlockDelta ||
                                    providerEvent is ProviderEvent.HostedToolStarted ||
                                    providerEvent is ProviderEvent.SearchSuggestions
                                ) {
                                    // A block header is emitted before several providers have sent
                                    // the request or finished prefill. It does not prove that model
                                    // output reached the user, so context overflow after this point
                                    // may still be compacted and retried safely.
                                    responseOutputStarted = true
                                }
                                if (providerEvent is ProviderEvent.HostedToolStarted) {
                                    hostedToolStarted = true
                                    budgetGuard.beforeHostedToolCall()
                                }
                                if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                        providerEvent is ProviderEvent.SearchSuggestions ||
                                        providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                                    throw unexpectedToolFailure()
                                }
                                if (providerEvent is ProviderEvent.Usage) {
                                    roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                                }
                                if (providerEvent is ProviderEvent.BlockDelta &&
                                    providerEvent.kind == AssistantBlockKind.THINKING
                                ) {
                                    accumulatedReasoning.append(providerEvent.delta)
                                }
                                providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                            },
                            discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                        ).also { response ->
                            rejectContentFilter(response.response)
                            if (purpose.acceptsSteering) validateChatResponse(response.response)
                        }
                    } catch (failure: Exception) {
                        val classified = AgentModelFailure.transport(failure) ?: throw failure
                        if (
                            classified.code == "CONTEXT_OVERFLOW" &&
                            purpose.allowsTools &&
                            !overflowCompactionAttempted &&
                            !responseOutputStarted &&
                            !sideEffectfulToolStarted &&
                            !hostedToolStarted
                        ) {
                            overflowCompactionAttempted = true
                            accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                            roundInputTokens = null
                            context.compact(force = true, tools = roundTools)
                            round += 1
                            continue
                        }
                        throw failure
                    }
                }
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            val successfulRound = checkNotNull(completedRound)
            context.observeInputTokens(roundInputTokens)
            round = successfulRound.round
            val providerResponse = successfulRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw unexpectedToolFailure()
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            if (purpose in setOf(ProviderRequestPurpose.MEMORY_EXTRACTION, ProviderRequestPurpose.TRANSCRIPT_REFINE) &&
                providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("MODEL_TASK_INCOMPLETE", false, "整理模型未完整返回，原始内容已保留。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            val media = ProviderMediaOutput.takeFiles(assistantMessage)
            val modelArtifacts = if (media.isEmpty()) emptyList() else captureModelMedia(round, media)
            if (media.isNotEmpty() && modelArtifacts.isEmpty() && assistantMessage.optString("content").isBlank() && toolCalls.isEmpty()) {
                throw AgentModelFailure("MODEL_MEDIA_CAPTURE_FAILED", false, "模型返回的媒体未能保存，请重试。")
            }
            if (modelArtifacts.isNotEmpty() && assistantMessage.optString("content").isBlank() && toolCalls.isEmpty()) {
                // Keep a readable transcript and terminal result for a media-only response.
                assistantMessage.put("content", modelArtifacts.joinToString("\n") { it.name })
            }
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                    artifacts = modelArtifacts,
                )
            )

            if (toolCalls.isNotEmpty()) {
                // A batch can stop on a local approval gate. Publish the assistant tool call before
                // executing anything so a paused run can persist what it asked for and what already
                // happened, instead of replaying finished work after approval.
                publishTranscript()
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason) { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                    publishTranscript()
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.acceptsSteering && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if ((content.isBlank() || content == "null") && modelArtifacts.isEmpty()) {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true, tools = roundTools)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    private fun unexpectedToolFailure() = if (purpose == ProviderRequestPurpose.FALLBACK) {
        AgentModelFailure("FALLBACK_TOOLS_DISABLED", false, "备用模型请求了工具，已停止；本次未执行工具。")
    } else if (purpose == ProviderRequestPurpose.REPLY_REWRITE) {
        AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
    } else {
        AgentModelFailure("MODEL_TASK_TOOL_CALL", false, "整理模型请求了工具，已停止；本次未执行工具。")
    }

    /**
     * 拒答或过滤会在流中途截断回复，半截正文、思考块和工具调用都不能进入上下文：
     * 带着被截断的签名块继续请求会被上游判定为改动了 thinking 块，同一会话随后每轮都会 400。
     * 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                explanation?.let { append("服务商说明：").append(it) }
                append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        if (ProviderMediaOutput.hasFiles(message)) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
        onOutcome: (ToolOutcome) -> Unit,
    ): List<ToolOutcome> {
        if (stopReason != AssistantStopReason.TOOL_USE) {
            return toolCalls.map { call ->
                when (stopReason) {
                    AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                        round, call, "TRUNCATED_TOOL_CALL",
                        "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                    )
                    else -> rejectedToolOutcome(
                        round, call, "UNEXPECTED_TOOL_CALL",
                        "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                    )
                }.also(onOutcome)
            }
        }

        val outcomes = mutableListOf<ToolOutcome>()
        var index = 0
        while (index < toolCalls.size) {
            val current = toolCalls[index]
            if (current.name == AgentSubagentToolCatalog.TOOL_NAME && current.name in availableToolNames) {
                val parallelBatch = mutableListOf<AgentModelClient.ToolCall>()
                while (
                    index < toolCalls.size &&
                    toolCalls[index].name == AgentSubagentToolCatalog.TOOL_NAME &&
                    toolCalls[index].name in availableToolNames
                ) {
                    parallelBatch += toolCalls[index].copy(round = round)
                    index += 1
                }
                if (parallelBatch.size == 1) {
                    outcomes += executeTool(round, parallelBatch.single(), onOutcome)
                } else {
                    outcomes += executeParallelSubagents(round, parallelBatch, onOutcome)
                }
            } else {
                val readRun = ToolBatchScheduler.parallelReadRun(
                    calls = toolCalls,
                    start = index,
                    isAvailable = { it in availableToolNames },
                    slotOf = toolBatchSlot,
                )
                if (readRun == null) {
                    outcomes += executeTool(round, current.copy(round = round), onOutcome)
                    index += 1
                } else {
                    outcomes += executeParallelReadTools(
                        round,
                        readRun.map { toolCalls[it].copy(round = round) },
                        onOutcome,
                    )
                    index = readRun.last + 1
                }
            }
        }
        return outcomes
    }

    private fun executeParallelSubagents(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
        onOutcome: (ToolOutcome) -> Unit,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        budgetGuard.reserveToolCalls(calls.size)
        if (calls.isNotEmpty()) sideEffectfulToolStarted = true
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val completed = java.util.concurrent.atomic.AtomicReferenceArray<ToolOutcome?>(calls.size)
        val limit = maxParallelAgents.coerceIn(1, 8)
        val permits = Semaphore(limit)
        val waiting = AtomicInteger(0)
        val outcomes = try { runBlocking(Dispatchers.IO) {
            supervisorScope {
                calls.mapIndexed { index, toolCall ->
                    async {
                        val waitsForPermit = permits.availablePermits <= 0
                        if (waitsForPermit) {
                            val queuePosition = waiting.incrementAndGet()
                            val type = runCatching { JSONObject(toolCall.argumentsJson).optString("subagent_type") }
                                .getOrDefault("").ifBlank { "subagent" }
                            onEvent(AgentEvent.SubagentQueued(round, toolCall.id, type, limit, queuePosition))
                        }
                        val waitStarted = System.nanoTime()
                        var holdsQueuedSlot = waitsForPermit
                        val result = try {
                            permits.withPermit {
                                if (holdsQueuedSlot) {
                                    holdsQueuedSlot = false
                                    waiting.decrementAndGet()
                                }
                                val waitedMillis = if (waitsForPermit) (System.nanoTime() - waitStarted) / 1_000_000 else 0L
                                if (waitsForPermit) {
                                    val type = runCatching { JSONObject(toolCall.argumentsJson).optString("subagent_type") }
                                        .getOrDefault("").ifBlank { "subagent" }
                                    onEvent(AgentEvent.SubagentAdmitted(round, toolCall.id, type, waitedMillis))
                                }
                                SubagentConcurrencyGate.shared.withPermit(
                                    controller = runController,
                                    onQueued = { position ->
                                        onEvent(AgentEvent.SubagentQueued(
                                            round, toolCall.id, subagentType(toolCall),
                                            SubagentConcurrencyGate.DEFAULT_GLOBAL_LIMIT, position,
                                        ))
                                    },
                                    onAdmitted = { waitedMillis ->
                                        onEvent(AgentEvent.SubagentAdmitted(
                                            round, toolCall.id, subagentType(toolCall), waitedMillis,
                                        ))
                                    },
                                ) { executeWithInvocation(toolCall) }
                            }
                        } catch (throwable: Exception) {
                            if (runController.isCancelled) {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "SUBAGENT_CANCELLED")
                                        .put("message", "已停止")
                                        .toString(),
                                )
                            } else {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "TOOL_ERROR")
                                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                        .toString(),
                                )
                            }
                        } finally {
                            // 取消或异常时排队位置必须回收，迟到调用不会让后续排队序号一直虚高。
                            if (holdsQueuedSlot) {
                                holdsQueuedSlot = false
                                waiting.decrementAndGet()
                            }
                        }
                        val outcome = ToolOutcome(toolCall, result)
                        completed.set(index, outcome)
                        synchronized(toolBatchLock) {
                            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                sensitiveToolCallIds += toolCall.id
                            }
                            if (!runController.isCancelled) {
                                emitToolFinished(round, toolCall, result)
                            }
                        }
                        outcome
                    }
                }.awaitAll()
            }
        } } finally {
            // Retain settled results even if another child or cancellation terminates the batch.
            for (index in calls.indices) completed.get(index)?.let(onOutcome)
        }
        runController.throwIfCancelled()
        return outcomes
    }

    /**
     * 只读工具按本地并发键同时执行：不同资源键的调用可以重叠，结果仍按模型给出的顺序
     * 写回 transcript。已结算的结果先落账；取消后尚未启动的调用只留下显式回执，
     * 不会在执行器之外静默丢失，也不会再次执行。
     */
    private fun executeParallelReadTools(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
        onOutcome: (ToolOutcome) -> Unit,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        budgetGuard.reserveToolCalls(calls.size)
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val completed = java.util.concurrent.atomic.AtomicReferenceArray<ToolOutcome?>(calls.size)
        val outcomes = try {
            runBlocking(Dispatchers.IO) {
                supervisorScope {
                    calls.mapIndexed { index, toolCall ->
                        async {
                            val result = if (runController.isCancelled) {
                                cancelledToolResult()
                            } else {
                                try {
                                    executeWithInvocation(toolCall)
                                } catch (throwable: Exception) {
                                    if (runController.isCancelled ||
                                        throwable is kotlin.coroutines.cancellation.CancellationException
                                    ) {
                                        cancelledToolResult()
                                    } else {
                                        AgentModelClient.ToolResult(
                                            content = JSONObject()
                                                .put("ok", false)
                                                .put("code", "TOOL_ERROR")
                                                .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                                .toString(),
                                        )
                                    }
                                }
                            }
                            val outcome = ToolOutcome(toolCall, result)
                            completed.set(index, outcome)
                            synchronized(toolBatchLock) {
                                if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                    sensitiveToolCallIds += toolCall.id
                                }
                                if (!runController.isCancelled) {
                                    emitToolFinished(round, toolCall, result)
                                }
                            }
                            outcome
                        }
                    }.awaitAll()
                }
            }
        } finally {
            // 与子 Agent 批次一致：中断时已结算的只读结果仍按原顺序进入 transcript。
            for (index in calls.indices) completed.get(index)?.let(onOutcome)
        }
        runController.throwIfCancelled()
        return outcomes
    }

    private fun cancelledToolResult(): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = JSONObject()
            .put("ok", false)
            .put("code", "TOOL_CANCELLED")
            .put("message", "已停止；该只读调用未执行")
            .toString(),
    )

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        onOutcome: (ToolOutcome) -> Unit,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "工具未在本轮能力目录中提供，请使用当前可用工具",
            ).also(onOutcome)
        }
        val roundCall = if (toolCall.round == round) toolCall else toolCall.copy(round = round)
        budgetGuard.beforeToolCall()
        // 即使工具随后失败，也可能已经产生外部副作用；后续模型溢出不得自动重放。
        sideEffectfulToolStarted = true
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = roundCall.id,
                name = roundCall.name,
                argsPreview = traceFormatter.summarizeArguments(roundCall),
                command = traceFormatter.displayCommand(roundCall),
            )
        )

        val result = try {
            if (roundCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
                SubagentConcurrencyGate.shared.withPermit(
                    controller = runController,
                    onQueued = { position ->
                        onEvent(AgentEvent.SubagentQueued(
                            round, roundCall.id, subagentType(roundCall),
                            SubagentConcurrencyGate.DEFAULT_GLOBAL_LIMIT, position,
                        ))
                    },
                    onAdmitted = { waitedMillis ->
                        onEvent(AgentEvent.SubagentAdmitted(
                            round, roundCall.id, subagentType(roundCall), waitedMillis,
                        ))
                    },
                ) { executeWithInvocation(roundCall) }
            } else {
                executeWithInvocation(roundCall)
            }
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        val outcome = ToolOutcome(roundCall, result)
        try { emitToolFinished(round, roundCall, result) }
        finally { onOutcome(outcome) }
        return outcome
    }

    private fun subagentType(toolCall: AgentModelClient.ToolCall): String =
        runCatching { JSONObject(toolCall.argumentsJson).optString("subagent_type") }
            .getOrDefault("").ifBlank { "subagent" }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        val artifact = if (toolCall.name in setOf("write_file", "edit_file", "copy_file", "move_file", "voice_synthesize", "deliver_file")) runCatching { JSONObject(result.content) }
            .getOrNull()?.takeIf { it.optBoolean("ok") && it.optString("artifact_id").matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) }
            else null
        val artifacts = result.artifacts.ifEmpty { artifact?.let {
            listOf(io.github.mangi.eta.agent.runtime.AgentArtifactReference(it.getString("artifact_id"),
                it.optString("path").substringAfterLast('/').substringAfterLast('\\').take(255).ifBlank { "文件" },
                io.github.mangi.eta.data.repository.NexusArtifactMedia.mimeType(it.optString("mime_type"), it.optString("path"))))
        }.orEmpty() }
        val workflow = if (toolCall.name in setOf("plan_manage", "goal_manage", "notepad_manage"))
            runCatching { JSONObject(result.content) }.getOrNull()?.takeIf { it.optBoolean("ok") }
                ?.optString("execution_reference")?.takeIf {
                    it.matches(Regex("nexus-workflow://[a-f0-9]{64}/(?:plan|goal|notepad)/[a-f0-9]{64}"))
                } else null
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                effectUnknown = traceFormatter.isUnconfirmedResult(result),
                command = result.command,
                artifactId = artifacts.firstOrNull()?.id,
                artifactName = artifacts.firstOrNull()?.name,
                artifacts = artifacts,
                workflowReference = workflow,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
