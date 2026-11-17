package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentRunTelemetry
import io.github.mangi.eta.agent.runtime.AgentRunTelemetrySnapshot
import io.github.mangi.eta.agent.runtime.AgentTokenPrice
import io.github.mangi.eta.agent.runtime.NexusExecutionRecorder
import io.github.mangi.eta.agent.runtime.NexusRunMapper
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.zhh_lgtm.nexus.application.api.ExecutionStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * A no-tools safety net for failures in the cross-process Runtime handoff.
 * Model/provider failures intentionally do not enter this path.
 */
internal object AgentRuntimeLocalFallback {
    fun shouldUse(request: AgentRuntimeWire.RunRequest, result: AgentRuntimeWire.RunResult): Boolean =
        !io.github.mangi.eta.data.repository.EtaBackupOperations.dataOperationActive && request.operation == AgentRuntimeWire.OP_CHAT &&
            result.operation == AgentRuntimeWire.OP_CHAT &&
            result.runId == request.runId &&
            !result.ok && result.content.isEmpty() && result.reasoningContent.isEmpty() &&
            result.transcript.isEmpty() && result.contextSnapshot == null &&
            result.contextSnapshotRef.isBlank() &&
            // Only these failures prove that model/tool execution never started. A disconnected
            // Binder or failed send has an unknown outcome and must use result recovery instead.
            result.error in setOf(
                "Agent Runtime 服务绑定失败",
                "无法启动后台执行服务，请返回 NEXA 后重试",
                "Agent Runtime 无法准备请求",
            )

    fun run(
        request: AgentRuntimeWire.RunRequest,
        runController: AgentRunController,
        executionStore: ExecutionStore,
        provider: AgentProviderClient? = null,
        onTelemetry: (AgentRunTelemetrySnapshot) -> Unit = {},
        priceResolver: (String, String) -> AgentTokenPrice? = { _, _ -> null },
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        if (io.github.mangi.eta.data.repository.EtaBackupOperations.dataOperationActive) return AgentRuntimeWire.RunResult(
            request.runId, false, "", "备份或恢复正在处理，请完成后再运行任务", failureCode = "BACKUP_IN_PROGRESS")
        val safeConfig = request.config.copy(
            systemPrompt = request.config.systemPrompt + "\n\n" +
                "The cross-process Agent Runtime is temporarily unavailable. " +
                "Answer this turn directly without tools, device control, files, network browsing, memory writes, or Subagent calls. " +
                "Do not claim that any tool was used.",
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            subagentTools = false,
            supportsSearchSuggestions = false,
            hostedWebSearchEnabled = false,
            extraBodyJson = "",
            customBody = emptyList(),
        )
        val compactionConfig = runCatching {
            val appContext = io.github.mangi.eta.data.datastore.SettingsDataStore.applicationContext()
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val meetingRoomId = request.promptScope.meetingRoomId.takeIf(String::isNotBlank)
                val conversationId = request.promptScope.conversationId.takeIf(String::isNotBlank)
                    ?: request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                        ?.let { io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload.from(it.payload).conversationId }
                        ?.takeIf(String::isNotBlank)
                val scopedBinding = meetingRoomId?.let {
                    io.github.mangi.eta.fork.meeting.MeetingRepository.roomCompactionBinding(appContext, it)
                } ?: conversationId?.let {
                    io.github.mangi.eta.fork.conversations.ConversationExtensionRepository.compactionBinding(appContext, it)
                }
                io.github.mangi.eta.data.repository.RuntimeConfigRepository.compactionRuntimeConfig(safeConfig, scopedBinding)
            }
        }.getOrDefault(safeConfig)
        val recorder = try {
            val ledgerRequest = NexusRunMapper.fromLegacy(
                request.copy(runId = "${request.runId}:local-fallback"),
            ).request
            NexusExecutionRecorder(executionStore, ledgerRequest).also { it.start() }
        } catch (failure: Exception) {
            return ledgerFailure(request, "本地回复未能建立执行记录", failure)
        }
        var terminal = false
        val telemetry = AgentRunTelemetry(request, onSnapshot = onTelemetry, recordId = "${request.runId}:local-reply", priceResolver = priceResolver)
        val result = runCatching {
            val response = AgentModelClient.complete(
                config = safeConfig,
                compactionConfig = compactionConfig,
                prompt = request.prompt,
                images = request.images,
                history = request.history,
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    AgentModelClient.ToolResult(
                        content = JSONObject()
                            .put("ok", false)
                            .put("code", "LOCAL_FALLBACK_TOOL_DISABLED")
                            .put("message", "Tool ${call.name} is unavailable during local fallback")
                            .toString(),
                    )
                },
                provider = provider ?: ProviderClientFactory.getClient(safeConfig),
                runController = runController,
                skillContext = SkillContext.EMPTY,
                memoryContext = AgentMemoryContext.DISABLED,
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                // Some discovery schemas are independent of the config toggles. Never advertise
                // any tool during fallback, including newly added built-in or remote schemas.
                toolFilter = { JSONArray() },
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = "user-${request.runId}",
                textOnlyFallback = true,
                onEvent = { event ->
                    if (terminal) return@complete
                    when (event) {
                        is AgentEvent.ToolStarted -> {
                            recorder.record(event)
                            recorder.record(AgentEvent.ToolFinished(
                                round = event.round,
                                toolCallId = event.toolCallId,
                                name = event.name,
                                resultSummary = "LOCAL_FALLBACK_TOOL_DISABLED",
                                imageCount = 0,
                                imageBytes = 0,
                                success = false,
                            ))
                            throw IllegalStateException("LOCAL_FALLBACK_UNEXPECTED_TOOL")
                        }
                        is AgentEvent.HostedToolStarted -> {
                            recorder.record(event)
                            recorder.record(AgentEvent.HostedToolFinished(
                                round = event.round,
                                toolCallId = event.toolCallId,
                                name = event.name,
                                success = false,
                            ))
                            throw IllegalStateException("LOCAL_FALLBACK_UNEXPECTED_TOOL")
                        }
                        is AgentEvent.ToolFinished, is AgentEvent.HostedToolFinished ->
                            throw IllegalStateException("LOCAL_FALLBACK_UNEXPECTED_TOOL")
                        else -> Unit
                    }
                    // The durable fact must commit before the same observation reaches the UI.
                    recorder.record(event)
                    telemetry.accept(event)
                    onEvent(event)
                },
            )
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = response.content,
                reasoningContent = response.reasoningContent,
                transcript = response.transcript,
                contextSnapshot = response.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        }.getOrElse { failure ->
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = "Runtime 交接失败，进程内回复也失败：${failure.message ?: failure.javaClass.simpleName}",
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        }
        val cancelled = runController.isUserCancelled
        terminal = true
        try {
            recorder.finish(result, cancelled)
        } catch (failure: Exception) {
            telemetry.finish(false, cancelled)
            return ledgerFailure(request, "本地回复未能写入最终执行记录", failure)
        }
        telemetry.finish(result.ok, cancelled)
        return result
    }

    private fun ledgerFailure(
        request: AgentRuntimeWire.RunRequest,
        message: String,
        failure: Exception,
    ) = AgentRuntimeWire.RunResult(
        runId = request.runId,
        ok = false,
        content = "",
        error = "$message：${failure.message ?: failure.javaClass.simpleName}",
        operation = request.operation,
        rewriteTargetMessageId = request.rewriteTargetMessageId,
        failureCode = "LOCAL_FALLBACK_LEDGER_FAILED",
    )
}
