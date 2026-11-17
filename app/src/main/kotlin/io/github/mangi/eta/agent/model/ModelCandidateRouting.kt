package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelCandidatePurpose

internal enum class ModelCandidateSource {
    PRIMARY,
    USER_CANDIDATE,
    FALLBACK,
}

internal data class ModelCandidateRouteRequest(
    val purpose: ModelCandidatePurpose = ModelCandidatePurpose.CHAT,
    val estimatedInputTokens: Int,
    val requiredTools: Boolean = false,
    val requiredVision: Boolean = false,
)

internal data class ModelCandidateRouteOption(
    val providerId: String,
    val modelId: String,
    val config: AgentModelClient.ModelConfig?,
    val source: ModelCandidateSource,
    val authorized: Boolean,
    val enabled: Boolean = true,
    val purpose: ModelCandidatePurpose = ModelCandidatePurpose.ALL,
    val requiredTools: Boolean = false,
    val requiredVision: Boolean = false,
    val minimumContextWindow: Int? = null,
    val priority: Int = 0,
)

internal data class ModelCandidateRejection(
    val providerId: String,
    val modelId: String,
    val source: ModelCandidateSource,
    val reason: String,
)

internal data class ModelCandidateRouteDecision(
    val selected: ModelCandidateRouteOption?,
    val rejections: List<ModelCandidateRejection> = emptyList(),
)

/** Pure, ordered routing. The current chat model is always evaluated first. */
internal object ModelCandidateSelector {
    fun select(
        primary: ModelCandidateRouteOption,
        candidates: List<ModelCandidateRouteOption>,
        request: ModelCandidateRouteRequest,
    ): ModelCandidateRouteDecision {
        val ordered = buildList {
            add(primary)
            addAll(candidates.sortedWith(
                compareBy<ModelCandidateRouteOption> { sourceOrder(it.source) }
                    .thenBy { it.priority }
                    .thenBy { it.providerId }
                    .thenBy { it.modelId },
            ))
        }.distinctBy { it.providerId to it.modelId }

        val rejections = mutableListOf<ModelCandidateRejection>()
        for (candidate in ordered) {
            val rejection = rejection(candidate, request)
            if (rejection == null) return ModelCandidateRouteDecision(candidate, rejections)
            rejections += ModelCandidateRejection(
                providerId = candidate.providerId,
                modelId = candidate.modelId,
                source = candidate.source,
                reason = rejection,
            )
        }
        return ModelCandidateRouteDecision(null, rejections)
    }

    private fun rejection(
        candidate: ModelCandidateRouteOption,
        request: ModelCandidateRouteRequest,
    ): String? {
        if (!candidate.enabled) return "disabled"
        if (!candidate.authorized) return "unauthorized"
        val config = candidate.config ?: return "unavailable"
        if (request.purpose != ModelCandidatePurpose.ALL &&
            candidate.purpose !in setOf(ModelCandidatePurpose.ALL, request.purpose)
        ) return "purpose_mismatch"
        return capabilityRejection(
            config = config,
            request = request.copy(
                requiredTools = request.requiredTools || candidate.requiredTools,
                requiredVision = request.requiredVision || candidate.requiredVision,
            ),
            minimumContextWindow = candidate.minimumContextWindow,
        )
    }

    /**
     * Capability part of the route decision, shared by candidate routing and the
     * pre-Runtime check of an already resolved model. Both therefore read the same
     * `ModelConfig` snapshot instead of re-deriving tools, vision or window per entry.
     */
    fun capabilityRejection(
        config: AgentModelClient.ModelConfig?,
        request: ModelCandidateRouteRequest,
        minimumContextWindow: Int? = null,
    ): String? {
        val resolved = config ?: return "unavailable"
        if (request.requiredTools && !resolved.supportsTools) {
            return "tools_required"
        }
        if (request.requiredVision && !resolved.supportsImageInput) {
            return "vision_required"
        }
        val window = resolved.knownContextWindow
        if (minimumContextWindow != null) {
            if (window == null || window < minimumContextWindow) return "context_window_below_minimum"
        }
        if (window != null) {
            val usableInput = ContextBudgetPolicy.of(window)?.usableInputTokens ?: 0
            if (request.estimatedInputTokens > usableInput) return "input_exceeds_context"
        }
        return null
    }

    private fun sourceOrder(source: ModelCandidateSource): Int = when (source) {
        ModelCandidateSource.PRIMARY -> 0
        ModelCandidateSource.USER_CANDIDATE -> 1
        ModelCandidateSource.FALLBACK -> 2
    }
}
