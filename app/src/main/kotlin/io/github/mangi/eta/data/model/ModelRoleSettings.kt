package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class ModelRoleBinding(
    val modelId: String,
    val providerId: String? = null,
)

internal enum class ModelRolePurpose {
    MEMORY,
    LEARNING,
    TRANSCRIPT,
}

/**
 * Global model-role bindings. A compaction binding may inherit the auxiliary model; when neither
 * is set, the active chat model continues to be used.
 */
@Serializable
internal data class ModelRoleSettings(
    /** Model.id for the shared auxiliary role, resolved against configured providers. */
    val auxiliaryModelId: String? = null,
    val auxiliaryProviderId: String? = null,
    /** Optional compaction override. Unset inherits the auxiliary role and then the chat model. */
    val compactionModelId: String? = null,
    val compactionProviderId: String? = null,
    /** Optional per-purpose overrides; unset inherits the shared auxiliary binding. */
    val memoryModelId: String? = null,
    val memoryProviderId: String? = null,
    val learningModelId: String? = null,
    val learningProviderId: String? = null,
    val transcriptModelId: String? = null,
    val transcriptProviderId: String? = null,
) {
    fun normalized(): ModelRoleSettings = copy(
        auxiliaryModelId = auxiliaryModelId.normalizedId(),
        auxiliaryProviderId = auxiliaryProviderId.normalizedId(),
        compactionModelId = compactionModelId.normalizedId(),
        compactionProviderId = compactionProviderId.normalizedId(),
        memoryModelId = memoryModelId.normalizedId(),
        memoryProviderId = memoryProviderId.normalizedId(),
        learningModelId = learningModelId.normalizedId(),
        learningProviderId = learningProviderId.normalizedId(),
        transcriptModelId = transcriptModelId.normalizedId(),
        transcriptProviderId = transcriptProviderId.normalizedId(),
    ).let { value ->
        // A provider guard without a model cannot select anything and would create a misleading
        // "configured" state in settings.
        value.copy(
            auxiliaryProviderId = value.auxiliaryProviderId.takeIf { value.auxiliaryModelId != null },
            compactionProviderId = value.compactionProviderId.takeIf { value.compactionModelId != null },
            memoryProviderId = value.memoryProviderId.takeIf { value.memoryModelId != null },
            learningProviderId = value.learningProviderId.takeIf { value.learningModelId != null },
            transcriptProviderId = value.transcriptProviderId.takeIf { value.transcriptModelId != null },
        )
    }

    private fun String?.normalizedId(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
