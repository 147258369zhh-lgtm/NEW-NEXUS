package io.github.mangi.eta.ui.screens.metrics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMetricsPeriodTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun fixedWindowsIncludeOnlyTheirActualCalendarSpan() {
        assertTrue(metricDateInPeriod(today, 0, null, null, today))
        assertFalse(metricDateInPeriod(today.minusDays(1), 0, null, null, today))
        assertTrue(metricDateInPeriod(today.minusDays(6), 1, null, null, today))
        assertFalse(metricDateInPeriod(today.minusDays(7), 1, null, null, today))
        assertTrue(metricDateInPeriod(today.minusDays(29), 2, null, null, today))
        assertFalse(metricDateInPeriod(today.minusDays(30), 2, null, null, today))
    }

    @Test
    fun customWindowIsInclusiveAndProducesItsRealDayCount() {
        val start = LocalDate.of(2026, 11, 3)
        val end = LocalDate.of(2026, 11, 8)
        assertTrue(metricDateInPeriod(start, 3, start, end, today))
        assertTrue(metricDateInPeriod(end, 3, start, end, today))
        assertFalse(metricDateInPeriod(start.minusDays(1), 3, start, end, today))
        assertFalse(metricDateInPeriod(end.plusDays(1), 3, start, end, today))
        assertEquals(6, metricWindowDays(3, today.minusDays(60), today, start, end))
    }
}
