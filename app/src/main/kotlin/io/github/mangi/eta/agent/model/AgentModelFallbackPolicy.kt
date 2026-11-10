package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent

internal enum class ModelFailureKind { NETWORK, AUTHENTICATION, RATE_LIMIT, SERVER, INVALID_REQUEST, UNKNOWN }

internal data class ModelFallbackDecision(
    val useFallback: Boolean,
    val reason: String,
    val preserveTools: Boolean,
)

/** Decides when an optional small/local model may help; it never silently changes providers. */
internal object AgentModelFallbackPolicy {
    fun decide(
        kind: ModelFailureKind,
        fallbackConfigured: Boolean,
        userEnabled: Boolean,
        canRestart: Boolean = true,
        isChat: Boolean = true,
    ): ModelFallbackDecision {
        if (!fallbackConfigured || !userEnabled) return ModelFallbackDecision(false, "fallback_disabled", false)
        if (!isChat) return ModelFallbackDecision(false, "operation_requires_selected_model", false)
        if (!canRestart) return ModelFallbackDecision(false, "primary_run_has_progress", false)
        return when (kind) {
            ModelFailureKind.NETWORK, ModelFailureKind.RATE_LIMIT, ModelFailureKind.SERVER ->
                ModelFallbackDecision(true, kind.name.lowercase(), preserveTools = false)
            ModelFailureKind.AUTHENTICATION, ModelFailureKind.INVALID_REQUEST, ModelFailureKind.UNKNOWN ->
                ModelFallbackDecision(false, "configuration_requires_user_action", false)
        }
    }

    /** Retry exhaustion wraps the classified failure; quota/permission and unknown errors stay permanent. */
    fun classify(cause: Throwable?): ModelFailureKind {
        var failure = cause as? AgentModelFailure ?: return ModelFailureKind.UNKNOWN
        repeat(8) {
            val original = failure.cause as? AgentModelFailure
            if (original != null && original.code == failure.code) failure = original
        }
        if (failure.code in setOf("PROVIDER_AUTH_FAILED", "HTTP_401", "HTTP_403")) return ModelFailureKind.AUTHENTICATION
        if (!failure.retryable) return ModelFailureKind.INVALID_REQUEST
        return when (failure.code) {
            "MODEL_CONNECTION_FAILED", "MODEL_TIMEOUT", "STREAM_INCOMPLETE", "HTTP_408" -> ModelFailureKind.NETWORK
            "HTTP_429" -> ModelFailureKind.RATE_LIMIT
            "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504", "HTTP_524", "HTTP_529", "PROVIDER_STREAM_ERROR" -> ModelFailureKind.SERVER
            else -> ModelFailureKind.UNKNOWN
        }
    }

    fun constrainConfig(primary: AgentModelClient.ModelConfig, fallback: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        val window = listOfNotNull(primary.knownContextWindow, fallback.knownContextWindow).minOrNull()
        return fallback.copy(
            contextWindow = window,
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            subagentTools = false,
            hostedWebSearchEnabled = false,
            supportsSearchSuggestions = false,
            autoCompactionEnabled = false,
            extraBodyJson = "",
            customBody = emptyList(),
            systemPrompt = primary.systemPrompt.trim() +
                "\n\nThis is a backup model run because the primary model was temporarily unavailable. " +
                "State this clearly. You can only provide a text answer; do not claim to execute tools or device actions.",
        )
    }
}

/** Restarting the original request is safe only before any output, tool, steering or compaction. */
internal class ModelFallbackProgress {
    var canRestart: Boolean = true
        private set
    var lastRound: Int = 0
        private set

    fun accept(event: AgentEvent) {
        if (event is AgentEvent.RoundStarted) lastRound = maxOf(lastRound, event.round)
        when (event) {
            is AgentEvent.AssistantBlockStart, is AgentEvent.AssistantBlockDelta,
            is AgentEvent.AssistantBlockEnd, is AgentEvent.AssistantReceived,
            is AgentEvent.ToolStarted, is AgentEvent.ToolFinished,
            is AgentEvent.HostedToolStarted, is AgentEvent.HostedToolFinished,
            is AgentEvent.UserSupplementReceived, is AgentEvent.ContextCompaction,
            is AgentEvent.SearchSuggestions, is AgentEvent.SubagentProgress -> canRestart = false
            else -> Unit
        }
    }
}
