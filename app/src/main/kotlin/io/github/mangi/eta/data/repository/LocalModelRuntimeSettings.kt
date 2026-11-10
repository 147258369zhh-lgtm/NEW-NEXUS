package io.github.mangi.eta.data.repository

import kotlinx.serialization.Serializable

@Serializable
internal enum class LocalRuntimeBackendPolicy {
    AUTO,
    NPU,
    CPU,
    GPU,
}

@Serializable
internal enum class LocalRuntimeThermalPolicy {
    BALANCED,
    STABILITY_FIRST,
    PAUSE_WHEN_SEVERE,
}

@Serializable
internal enum class LocalRuntimePowerPolicy {
    FOLLOW_SYSTEM,
    PAUSE_IN_BATTERY_SAVER,
}

/**
 * User-controlled knobs for the in-process LiteRT-LM runtime. Every value is consumed by the
 * runtime adapter; this is deliberately not a decorative settings surface.
 */
@Serializable
internal data class LocalModelRuntimeSettings(
    val autoConnect: Boolean = true,
    val autoStart: Boolean = true,
    val backend: LocalRuntimeBackendPolicy = LocalRuntimeBackendPolicy.AUTO,
    val threads: Int = 4,
    val contextWindow: Int = 4_096,
    val maxOutputTokens: Int = 1_024,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val idleUnloadMinutes: Int = 10,
    val thermalPolicy: LocalRuntimeThermalPolicy = LocalRuntimeThermalPolicy.BALANCED,
    val powerPolicy: LocalRuntimePowerPolicy = LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER,
    val thinkingEnabled: Boolean = false,
    val imageInputEnabled: Boolean = false,
) {
    fun validated(): LocalModelRuntimeSettings = copy(
        threads = threads.coerceIn(1, 8),
        contextWindow = contextWindow.coerceIn(1_024, 131_072),
        maxOutputTokens = maxOutputTokens.coerceIn(64, 8_192),
        temperature = temperature.coerceIn(0f, 2f),
        topK = topK.coerceIn(1, 128),
        topP = topP.coerceIn(0.01f, 1f),
        idleUnloadMinutes = idleUnloadMinutes.coerceIn(1, 120),
    )
}

@Serializable
internal data class LocalModelRuntimeSnapshot(
    val recordId: String? = null,
    val state: LocalModelRuntimeState = LocalModelRuntimeState.STOPPED,
    val backend: String = "",
    val message: String = "",
    val loadedAt: Long? = null,
    val lastTokenCount: Int? = null,
)

internal enum class LocalModelRuntimeState {
    STOPPED,
    STARTING,
    READY,
    GENERATING,
    FAILED,
}
