package io.github.mangi.eta.fork.tasks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal enum class AgentScheduleKind {
    ONCE,
    INTERVAL,
    DAILY,
    WEEKLY,
    MONTHLY,
}

internal enum class MissedRunPolicy {
    SKIP,
    RUN_ONCE,
    ASK_USER,
}

internal data class AgentScheduleRule(
    val kind: AgentScheduleKind,
    val startAt: Long,
    val endAt: Long? = null,
    val intervalMinutes: Long? = null,
    val localHour: Int? = null,
    val localMinute: Int? = null,
    /** Monday is bit 0; Sunday is bit 6. */
    val weekdaysMask: Int? = null,
    val timeZoneId: String = "UTC",
    val monthDay: Int? = null,
) {
    init {
        require(endAt == null || endAt >= startAt) { "endAt must not precede startAt" }
        when (kind) {
            AgentScheduleKind.ONCE -> Unit
            AgentScheduleKind.INTERVAL -> require((intervalMinutes ?: 0L) > 0L) {
                "intervalMinutes must be positive"
            }
            AgentScheduleKind.DAILY,
            AgentScheduleKind.WEEKLY,
            AgentScheduleKind.MONTHLY,
            -> {
                require(localHour != null && localHour in 0..23) { "localHour must be in 0..23" }
                require(localMinute != null && localMinute in 0..59) { "localMinute must be in 0..59" }
            }
        }
        if (kind == AgentScheduleKind.WEEKLY) {
            require((weekdaysMask ?: 0) in 1..WEEKDAY_ALL) { "weekdaysMask must contain at least one weekday" }
        }
        require(runCatching { ZoneId.of(timeZoneId) }.isSuccess) { "Unknown time zone: $timeZoneId" }
        if (kind == AgentScheduleKind.MONTHLY) require(monthDay != null && monthDay in 1..31)
    }

    companion object {
        const val WEEKDAY_ALL = 0b1111111
    }
}

internal sealed interface MissedRunDecision {
    data class Scheduled(val scheduledFor: Long) : MissedRunDecision
    data class RunNow(val scheduledFor: Long, val nextScheduledFor: Long?) : MissedRunDecision
    data class AskUser(val scheduledFor: Long) : MissedRunDecision
    data object Exhausted : MissedRunDecision
}

/** Pure calculation; persistence advances the cursor only after the decision is committed. */
internal object TaskScheduleCalculator {
    fun nextOccurrence(rule: AgentScheduleRule, afterExclusive: Long): Long? = when (rule.kind) {
        AgentScheduleKind.ONCE -> rule.startAt.takeIf { it > afterExclusive && rule.isWithinEnd(it) }
        AgentScheduleKind.INTERVAL -> nextInterval(rule, afterExclusive)
        AgentScheduleKind.DAILY -> nextCalendar(rule, afterExclusive, weekly = false)
        AgentScheduleKind.WEEKLY -> nextCalendar(rule, afterExclusive, weekly = true)
        AgentScheduleKind.MONTHLY -> nextMonthly(rule, afterExclusive)
    }

    fun decideAfter(
        rule: AgentScheduleRule,
        lastScheduledFor: Long?,
        now: Long,
        missedRunPolicy: MissedRunPolicy,
    ): MissedRunDecision {
        val startCursor = cursorBefore(rule.startAt)
        val cursor = maxOf(lastScheduledFor ?: startCursor, startCursor)
        val next = nextOccurrence(rule, cursor) ?: return MissedRunDecision.Exhausted
        if (next > now) return MissedRunDecision.Scheduled(next)
        return when (missedRunPolicy) {
            MissedRunPolicy.RUN_ONCE -> MissedRunDecision.RunNow(
                scheduledFor = next,
                nextScheduledFor = nextOccurrence(rule, now),
            )
            MissedRunPolicy.ASK_USER -> MissedRunDecision.AskUser(next)
            MissedRunPolicy.SKIP -> nextOccurrence(rule, now)
                ?.let(MissedRunDecision::Scheduled)
                ?: MissedRunDecision.Exhausted
        }
    }

    private fun nextMonthly(rule: AgentScheduleRule, afterExclusive: Long): Long? {
        val zone = ZoneId.of(rule.timeZoneId)
        val month = java.time.YearMonth.from(Instant.ofEpochMilli(maxOf(afterExclusive, cursorBefore(rule.startAt))).atZone(zone))
        for (offset in 0..2) {
            val current = month.plusMonths(offset.toLong())
            val date = current.atDay(minOf(rule.monthDay!!, current.lengthOfMonth()))
            val millis = resolveLocal(date, LocalTime.of(rule.localHour!!, rule.localMinute!!), zone).toInstant().toEpochMilli()
            if (millis > afterExclusive && millis >= rule.startAt && rule.isWithinEnd(millis)) return millis
        }
        return null
    }

    private fun nextInterval(rule: AgentScheduleRule, afterExclusive: Long): Long? {
        val intervalMillis = Math.multiplyExact(rule.intervalMinutes!!, 60_000L)
        if (afterExclusive < rule.startAt) return rule.startAt.takeIf { rule.isWithinEnd(it) }
        val elapsed = afterExclusive - rule.startAt
        val nextStep = elapsed / intervalMillis + 1L
        val next = runCatching { Math.addExact(rule.startAt, Math.multiplyExact(nextStep, intervalMillis)) }
            .getOrNull() ?: return null
        return next.takeIf { rule.isWithinEnd(it) }
    }

    private fun nextCalendar(rule: AgentScheduleRule, afterExclusive: Long, weekly: Boolean): Long? {
        val zone = ZoneId.of(rule.timeZoneId)
        val boundaryMillis = maxOf(afterExclusive, cursorBefore(rule.startAt))
        val startDate = Instant.ofEpochMilli(boundaryMillis).atZone(zone).toLocalDate()
        val time = LocalTime.of(rule.localHour!!, rule.localMinute!!)
        val maxDays = if (weekly) 8 else 2
        for (offset in 0..maxDays) {
            val date = startDate.plusDays(offset.toLong())
            if (weekly && !rule.matches(date.dayOfWeek)) continue
            val candidate = resolveLocal(date, time, zone)
            val millis = candidate.toInstant().toEpochMilli()
            if (millis > afterExclusive && millis >= rule.startAt && rule.isWithinEnd(millis)) {
                return millis
            }
        }
        return null
    }

    private fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
        LocalDateTime.of(date, time).atZone(zone).withEarlierOffsetAtOverlap()

    private fun AgentScheduleRule.matches(dayOfWeek: DayOfWeek): Boolean =
        weekdaysMask?.and(1 shl (dayOfWeek.value - 1)) != 0

    private fun AgentScheduleRule.isWithinEnd(candidate: Long): Boolean = endAt == null || candidate <= endAt

    private fun cursorBefore(startAt: Long): Long =
        if (startAt == Long.MIN_VALUE) Long.MIN_VALUE else startAt - 1L
}
