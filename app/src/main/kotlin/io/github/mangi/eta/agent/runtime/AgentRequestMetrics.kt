package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

/** Per-request token counters. Null means the provider did not report that field. */
internal data class AgentRequestUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedTokens: Long? = null,
    val contextTokens: Int? = null,
    val uncachedInputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val billableOutputTokens: Long? = null,
) {
    val reported: Boolean
        get() = listOf(inputTokens, outputTokens, reasoningTokens, cachedTokens, contextTokens,
            uncachedInputTokens, cacheReadTokens, cacheWriteTokens, billableOutputTokens)
            .any { it != null }

    companion object {
        fun from(value: AgentTokenUsage): AgentRequestUsage {
            val billing = AgentBillingTokens.from(value)
            return AgentRequestUsage(
                inputTokens = value.inputTokens?.takeIf { it >= 0 }?.toLong(),
                outputTokens = value.outputTokens?.takeIf { it >= 0 }?.toLong(),
                reasoningTokens = value.reasoningTokens?.takeIf { it >= 0 }?.toLong(),
                cachedTokens = value.cachedTokens?.takeIf { it >= 0 }?.toLong(),
                contextTokens = value.contextTokens?.takeIf { it >= 0 },
                uncachedInputTokens = billing.uncachedInput,
                cacheReadTokens = billing.cacheRead,
                cacheWriteTokens = billing.cacheWrite,
                billableOutputTokens = billing.output,
            )
        }
    }
}

internal data class AgentMetricRequest(
    val requestId: String,
    val scopeId: String,
    val order: Long,
    val providerId: String,
    val modelId: String,
    val name: String,
    val round: Int?,
    val attempt: Int,
    val startedAt: Long,
    val startedElapsedMs: Long,
    val completedAt: Long?,
    val completedElapsedMs: Long?,
    val status: String,
    val httpCode: Int?,
    val failureCode: String,
    val responseMs: Long?,
    val firstTextMs: Long?,
    val totalMs: Long?,
    val usage: AgentRequestUsage,
    val price: AgentTokenPrice?,
    val priceCaptured: Boolean,
    val localRuntime: LocalRuntimeMetrics? = null,
) {
    val outputTokensPerSecond: Double?
        get() {
            val tokens = usage.billableOutputTokens ?: usage.outputTokens ?: return null
            val finished = totalMs ?: return null
            val streamingMs = (finished - (firstTextMs ?: 0L)).coerceAtLeast(0L)
            val durationMs = if (streamingMs > 0L) streamingMs else finished
            return if (durationMs > 0L) tokens * 1_000.0 / durationMs else null
        }
}

/** Captures one mutable record per actual provider HTTP attempt, bounded with the surrounding run trace. */
internal class AgentRequestLedger(
    private val scopeId: String,
    private val wallClock: () -> Long,
    private val elapsed: () -> Long,
) {
    private data class ModelSnapshot(
        val providerId: String,
        val modelId: String,
        val name: String,
        val price: AgentTokenPrice?,
        val priceCaptured: Boolean,
    )

    private data class OpenRequest(
        var request: AgentMetricRequest,
        var firstTextElapsed: Long? = null,
    )

    private val requests = mutableListOf<OpenRequest>()
    private val nextAttemptByRound = mutableMapOf<Int, Int>()
    private var nextOrder = 0L

    fun accept(event: AgentEvent, model: AgentModelClient.ModelConfig, modelPrice: AgentTokenPrice?, priceCaptured: Boolean): Boolean {
        val snapshot = ModelSnapshot(
            providerId = model.providerId.take(128),
            modelId = model.model.take(128),
            name = model.modelDisplayName.ifBlank { model.model }
                .filterNot(Char::isISOControl).take(128),
            price = modelPrice,
            priceCaptured = priceCaptured,
        )
        return when (event) {
            is AgentEvent.ProviderRequestStarted -> start(event.round, snapshot).let { true }
            is AgentEvent.ProviderResponseStarted -> update(event.round) { open ->
                val now = elapsed()
                open.request = open.request.copy(
                    httpCode = event.httpCode.takeIf { it in 100..599 },
                    responseMs = (now - open.request.startedElapsedMs).coerceAtLeast(0L),
                )
            }
            is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT && event.deltaChars > 0) {
                update(event.round) { open ->
                    if (open.firstTextElapsed == null) {
                        val now = elapsed()
                        open.firstTextElapsed = now
                        open.request = open.request.copy(firstTextMs = (now - open.request.startedElapsedMs).coerceAtLeast(0L))
                    }
                }
            } else false
            is AgentEvent.AssistantReceived -> finishRequest(event.round, AgentMetricStatus.SUCCEEDED, "")
            is AgentEvent.ModelRetryScheduled -> {
                val failed = finishRequest(event.round, AgentMetricStatus.FAILED, safeCode(event.reasonCode))
                nextAttemptByRound[event.round + 1] = event.attempt.coerceAtLeast(0) + 1
                failed
            }
            is AgentEvent.UsageReceived -> update(event.round) { open ->
                open.request = open.request.copy(
                    usage = AgentRequestUsage.from(event.usage),
                    localRuntime = event.usage.localRuntime,
                )
            }
            is AgentEvent.RunFailed -> finishLatest(AgentMetricStatus.FAILED, "run_failed")
            else -> false
        }
    }

    fun finish(ok: Boolean, cancelled: Boolean, interrupted: Boolean = false): Boolean = finishLatest(
        when {
            cancelled -> AgentMetricStatus.CANCELLED
            interrupted || ok -> AgentMetricStatus.INTERRUPTED
            else -> AgentMetricStatus.FAILED
        },
        "run_${if (cancelled) "cancelled" else if (interrupted || ok) "interrupted" else "failed"}",
    )

    fun snapshot(): List<AgentMetricRequest> = requests.map { it.request }

    private fun start(round: Int, model: ModelSnapshot): Boolean {
        if (requests.size >= MAX_REQUESTS_PER_SCOPE) return false
        val attempt = nextAttemptByRound.remove(round) ?: 1
        val now = elapsed()
        requests += OpenRequest(AgentMetricRequest(
            requestId = "${scopeId.ifBlank { "root" }}:$nextOrder",
            scopeId = scopeId,
            order = nextOrder++,
            providerId = model.providerId,
            modelId = model.modelId,
            name = model.name,
            round = round.takeIf { it > 0 },
            attempt = attempt,
            startedAt = wallClock().coerceAtLeast(0L),
            startedElapsedMs = now,
            completedAt = null,
            completedElapsedMs = null,
            status = AgentMetricStatus.RUNNING,
            httpCode = null,
            failureCode = "",
            responseMs = null,
            firstTextMs = null,
            totalMs = null,
            usage = AgentRequestUsage(),
            price = model.price,
            priceCaptured = model.priceCaptured,
        ))
        return true
    }

    private fun update(round: Int, block: (OpenRequest) -> Unit): Boolean {
        val open = requests.asReversed().firstOrNull { it.request.round == round } ?: return false
        block(open)
        return true
    }

    private fun finishRequest(round: Int, status: String, failureCode: String): Boolean {
        val open = requests.asReversed().firstOrNull { it.request.round == round } ?: return false
        if (open.request.status != AgentMetricStatus.RUNNING) return false
        val now = elapsed()
        open.request = open.request.copy(
            completedAt = wallClock().coerceAtLeast(open.request.startedAt),
            completedElapsedMs = now,
            totalMs = (now - open.request.startedElapsedMs).coerceAtLeast(0L),
            status = status,
            failureCode = safeCode(failureCode),
        )
        return true
    }

    private fun finishLatest(status: String, failureCode: String): Boolean {
        val open = requests.asReversed().firstOrNull { it.request.status == AgentMetricStatus.RUNNING } ?: return false
        val now = elapsed()
        open.request = open.request.copy(
            completedAt = wallClock().coerceAtLeast(open.request.startedAt),
            completedElapsedMs = now,
            totalMs = (now - open.request.startedElapsedMs).coerceAtLeast(0L),
            status = status,
            failureCode = safeCode(failureCode),
        )
        return true
    }

    private fun safeCode(value: String): String = value.takeIf { it.matches(SAFE_CODE) }.orEmpty()

    companion object {
        const val MAX_REQUESTS_PER_SCOPE = 512
        private val SAFE_CODE = Regex("[A-Za-z0-9_.:-]{1,96}")
    }
}
