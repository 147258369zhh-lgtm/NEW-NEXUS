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
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * A no-tools safety net for failures in the cross-process Runtime handoff.
 * Model/provider failures intentionally do not enter this path.
 */
internal object AgentRuntimeLocalFallback {
    fun shouldUse(request: AgentRuntimeWire.RunRequest, result: AgentRuntimeWire.RunResult): Boolean =
        request.operation == AgentRuntimeWire.OP_CHAT &&
            result.operation == AgentRuntimeWire.OP_CHAT &&
            result.runId == request.runId &&
            !result.ok && result.content.isEmpty() && result.reasoningContent.isEmpty() &&
            result.transcript.isEmpty() && result.contextSnapshot == null &&
            result.contextSnapshotRef.isBlank() &&
            // Only these failures prove that model/tool execution never started. A disconnected
            // Binder or failed send has an unknown outcome and must use result recovery instead.
            result.error in setOf(
                "Agent Runtime 服务绑定失败",
                "无法启动后台执行服务，请返回 Nexus 后重试",
                "Agent Runtime 无法准备请求",
            )

    fun run(
        request: AgentRuntimeWire.RunRequest,
        runController: AgentRunController,
        provider: AgentProviderClient? = null,
        onTelemetry: (AgentRunTelemetrySnapshot) -> Unit = {},
        priceResolver: (String, String) -> AgentTokenPrice? = { _, _ -> null },
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
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
        val telemetry = AgentRunTelemetry(request, onSnapshot = onTelemetry, recordId = "${request.runId}:local-reply", priceResolver = priceResolver)
        val result = runCatching {
            val response = AgentModelClient.complete(
                config = safeConfig,
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
                onEvent = { telemetry.accept(it); onEvent(it) },
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
        telemetry.finish(result.ok, runController.isCancelled)
        return result
    }
}
