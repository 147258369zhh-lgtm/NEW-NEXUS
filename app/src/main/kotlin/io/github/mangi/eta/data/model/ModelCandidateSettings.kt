package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal enum class ModelCandidatePurpose {
    ALL,
    CHAT,
    MEETING,
    AUTOMATION,
    AUXILIARY,
    COMPACTION,
    SUBAGENT,
}

/** Stable model binding plus the minimum capabilities it must satisfy. */
@Serializable
internal data class ModelCandidateBinding(
    val providerId: String = "",
    val modelId: String = "",
    val enabled: Boolean = true,
    val purpose: ModelCandidatePurpose = ModelCandidatePurpose.CHAT,
    val requiredTools: Boolean = false,
    val requiredVision: Boolean = false,
    val minimumContextWindow: Int? = null,
    val priority: Int = 0,
) {
    fun normalized(): ModelCandidateBinding? {
        val normalizedProvider = providerId.trim()
        val normalizedModel = modelId.trim()
        if (normalizedProvider.isEmpty() || normalizedModel.isEmpty()) return null
        if (normalizedProvider.length > 128 || normalizedModel.length > 128) return null
        return copy(
            providerId = normalizedProvider,
            modelId = normalizedModel,
            minimumContextWindow = minimumContextWindow
                ?.takeIf { it > 0 }
                ?.coerceAtMost(MAX_CONTEXT_WINDOW),
            priority = priority.coerceIn(0, MAX_PRIORITY),
        )
    }

    companion object {
        internal const val MAX_CONTEXT_WINDOW = 10_000_000
        private const val MAX_PRIORITY = 10_000
    }
}

@Serializable
internal data class ModelCandidateSettings(
    val enabled: Boolean = false,
    val candidates: List<ModelCandidateBinding> = emptyList(),
) {
    fun normalized(): ModelCandidateSettings = copy(
        candidates = candidates.asSequence()
            .mapNotNull(ModelCandidateBinding::normalized)
            .distinctBy { Triple(it.providerId, it.modelId, it.purpose) }
            .take(MAX_CANDIDATES)
            .toList(),
    )

    companion object {
        const val MAX_CANDIDATES = 16
    }
}
