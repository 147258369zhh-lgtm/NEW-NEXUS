package io.github.mangi.eta.agent.runtime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class AgentMetricDay(
    val date: LocalDate, val runs: Int, val inputTokens: Long?, val outputTokens: Long?, val incompleteUsage: Int,
)

internal object AgentMetricsTrend {
    fun lastWeek(records: List<AgentRunMetricsRecord>, now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(), usages: Map<String, List<AgentRunMetrics>> = emptyMap()): List<AgentMetricDay> =
        window(records, daysBack = 7, now = now, zone = zone, usages = usages)

    fun window(records: List<AgentRunMetricsRecord>, daysBack: Int, now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(), usages: Map<String, List<AgentRunMetrics>> = emptyMap()): List<AgentMetricDay> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val grouped = records.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        val span = daysBack.coerceAtLeast(1).toLong()
        return ((span - 1) downTo 0).map { offset ->
            val date = today.minusDays(offset)
            val runs = grouped[date].orEmpty()
            val metrics = runs.flatMap { usages[it.recordId] ?: listOf(it.metrics) }
            fun sum(field: (AgentRunMetrics) -> Long?) = metrics.mapNotNull(field)
                .takeIf { it.isNotEmpty() }?.sum()
            AgentMetricDay(date, runs.size, sum { it.inputTokens }, sum { it.outputTokens },
                metrics.count { !it.usageComplete })
        }
    }
}
