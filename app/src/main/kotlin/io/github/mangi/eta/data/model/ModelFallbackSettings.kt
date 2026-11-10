package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** Optional user-selected backup model used only for transient primary failures. */
@Serializable
internal data class ModelFallbackSettings(
    val enabled: Boolean = false,
    /** Model.id, resolved against the currently configured providers. */
    val modelId: String? = null,
    /** Optional provider guard for model IDs that are duplicated across providers. */
    val providerId: String? = null,
    val maxAttemptsPerRun: Int = 1,
) {
    fun normalized(): ModelFallbackSettings = copy(
        modelId = modelId?.trim()?.takeIf(String::isNotEmpty),
        providerId = providerId?.trim()?.takeIf(String::isNotEmpty),
        maxAttemptsPerRun = maxAttemptsPerRun.coerceIn(1, 1),
    )
}
