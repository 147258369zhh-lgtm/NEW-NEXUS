package io.github.mangi.eta.ui.screens.metrics

import io.github.mangi.eta.agent.runtime.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit

/** Sum only reported samples; preserve null when none were reported. */
internal fun metricKnownSum(metrics: List<AgentRunMetrics>, field: (AgentRunMetrics) -> Long?): Long? =
    metrics.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()

internal data class MetricTimelineBucket(
    val start: LocalDateTime, val endExclusive: LocalDateTime, val runs: Int,
    val input: Long?, val output: Long?, val tokens: Long?, val incomplete: Boolean,
    val displayLabel: String = "",
    val activeWithoutSample: Boolean = false,
) {
    val label: String get() = if(displayLabel.isNotBlank()) displayLabel else if (start.toLocalDate() == endExclusive.minusNanos(1).toLocalDate()) {
        if (start.toLocalTime() != java.time.LocalTime.MIDNIGHT || endExclusive.hour != 0) "%02d时".format(start.hour)
        else "${start.monthValue}/${start.dayOfMonth}"
    } else "${start.monthValue}/${start.dayOfMonth}"
}
internal data class MetricTimeline(val granularity: String, val buckets: List<MetricTimelineBucket>)

internal fun metricRunStartedInWindow(run: AgentRunMetricsRecord, start: Long, endExclusive: Long): Boolean =
    AgentMetricsTime.known(run.startedAt)?.let { it >= start && it < endExclusive } == true

/** Uses retained, selected records, never the wall clock or a fabricated seven-day window. */
internal fun adaptiveMetricTimeline(
    records: List<AgentRunMetricsRecord>,
    usages: Map<String, List<AgentRunMetrics>> = emptyMap(),
    zone: ZoneId = ZoneId.systemDefault(),
): MetricTimeline {
    val dated = records.mapNotNull { run -> AgentMetricsTime.known(run.startedAt)?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() to run
    } }
    val earliest = dated.minOfOrNull { it.first } ?: return MetricTimeline("暂无时间记录", emptyList())
    val latest = dated.maxOf { (started, run) ->
        val end = run.completedAt?.let(AgentMetricsTime::known)
            ?: run.updatedAt.let(AgentMetricsTime::known)
        end?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?.takeIf { !it.isBefore(started) } ?: started
    }
    val minutes = ChronoUnit.MINUTES.between(earliest, latest)
    val days = ChronoUnit.DAYS.between(earliest.toLocalDate(), latest.toLocalDate()) + 1
    // Keep short sessions legible without pretending that an aggregate is a per-minute sample.
    val mode = when {
        minutes <= 30 -> 0
        minutes <= 120 -> 1
        minutes <= 720 -> 2
        minutes <= 2880 -> 3
        days <= 31 -> 4
        days <= 366 -> 5
        else -> 6
    }
    val start = when (mode) {
        0 -> earliest.withSecond(0).withNano(0)
        1 -> earliest.withMinute(earliest.minute / 5 * 5).withSecond(0).withNano(0)
        2 -> earliest.withMinute(earliest.minute / 30 * 30).withSecond(0).withNano(0)
        3 -> earliest.withMinute(0).withSecond(0).withNano(0)
        5 -> earliest.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay()
        6 -> earliest.toLocalDate().withDayOfMonth(1).atStartOfDay()
        else -> earliest.toLocalDate().atStartOfDay()
    }
    // A record database can span years; cap chart density while preserving calendar boundaries.
    val monthStride = if (mode == 6) (ChronoUnit.MONTHS.between(start, latest).toInt() / 30 + 1) else 1
    fun next(value: LocalDateTime) = when (mode) {
        0 -> value.plusMinutes(1); 1 -> value.plusMinutes(5); 2 -> value.plusMinutes(30)
        3 -> value.plusHours(1); 4 -> value.plusDays(1); 5 -> value.plusWeeks(1)
        else -> value.plusMonths(monthStride.toLong())
    }
    // An end exactly on a boundary belongs to the preceding interval; a start at that
    // boundary still needs its own bucket.
    val latestStart = dated.maxOf { it.first }
    val boundaries = generateSequence(start, ::next).takeWhile { it.isBefore(latest) || !it.isAfter(latestStart) }.toList()
    val grouped = dated.groupBy { (time, _) -> when (mode) {
        0 -> time.withSecond(0).withNano(0)
        1 -> time.withMinute(time.minute / 5 * 5).withSecond(0).withNano(0)
        2 -> time.withMinute(time.minute / 30 * 30).withSecond(0).withNano(0)
        3 -> time.withMinute(0).withSecond(0).withNano(0)
        4 -> time.toLocalDate().atStartOfDay()
        5 -> time.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay()
        else -> start.plusMonths((ChronoUnit.MONTHS.between(start, time) / monthStride * monthStride))
    } }
    val intervals = dated.map { (started, run) ->
        val end = run.completedAt?.let(AgentMetricsTime::known) ?: run.updatedAt.let(AgentMetricsTime::known)
        started to (end?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?.takeIf { !it.isBefore(started) } ?: started)
    }.sortedBy { it.first }
    var intervalIndex = 0
    var furthestEnd: LocalDateTime? = null
    val buckets = boundaries.map { boundary ->
        val runs = grouped[boundary].orEmpty().map { it.second }
        val samples = runs.flatMap { usages[it.recordId] ?: listOf(it.metrics) }
        while (intervalIndex < intervals.size && intervals[intervalIndex].first.isBefore(boundary)) {
            val end = intervals[intervalIndex++].second
            if (furthestEnd?.let { end.isAfter(it) } != false) furthestEnd = end
        }
        val continuing = furthestEnd?.isAfter(boundary) == true
        MetricTimelineBucket(boundary, next(boundary), runs.size,
            if(runs.isEmpty()) null else metricKnownSum(samples, ::metricReportedInput),
            if(runs.isEmpty()) null else metricKnownSum(samples) { it.billableOutputTokens ?: it.outputTokens },
            if(runs.isEmpty()) null else metricKnownSum(samples) { it.reportedTotalTokens() }, samples.any { !it.usageComplete },
            displayLabel = when {
                mode <= 2 -> boundary.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                mode == 3 -> boundary.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                mode == 6 -> boundary.format(java.time.format.DateTimeFormatter.ofPattern("yy/MM"))
                mode == 5 && earliest.year != latest.year -> boundary.format(java.time.format.DateTimeFormatter.ofPattern("yy/M/d"))
                else -> ""
            }, activeWithoutSample = continuing && runs.isEmpty())
    }
    return MetricTimeline(when(mode) {
        0 -> "按分钟"; 1 -> "每 5 分钟"; 2 -> "每 30 分钟"; 3 -> "按小时"
        4 -> if (days <= 7) "周视图 · 按天" else "月视图 · 按天"
        5 -> "按自然周"; else -> if(monthStride == 1) "按自然月" else "每 $monthStride 个月"
    }, buckets)
}

/** Parent model ledgers replace only root aggregate usage; children always remain independent. */
internal fun metricScopedModels(overview: AgentMetricOverview): List<AgentMetricModelEntity> =
    overview.models.ifEmpty { listOf(AgentMetricModelEntity(overview.run.recordId, -1,
        overview.run.providerId, overview.run.modelId, overview.run.modelName, overview.run.metrics)) } +
        overview.children.map { it.asModel() }


/** A missing cache component never becomes a reported zero in the hit-rate denominator. */
internal fun metricCacheHitRate(metrics: List<AgentRunMetrics>): Double? {
    val pairs = metrics.mapNotNull { usage ->
        val read = usage.cacheReadTokens ?: usage.cachedTokens ?: return@mapNotNull null
        val input = metricReportedInput(usage)
        input?.takeIf { it > 0L && read <= it }?.let { read to it }
    }
    return pairs.takeIf { it.isNotEmpty() }?.let { it.sumOf { pair -> pair.first } * 100.0 / it.sumOf { pair -> pair.second } }
}


/** Provider-normalized billing input includes cache reads and writes when all components are known. */
internal fun metricReportedInput(usage: AgentRunMetrics): Long? =
    if(usage.uncachedInputTokens != null && usage.cacheReadTokens != null && usage.cacheWriteTokens != null)
        usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens else usage.inputTokens
