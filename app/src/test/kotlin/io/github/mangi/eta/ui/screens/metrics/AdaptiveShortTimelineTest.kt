package io.github.mangi.eta.ui.screens.metrics

import io.github.mangi.eta.agent.runtime.AgentRunMetrics
import io.github.mangi.eta.agent.runtime.AgentRunMetricsRecord
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShortTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDateTime.of(2026, 11, 16, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun record(end: Long, id: String = "run") = AgentRunMetricsRecord(
        id, id, "owner", "chat", "chat", "chat", "", "", "", start, end, end,
        end - start, "succeeded", 1, 0, AgentRunMetrics(rounds = 1, inputTokens = 100,
            outputTokens = 20, inputReportedRounds = 1, outputReportedRounds = 1),
    )

    @Test fun tenMinuteRunHasMinuteBucketsAndDoesNotInventTokenSamples() {
        val timeline = adaptiveMetricTimeline(listOf(record(start + 10 * 60_000)), zone = zone)
        assertEquals("按分钟", timeline.granularity)
        assertEquals(10, timeline.buckets.size)
        assertEquals(1, timeline.buckets.sumOf { it.runs })
        assertEquals(120L, timeline.buckets.sumOf { it.tokens ?: 0L })
        assertEquals("10:00", timeline.buckets.first().label)
        assertEquals("10:09", timeline.buckets.last().label)
        assertTrue(timeline.buckets.drop(1).all { it.activeWithoutSample && it.tokens == null && it.runs == 0 })
    }

    @Test fun boundaryStartRemainsSelectableWithoutInventingAnotherRun() {
        val later = record(start + 11 * 60_000, "later").copy(startedAt = start + 10 * 60_000)
        val timeline = adaptiveMetricTimeline(listOf(record(start + 10 * 60_000), later), zone = zone)
        assertEquals(11, timeline.buckets.size)
        assertEquals(1, timeline.buckets.last().runs)
        assertEquals("10:10", timeline.buckets.last().label)
        assertEquals(2, timeline.buckets.sumOf { it.runs })
        assertTrue(!metricRunStartedInWindow(record(start + 10 * 60_000), start + 60_000, start + 120_000))
        assertTrue(metricRunStartedInWindow(later, start + 10 * 60_000, start + 11 * 60_000))
        assertTrue(!metricRunStartedInWindow(later, start + 9 * 60_000, start + 10 * 60_000))
    }

    @Test fun longerSessionsUseWiderTimeBuckets() {
        val timeline = adaptiveMetricTimeline(listOf(record(start + 90 * 60_000)), zone = zone)
        assertEquals("每 5 分钟", timeline.granularity)
        assertTrue(timeline.buckets.size in 18..20)
        assertEquals(1, timeline.buckets.sumOf { it.runs })
    }
}
