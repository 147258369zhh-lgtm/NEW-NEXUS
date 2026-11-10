package io.github.mangi.eta.agent.runtime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class AgentActivityDay(val date: LocalDate, val runs: Int, val tokens: Long?, val complete: Boolean)

internal object AgentMetricsActivity {
    fun days(records: List<AgentRunMetricsRecord>, metrics: Map<String, List<AgentRunMetrics>>,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), daysBack: Int = 91): List<AgentActivityDay> {
        val grouped = records.groupBy { date(it.startedAt, zone) }
        val start = if (daysBack == 91) {
            today.minusWeeks(12).with(java.time.DayOfWeek.MONDAY)
        } else {
            today.minusDays(daysBack.coerceAtLeast(1).toLong() - 1)
        }
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { day ->
            val runs = grouped[day].orEmpty()
            val usages = runs.flatMap { metrics[it.recordId] ?: listOf(it.metrics) }
            val values = usages.mapNotNull { it.reportedTotalTokens() }
            AgentActivityDay(day, runs.size, values.takeIf { it.isNotEmpty() }?.sum(), usages.all { it.usageComplete })
        }.toList()
    }
    fun date(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    fun streak(days: List<AgentActivityDay>, today: LocalDate = LocalDate.now()): Int {
        val active = days.filter { it.runs > 0 }.map { it.date }.toSet()
        var day = if (today in active) today else today.minusDays(1)
        var count = 0
        while (day in active) { count++; day = day.minusDays(1) }
        return count
    }
}
