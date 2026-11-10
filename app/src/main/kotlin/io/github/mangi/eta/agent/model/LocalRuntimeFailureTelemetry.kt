package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentBillingTokens
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.LocalRuntimeBackend
import io.github.mangi.eta.agent.runtime.LocalRuntimeMetrics
import org.json.JSONObject

/** Builds an honest failure sample from only the counters available at the exception boundary. */
internal object LocalRuntimeFailureTelemetry {
    fun code(failure: Throwable): String {
        if (failure is AgentModelFailure) return failure.code
        if (failure is AgentRunCancelledException || failure is InterruptedException) return "CANCELLED"
        val message = failure.message.orEmpty().lowercase()
        return when {
            "context" in message && listOf("full", "overflow", "too many", "token").any(message::contains) ||
                "上下文" in message -> "CONTEXT_OVERFLOW"
            listOf(
                "加载失败", "无法创建", "模型文件已丢失", "模型目录已丢失", "模型记录不存在",
                "引擎尚未加载", "load failed",
            )
                .any(message::contains) -> "MODEL_LOAD_FAILED"
            else -> "GENERATION_FAILED"
        }
    }

    fun metrics(
        format: String,
        modelId: String,
        failure: Throwable,
        backend: LocalRuntimeBackend = LocalRuntimeBackend.UNKNOWN,
        backendDetail: String? = null,
        loadMs: Long? = null,
        firstTokenMs: Long? = null,
        stats: JSONObject? = null,
        contextWindow: Int? = null,
        totalMs: Long? = null,
    ): LocalRuntimeMetrics {
        val promptTokens = stats?.optInt("promptTokens", -1)?.takeIf { it >= 0 }
        val generatedTokens = stats?.optInt("generatedTokens", -1)?.takeIf { it >= 0 }
        val reusedTokens = stats?.optInt("reusedTokens", -1)?.takeIf { it >= 0 }
        val kvUsed = stats?.optInt("kvUsed", -1)?.takeIf { it >= 0 }
        val prefillUs = stats?.optLong("prefillUs", -1L)?.takeIf { it >= 0L }
        val generateUs = stats?.optLong("generateUs", -1L)?.takeIf { it >= 0L }
        val completedAt = System.currentTimeMillis()
        return LocalRuntimeMetrics(
            backend = backend,
            modelFormat = format,
            modelId = modelId,
            backendDetail = backendDetail,
            loadMs = loadMs,
            firstTokenMs = firstTokenMs,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            tokensPerSecond = generatedTokens?.takeIf { it > 0 }?.let { tokens ->
                generateUs?.takeIf { it > 0L }?.let { tokens * 1_000_000.0 / it }
            },
            promptCacheHitTokens = reusedTokens,
            promptCacheReusedTokens = reusedTokens,
            kvCacheUsedTokens = kvUsed,
            kvCacheCapacityTokens = contextWindow,
            contextWindow = contextWindow,
            prefillMs = prefillUs?.div(1_000L),
            decodeMs = generateUs?.div(1_000L),
            totalMs = totalMs,
            finishReason = if (failure is AgentRunCancelledException) "cancelled" else "failure",
            failureCode = code(failure),
            startedAt = completedAt - (totalMs ?: 0L),
            completedAt = completedAt,
        )
    }

    fun attach(failure: Throwable, metrics: LocalRuntimeMetrics): Throwable = when (failure) {
        is AgentModelFailure -> failure.withLocalRuntime(metrics)
        is AgentRunCancelledException -> AgentRunCancelledException(metrics)
        else -> AgentModelFailure(
            code = metrics.failureCode ?: code(failure),
            retryable = false,
            message = failure.message?.takeIf { it.isNotBlank() }
                ?: "本地模型推理失败（${failure.javaClass.simpleName}）",
            cause = failure,
            localRuntime = metrics,
        )
    }

    fun emit(
        onEvent: (ProviderEvent) -> Unit,
        format: String,
        modelId: String,
        failure: Throwable,
        startedElapsedMs: Long,
        contextWindow: Int? = null,
    ) {
        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
        val metrics = when (failure) {
            is AgentModelFailure -> failure.localRuntime
            is AgentRunCancelledException -> failure.localRuntime
            else -> null
        } ?: metrics(
            format = format,
            modelId = modelId,
            failure = failure,
            contextWindow = contextWindow,
            totalMs = elapsedMs,
        )
        onEvent(ProviderEvent.Usage(AgentTokenUsage(
            contextTokens = metrics.kvCacheUsedTokens,
            inputTokens = metrics.promptTokens,
            outputTokens = metrics.generatedTokens,
            cachedTokens = metrics.promptCacheHitTokens,
            billingBasis = AgentBillingTokens.UNKNOWN,
            localRuntime = metrics,
        )))
    }
}
