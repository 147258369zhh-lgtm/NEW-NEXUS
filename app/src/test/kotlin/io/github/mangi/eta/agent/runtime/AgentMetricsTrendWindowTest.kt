package io.github.mangi.eta.agent.runtime

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMetricsTrendWindowTest {
    @Test fun windowsFollowTheVisibleRangeSelector() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 11, 7)
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val todayOnly = AgentMetricsTrend.window(emptyList(), daysBack = 1, now = now, zone = zone)
        val week = AgentMetricsTrend.window(emptyList(), daysBack = 7, now = now, zone = zone)
        val month = AgentMetricsTrend.window(emptyList(), daysBack = 30, now = now, zone = zone)

        assertEquals(listOf(today), todayOnly.map { it.date })
        assertEquals(today.minusDays(6), week.first().date)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(29), month.first().date)
        assertEquals(30, month.size)
    }
}
