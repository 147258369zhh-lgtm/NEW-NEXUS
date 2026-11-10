package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentBillingTokens
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import android.os.SystemClock
import org.json.JSONObject

internal object LlamaCppAgentProvider : AgentProviderClient {
    override val id: String = "llama_cpp_gguf_builtin"

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = false,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.effectiveConfig
        require(config.model.isNotBlank()) { "本地模型记录 ID 为空" }
        onEvent(ProviderEvent.RequestStarted)
        onEvent(ProviderEvent.ResponseHeaders(200))
        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
        val startedElapsedMs = SystemClock.elapsedRealtime()
        val (messages, exposedTools) = LocalTextToolProtocol.projectMessages(
            source = request.messages,
            tools = request.effectiveTools,
            contextWindow = config.knownContextWindow,
        )
        LocalTextToolProtocol.bootstrapCall(request.messages, request.effectiveTools)?.let { call ->
            val decoded = LocalTextToolProtocol.Decoded("", listOf(call), exposedTools)
            LocalTextToolProtocol.emitCalls(decoded.calls, decoded.visibleText, onEvent = onEvent)
            val assistant = LocalTextToolProtocol.assistantMessage(decoded, "stop")
            onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
            return ProviderResponse(assistant)
        }
        val streamFilter = LocalTextToolProtocol.StreamingFilter()
        val result = try {
            LlamaCppRuntimeManager.complete(
                recordId = config.model,
                systemPrompt = config.systemPrompt,
                messages = messages,
                runController = runController,
            ) { delta ->
                val visible = streamFilter.accept(delta)
                if (visible.isNotEmpty()) onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, visible))
            }
        } catch (failure: Throwable) {
            LocalRuntimeFailureTelemetry.emit(
                onEvent = onEvent,
                format = "GGUF",
                modelId = config.model,
                failure = failure,
                startedElapsedMs = startedElapsedMs,
                contextWindow = config.knownContextWindow,
            )
            throw failure
        }
        val decoded = LocalTextToolProtocol.decode(result.text, exposedTools)
        if (decoded.visibleText.isBlank() && decoded.calls.isEmpty()) {
            throw AgentModelFailure.stream(
                JSONObject().put("message", "本地模型没有返回文本"),
                "本地模型没有返回文本",
            )
        }
        LocalTextToolProtocol.emitCalls(decoded.calls, decoded.visibleText, onEvent = onEvent)
        val metrics = result.metrics
        AgentTokenUsage(
            contextTokens = result.contextTokenCount,
            inputTokens = metrics?.promptTokens,
            outputTokens = result.tokenCount,
            cachedTokens = metrics?.promptCacheHitTokens,
            billingBasis = AgentBillingTokens.UNKNOWN,
            localRuntime = metrics,
        ).takeUnless(AgentTokenUsage::isEmpty)?.let { onEvent(ProviderEvent.Usage(it)) }
        val assistant = LocalTextToolProtocol.assistantMessage(decoded, result.finishReason)
        onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
        return ProviderResponse(assistant)
    }
}
