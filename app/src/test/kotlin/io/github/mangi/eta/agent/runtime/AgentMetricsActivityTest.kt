package io.github.mangi.eta.agent.runtime

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AgentMetricsActivityTest {
    @Test fun normalizedTokenTotalsIncludeAnthropicCacheAndGeminiReasoningOnlyOnce() {
        val anthropic = AgentRunMetrics(inputTokens = 100, outputTokens = 20, uncachedInputTokens = 100, cacheReadTokens = 200, cacheWriteTokens = 50, billableOutputTokens = 20)
        assertEquals(370L, anthropic.reportedTotalTokens())
        val gemini = AgentRunMetrics(inputTokens = 300, outputTokens = 20, reasoningTokens = 10, uncachedInputTokens = 100, cacheReadTokens = 200, cacheWriteTokens = 0, billableOutputTokens = 30)
        assertEquals(330L, gemini.reportedTotalTokens())
        assertNull(AgentRunMetrics().reportedTotalTokens())
    }
    @Test fun calendarUsesLocalDatesAndDoesNotInventZeroForMissingUsage() {
        val today = LocalDate.of(2026, 11, 7)
        val zone = ZoneId.of("Asia/Shanghai")
        val time = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val metrics = AgentRunMetrics(rounds = 1, inputTokens = 100, outputTokens = 10, inputReportedRounds = 1, outputReportedRounds = 1)
        val row = AgentRunMetricsRecord("r", "r", "owner", "chat", "chat", "chat", "", "", "", time, time, time, 10, "succeeded", 1, 0, metrics)
        val days = AgentMetricsActivity.days(listOf(row, row.copy(recordId = "unknown", metrics = AgentRunMetrics(rounds = 1))), emptyMap(), today, zone)
        assertEquals(java.time.DayOfWeek.MONDAY, days.first().date.dayOfWeek)
        assertEquals(2, days.last().runs)
        assertEquals(110L, days.last().tokens)
        assertFalse(days.last().complete)
        assertNull(days.first().tokens)
        assertEquals(1, AgentMetricsActivity.streak(days, today))
        assertEquals(today, AgentMetricsActivity.date(time, zone))
    }

    @Test fun streakContinuesFromYesterdayUntilTodayHasActivity() {
        val today = LocalDate.of(2026, 11, 7)
        val days = listOf(AgentActivityDay(today.minusDays(2), 1, 2, true), AgentActivityDay(today.minusDays(1), 1, 3, true), AgentActivityDay(today, 0, null, true))
        assertEquals(2, AgentMetricsActivity.streak(days, today))
    }

    @Test fun selectableWindowsUseTheRequestedCalendarSpan() {
        val today = LocalDate.of(2026, 11, 7)
        val oneDay = AgentMetricsActivity.days(emptyList(), emptyMap(), today, ZoneId.of("Asia/Shanghai"), daysBack = 1)
        val sevenDays = AgentMetricsActivity.days(emptyList(), emptyMap(), today, ZoneId.of("Asia/Shanghai"), daysBack = 7)
        val thirtyDays = AgentMetricsActivity.days(emptyList(), emptyMap(), today, ZoneId.of("Asia/Shanghai"), daysBack = 30)
        assertEquals(listOf(today), oneDay.map { it.date })
        assertEquals(today.minusDays(6), sevenDays.first().date)
        assertEquals(7, sevenDays.size)
        assertEquals(today.minusDays(29), thirtyDays.first().date)
        assertEquals(30, thirtyDays.size)
    }

    @Test fun requestTimingKeepsRetriesSeparateAndLeavesOldTextLatencyUnknown() {
        fun event(seq: Long, time: Long, kind: String, label: String = "") = AgentMetricTraceEntity("r", seq, time, kind, 1, label, null)
        val trace = listOf(event(0, 0, "request"), event(1, 100, "response", "429"), event(2, 200, "retry"), event(3, 500, "request"), event(4, 700, "response", "200"), event(5, 900, "first_text"), event(6, 1400, "assistant"))
        val times = AgentRequestTimings.from(trace)
        assertEquals(2, times.size)
        assertEquals(429, times[0].httpCode)
        assertEquals(100L, times[0].responseMs)
        assertNull(times[0].firstTextMs)
        assertNull(times[0].totalMs)
        assertEquals(400L, times[1].firstTextMs)
        assertEquals(900L, times[1].totalMs)
    }
}
