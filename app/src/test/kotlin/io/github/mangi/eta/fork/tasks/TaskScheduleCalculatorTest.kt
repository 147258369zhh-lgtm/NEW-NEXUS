package io.github.mangi.eta.fork.tasks

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScheduleCalculatorTest {
    @Test
    fun terminalStatesCannotRestart() {
        AgentTaskState.entries.filter { it.terminal }.forEach { terminal ->
            AgentTaskState.entries.forEach { target ->
                assertFalse("$terminal must not transition to $target", terminal.canTransitionTo(target))
            }
        }
        assertTrue(AgentTaskState.WAITING_INPUT.canTransitionTo(AgentTaskState.RUNNING))
    }

    @Test
    fun intervalSchedulesFromInclusiveStartAndAdvanceExclusively() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.INTERVAL,
            startAt = start,
            intervalMinutes = 15,
            timeZoneId = "UTC",
        )

        assertEquals(start, TaskScheduleCalculator.nextOccurrence(rule, start - 1))
        assertEquals(start + 15 * 60_000L, TaskScheduleCalculator.nextOccurrence(rule, start))
        assertEquals(start + 45 * 60_000L, TaskScheduleCalculator.nextOccurrence(rule, start + 30 * 60_000L))
    }

    @Test
    fun weeklyScheduleUsesMondayBitAndConfiguredZone() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.WEEKLY,
            startAt = start,
            localHour = 9,
            localMinute = 30,
            weekdaysMask = 1,
            timeZoneId = "Asia/Shanghai",
        )

        val next = TaskScheduleCalculator.nextOccurrence(rule, start)

        assertEquals(Instant.parse("2026-01-05T01:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun dstGapMovesForwardByTheZoneOffsetTransition() {
        val start = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.DAILY,
            startAt = start,
            localHour = 2,
            localMinute = 30,
            timeZoneId = "America/New_York",
        )
        val after = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(),
            TaskScheduleCalculator.nextOccurrence(rule, after),
        )
    }

    @Test
    fun dstOverlapUsesTheEarlierOffset() {
        val start = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.DAILY,
            startAt = start,
            localHour = 1,
            localMinute = 30,
            timeZoneId = "America/New_York",
        )
        val after = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),
            TaskScheduleCalculator.nextOccurrence(rule, after),
        )
    }

    @Test
    fun missedRunPoliciesHaveDistinctOutcomes() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-01-05T12:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.DAILY,
            startAt = start,
            localHour = 8,
            localMinute = 0,
            timeZoneId = "UTC",
        )

        val runOnce = TaskScheduleCalculator.decideAfter(
            rule,
            lastScheduledFor = null,
            now = now,
            missedRunPolicy = MissedRunPolicy.RUN_ONCE,
        )
        assertEquals(Instant.parse("2026-01-01T08:00:00Z").toEpochMilli(), (runOnce as MissedRunDecision.RunNow).scheduledFor)
        assertEquals(Instant.parse("2026-01-06T08:00:00Z").toEpochMilli(), runOnce.nextScheduledFor)

        val skip = TaskScheduleCalculator.decideAfter(rule, null, now, MissedRunPolicy.SKIP)
        assertEquals(Instant.parse("2026-01-06T08:00:00Z").toEpochMilli(), (skip as MissedRunDecision.Scheduled).scheduledFor)

        val ask = TaskScheduleCalculator.decideAfter(rule, null, now, MissedRunPolicy.ASK_USER)
        assertEquals(Instant.parse("2026-01-01T08:00:00Z").toEpochMilli(), (ask as MissedRunDecision.AskUser).scheduledFor)
    }

    @Test
    fun endTimeExhaustsRecurringSchedule() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val rule = AgentScheduleRule(
            kind = AgentScheduleKind.DAILY,
            startAt = start,
            endAt = Instant.parse("2026-01-02T23:59:59Z").toEpochMilli(),
            localHour = 8,
            localMinute = 0,
            timeZoneId = "UTC",
        )

        val after = Instant.parse("2026-01-02T08:00:00Z").toEpochMilli()
        assertNull(TaskScheduleCalculator.nextOccurrence(rule, after))
    }
}
