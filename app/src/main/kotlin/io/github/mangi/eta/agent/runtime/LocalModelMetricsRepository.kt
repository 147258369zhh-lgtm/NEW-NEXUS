package io.github.mangi.eta.agent.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class LocalModelMetricSample(
    val runId: String,
    val requestId: String,
    val providerId: String,
    val metrics: LocalRuntimeMetrics,
)

internal data class LocalModelMetricSummary(
    val sampleCount: Long,
    val averageLoadMs: Double?,
    val averageFirstTokenMs: Double?,
    val averageTokensPerSecond: Double?,
    val promptCacheHitRate: Double?,
    val kvCacheUsageRate: Double?,
    val totalPromptTokens: Long?,
    val totalGeneratedTokens: Long?,
)

/** Stable read/write boundary for future local-model statistics screens and exports. */
internal interface LocalModelMetricsRepository {
    fun record(context: Context, sample: LocalModelMetricSample)
    fun samples(context: Context, modelId: String? = null, limit: Int = 500): Flow<List<LocalModelMetricSample>>
    fun summary(context: Context, modelId: String? = null): Flow<LocalModelMetricSummary?>
    suspend fun clear(context: Context)
}

internal object LocalModelMetricsStore : LocalModelMetricsRepository {
    private const val MAX_SAMPLES = 50_000

    override fun record(context: Context, sample: LocalModelMetricSample) {
        val metrics = sample.metrics
        val dao = LocalModelMetricsDatabase.get(context).dao()
        dao.put(LocalModelMetricEntity(
            sampleId = "${sample.runId.take(220)}:${sample.requestId.take(260)}",
            runId = sample.runId,
            requestId = sample.requestId,
            providerId = sample.providerId,
            modelId = metrics.modelId,
            modelFormat = metrics.modelFormat,
            backend = metrics.backend.name,
            backendDetail = metrics.backendDetail,
            loadMs = metrics.loadMs,
            firstTokenMs = metrics.firstTokenMs,
            promptTokens = metrics.promptTokens,
            generatedTokens = metrics.generatedTokens,
            tokensPerSecond = metrics.tokensPerSecond,
            promptCacheHitTokens = metrics.promptCacheHitTokens,
            promptCacheReusedTokens = metrics.promptCacheReusedTokens,
            kvCacheUsedTokens = metrics.kvCacheUsedTokens,
            kvCacheCapacityTokens = metrics.kvCacheCapacityTokens,
            contextWindow = metrics.contextWindow,
            prefillMs = metrics.prefillMs,
            decodeMs = metrics.decodeMs,
            totalMs = metrics.totalMs,
            finishReason = metrics.finishReason,
            failureCode = metrics.failureCode,
            startedAt = metrics.startedAt,
            completedAt = metrics.completedAt,
        ))
        dao.prune(MAX_SAMPLES)
    }

    override fun samples(context: Context, modelId: String?, limit: Int): Flow<List<LocalModelMetricSample>> =
        LocalModelMetricsDatabase.get(context).dao()
            .samplesFlow(modelId?.take(256), limit.coerceIn(1, MAX_SAMPLES))
            .map { rows -> rows.map { it.toSample() } }
            .flowOn(Dispatchers.IO)

    override fun summary(context: Context, modelId: String?): Flow<LocalModelMetricSummary?> =
        LocalModelMetricsDatabase.get(context).dao().aggregateFlow(modelId?.take(256))
            .map { row -> row?.toSummary() }
            .flowOn(Dispatchers.IO)

    override suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        LocalModelMetricsDatabase.get(context).dao().clear()
    }

    internal fun recordSnapshot(context: Context, recordId: String, request: AgentMetricRequest) {
        val metrics = request.localRuntime ?: return
        record(context, LocalModelMetricSample(
            runId = recordId,
            requestId = request.requestId,
            providerId = request.providerId,
            metrics = metrics,
        ))
    }

    private fun LocalModelMetricEntity.toSample() = LocalModelMetricSample(
        runId = runId,
        requestId = requestId,
        providerId = providerId,
        metrics = LocalRuntimeMetrics(
            backend = runCatching { LocalRuntimeBackend.valueOf(backend) }.getOrDefault(LocalRuntimeBackend.UNKNOWN),
            modelFormat = modelFormat,
            modelId = modelId,
            backendDetail = backendDetail,
            loadMs = loadMs,
            firstTokenMs = firstTokenMs,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            tokensPerSecond = tokensPerSecond,
            promptCacheHitTokens = promptCacheHitTokens,
            promptCacheReusedTokens = promptCacheReusedTokens,
            kvCacheUsedTokens = kvCacheUsedTokens,
            kvCacheCapacityTokens = kvCacheCapacityTokens,
            contextWindow = contextWindow,
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            finishReason = finishReason,
            failureCode = failureCode,
            startedAt = startedAt,
            completedAt = completedAt,
        ),
    )

    private fun LocalModelMetricAggregateRow.toSummary() = LocalModelMetricSummary(
        sampleCount = sampleCount,
        averageLoadMs = averageLoadMs,
        averageFirstTokenMs = averageFirstTokenMs,
        averageTokensPerSecond = averageTokensPerSecond,
        promptCacheHitRate = ratio(totalCacheHitTokens, totalPromptTokens),
        kvCacheUsageRate = ratio(totalKvUsedTokens, totalKvCapacityTokens),
        totalPromptTokens = totalPromptTokens,
        totalGeneratedTokens = totalGeneratedTokens,
    )

    private fun ratio(numerator: Long?, denominator: Long?): Double? {
        val n = numerator?.takeIf { it >= 0L } ?: return null
        val d = denominator?.takeIf { it > 0L } ?: return null
        return (n.toDouble() / d.toDouble()).coerceIn(0.0, 1.0)
    }
}
