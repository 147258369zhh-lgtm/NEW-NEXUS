package io.github.mangi.eta.agent.runtime

import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Exact provider/model identity. Equal display names on different providers never share samples. */
internal data class AgentCandidateReliabilityKey(val providerId: String, val modelId: String)

/** One terminal provider attempt reduced to the fields reliability needs. */
internal data class AgentCandidateRequestSample(
    val providerId: String,
    val modelId: String,
    val startedAt: Long,
    val status: String,
    val totalMs: Long?,
    val usage: AgentRequestUsage,
    val price: AgentTokenPrice?,
    val priceCaptured: Boolean,
) {
    companion object {
        fun from(entity: AgentMetricRequestEntity) = AgentCandidateRequestSample(
            providerId = entity.providerId,
            modelId = entity.modelId,
            startedAt = entity.startedAt,
            status = entity.status,
            totalMs = entity.totalMs,
            usage = entity.usage,
            price = entity.price,
            priceCaptured = entity.priceCaptured,
        )
    }
}

/** Measured values only. Unknown price or latency stays null instead of becoming zero. */
internal data class AgentCandidateReliabilityStats(
    val providerId: String,
    val modelId: String,
    val sampleCount: Int,
    val succeeded: Int,
    val failed: Int,
    val successRate: Double?,
    val medianTotalMs: Long?,
    val pricedSampleCount: Int,
    val priceCoverage: Double?,
    val costs: List<AgentCurrencyCost>,
    val dataSufficient: Boolean,
)

/**
 * Read-only aggregation over the existing per-request metric ledger. It deliberately produces no
 * quality score: success rate, median request latency and priced-request cost coverage only.
 */
internal object AgentCandidateReliability {
    const val WINDOW_DAYS = 30
    const val MINIMUM_SAMPLES = 5
    private val terminalStatuses = setOf(AgentMetricStatus.SUCCEEDED, AgentMetricStatus.FAILED)

    fun windowStart(now: Long, days: Int = WINDOW_DAYS): Long =
        now - TimeUnit.DAYS.toMillis(days.coerceAtLeast(1).toLong())

    fun aggregate(
        samples: List<AgentCandidateRequestSample>,
        windowStart: Long,
        windowEnd: Long = Long.MAX_VALUE,
        minimumSamples: Int = MINIMUM_SAMPLES,
    ): Map<AgentCandidateReliabilityKey, AgentCandidateReliabilityStats> {
        val grouped = LinkedHashMap<AgentCandidateReliabilityKey, MutableList<AgentCandidateRequestSample>>()
        for (sample in samples) {
            if (sample.providerId.isBlank() || sample.modelId.isBlank()) continue
            if (sample.status !in terminalStatuses) continue
            if (sample.startedAt < windowStart || sample.startedAt > windowEnd) continue
            grouped.getOrPut(AgentCandidateReliabilityKey(sample.providerId, sample.modelId)) { mutableListOf() }
                .add(sample)
        }
        return grouped.mapValues { (key, group) -> build(key, group, minimumSamples) }
    }

    /**
     * Statistical order used only when the user turns it on. Candidates with fewer than
     * [MINIMUM_SAMPLES] terminal requests keep their manual order at the end.
     */
    fun <T> orderByReliability(
        items: List<T>,
        keyOf: (T) -> AgentCandidateReliabilityKey,
        priorityOf: (T) -> Int,
        stats: Map<AgentCandidateReliabilityKey, AgentCandidateReliabilityStats>,
    ): List<T> {
        fun ranked(item: T): AgentCandidateReliabilityStats? =
            stats[keyOf(item)]?.takeIf { it.dataSufficient && it.sampleCount > 0 }

        val (measurable, insufficient) = items.partition { ranked(it) != null }
        val sorted = measurable.sortedWith(Comparator { left, right ->
            val a = requireNotNull(ranked(left))
            val b = requireNotNull(ranked(right))
            compareValues(b.successRate ?: 0.0, a.successRate ?: 0.0)
                .takeIf { it != 0 }
                ?: compareNullableLatency(a.medianTotalMs, b.medianTotalMs)
                    .takeIf { it != 0 }
                ?: compareValues(b.sampleCount, a.sampleCount)
                    .takeIf { it != 0 }
                ?: compareValues(priorityOf(left), priorityOf(right))
                    .takeIf { it != 0 }
                ?: compareValues(keyOf(left).providerId, keyOf(right).providerId)
                    .takeIf { it != 0 }
                ?: compareValues(keyOf(left).modelId, keyOf(right).modelId)
        })
        return sorted + insufficient
    }

    private fun compareNullableLatency(left: Long?, right: Long?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }

    private fun build(
        key: AgentCandidateReliabilityKey,
        group: List<AgentCandidateRequestSample>,
        minimumSamples: Int,
    ): AgentCandidateReliabilityStats {
        val succeeded = group.count { it.status == AgentMetricStatus.SUCCEEDED }
        val latencies = group
            .filter { it.status == AgentMetricStatus.SUCCEEDED }
            .mapNotNull { it.totalMs?.takeIf { value -> value > 0 } }
        val costs = sortedMapOf<String, BigDecimal>()
        var priced = 0
        group.forEach { sample ->
            if (!sample.priceCaptured) return@forEach
            val price = sample.price ?: return@forEach
            val cost = AgentTokenCostEstimator.estimateRequest(sample.usage, price)
            val currency = cost.currency ?: return@forEach
            val amount = cost.amount ?: return@forEach
            priced++
            costs[currency] = (costs[currency] ?: BigDecimal.ZERO) + amount
        }
        val total = group.size
        return AgentCandidateReliabilityStats(
            providerId = key.providerId,
            modelId = key.modelId,
            sampleCount = total,
            succeeded = succeeded,
            failed = total - succeeded,
            successRate = if (total == 0) null else succeeded.toDouble() / total,
            medianTotalMs = median(latencies),
            pricedSampleCount = priced,
            priceCoverage = if (total == 0) null else priced.toDouble() / total,
            costs = costs.map { AgentCurrencyCost(it.key, it.value) },
            dataSufficient = total >= minimumSamples,
        )
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
