package io.github.mangi.eta.agent.runtime

/** Hardware path actually used by an embedded inference request. */
internal enum class LocalRuntimeBackend {
    NPU,
    GPU,
    CPU,
    UNKNOWN,
}

/**
 * One embedded-model inference sample.
 *
 * Values are nullable on purpose: provider counters differ, and a missing GPU/NPU compiler-cache
 * counter must remain unavailable instead of looking like a zero-cost cache hit in statistics.
 */
internal data class LocalRuntimeMetrics(
    val backend: LocalRuntimeBackend,
    val modelFormat: String,
    val modelId: String,
    val backendDetail: String? = null,
    val loadMs: Long? = null,
    val firstTokenMs: Long? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    val promptCacheHitTokens: Int? = null,
    val promptCacheReusedTokens: Int? = null,
    val kvCacheUsedTokens: Int? = null,
    val kvCacheCapacityTokens: Int? = null,
    val contextWindow: Int? = null,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val totalMs: Long? = null,
    val finishReason: String? = null,
    val failureCode: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = startedAt,
) {
    val promptCacheHitRate: Double?
        get() {
            val hit = promptCacheHitTokens?.takeIf { it >= 0 } ?: return null
            val prompt = promptTokens?.takeIf { it > 0 } ?: return null
            return (hit.toDouble() / prompt.toDouble()).coerceIn(0.0, 1.0)
        }

    val kvCacheUsageRate: Double?
        get() {
            val used = kvCacheUsedTokens?.takeIf { it >= 0 } ?: return null
            val capacity = kvCacheCapacityTokens?.takeIf { it > 0 } ?: return null
            return (used.toDouble() / capacity.toDouble()).coerceIn(0.0, 1.0)
        }
}
