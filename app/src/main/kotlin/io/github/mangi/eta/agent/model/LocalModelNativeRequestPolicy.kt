package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings

/** Parameters that require rebuilding the native model/KV cache instead of only the next decode. */
internal fun LocalModelRuntimeSettings.requiresNativeEngineReload(next: LocalModelRuntimeSettings): Boolean =
    backend != next.backend ||
        threads != next.threads ||
        contextWindow != next.contextWindow ||
        imageInputEnabled != next.imageInputEnabled

/**
 * Applies settings changed while a model is resident without ever exceeding the context or backend
 * that were actually allocated during native load. Sampling, output length and thinking changes
 * still take effect on the next request.
 */
internal fun LocalModelRuntimeSettings.withLoadedNativeConstraints(
    loaded: LocalModelRuntimeSettings,
): LocalModelRuntimeSettings = copy(
    backend = loaded.backend,
    threads = loaded.threads,
    contextWindow = minOf(contextWindow, loaded.contextWindow),
)

internal data class NativeGenerationArguments(
    val floats: FloatArray,
    val ints: IntArray,
)

internal fun LocalModelRuntimeSettings.llamaCppGenerationArguments(): NativeGenerationArguments =
    NativeGenerationArguments(
        floats = floatArrayOf(temperature, topP, 0.05f, 1.1f, 0f, 0f),
        ints = intArrayOf(
            topK,
            64,
            generationOutputBudget(),
            -1,
            if (thinkingEnabled) 0 else 1,
            1,
            1,
            256,
        ),
    )

internal fun LocalModelRuntimeSettings.mnnGenerationArguments(): NativeGenerationArguments =
    NativeGenerationArguments(
        floats = floatArrayOf(temperature, topP, 0.05f, 1.1f),
        ints = intArrayOf(
            topK,
            generationOutputBudget(),
            if (thinkingEnabled) 0 else 1,
            0,
            0,
        ),
    )

/**
 * LiteRT-LM treats the per-message output limit and the prompt as one KV budget. Keep the
 * configured output as the ceiling, but never let a small memory-constrained context reserve so
 * much of the window for generation that the latest user turn has no room left.
 */
internal fun LocalModelRuntimeSettings.liteRtOutputBudget(): Int {
    return generationOutputBudget()
}

/** One output ceiling shared by LiteRT, llama.cpp and MNN so small windows cannot reserve too much. */
internal fun LocalModelRuntimeSettings.generationOutputBudget(): Int =
    maxOutputTokens.coerceIn(64, (contextWindow / 4).coerceAtLeast(128))
