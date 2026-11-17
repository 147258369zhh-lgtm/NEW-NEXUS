package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val modelId: String,
    val displayName: String,
    val ownedBy: String? = null,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextWindow: Int? = null,
    val contextWindowOverride: Int? = null,
    val inputModalities: List<String> = listOf(TEXT_MODALITY),
    val outputModalities: List<String> = listOf(TEXT_MODALITY),
    val attachment: Boolean? = null,
    val toolCall: Boolean? = null,
    val tryUnknownCapabilities: Boolean = false,
    val reasoning: Boolean? = null,
    val reasoningCapabilities: ModelReasoningCapabilities? = null,
    val reasoningOverride: Boolean? = null,
    val reasoningCapabilitiesOverride: ModelReasoningCapabilities? = null,
    val structuredOutput: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val source: ModelSource = ModelSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
) {
    val effectiveContextWindow: Int?
        get() = contextWindowOverride?.takeIf { it > 0 }?.let { configured ->
            contextWindow?.takeIf { it > 0 }?.let { minOf(configured, it) } ?: configured
        }

    val effectiveReasoning: Boolean?
        get() = reasoningOverride ?: reasoning

    val effectiveReasoningCapabilities: ModelReasoningCapabilities?
        get() = when (reasoningOverride) {
            false -> null
            true -> reasoningCapabilitiesOverride ?: ModelReasoningCapabilities()
            null -> reasoningCapabilities
        }

    val supportsVision: Boolean
        get() = attachment ?: (inputModalities.any { it.equals(IMAGE_MODALITY, ignoreCase = true) } || tryUnknownCapabilities && isChatModel())

    val supportsTools: Boolean
        get() = toolCall ?: (tryUnknownCapabilities && isChatModel())

    val supportsReasoning: Boolean
        get() = effectiveReasoning == true

    companion object {
        const val TEXT_MODALITY = "text"
        const val IMAGE_MODALITY = "image"
    }
}

@Serializable
enum class ModelSource {
    MANUAL,
    REMOTE,
    CATALOG,
    LOCAL,
}
