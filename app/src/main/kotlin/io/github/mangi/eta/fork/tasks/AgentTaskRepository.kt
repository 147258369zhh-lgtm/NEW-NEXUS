package io.github.mangi.eta.fork.tasks

import java.util.UUID

internal class AgentTaskRepository(
    private val dao: AgentTaskDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun create(draft: AgentTaskDraft): AgentScheduledTaskIds {
        val now = clock()
        val definitionId = idFactory()
        val scheduleId = idFactory()
        val rule = draft.schedule
        dao.insertScheduledTask(
            definition = AgentTaskDefinitionEntity(
                id = definitionId,
                title = draft.title,
                runKind = draft.runKind,
                targetId = draft.targetId,
                promptTemplate = draft.promptTemplate,
                participantConfigJson = draft.participantConfigJson,
                policyJson = draft.policyJson,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
            schedule = AgentTaskScheduleEntity(
                id = scheduleId,
                definitionId = definitionId,
                kind = rule.kind,
                startAt = rule.startAt,
                endAt = rule.endAt,
                intervalMinutes = rule.intervalMinutes,
                localHour = rule.localHour,
                localMinute = rule.localMinute,
                weekdaysMask = rule.weekdaysMask,
                monthDay = rule.monthDay,
                timeZoneId = rule.timeZoneId,
                missedRunPolicy = draft.missedRunPolicy,
                lastScheduledFor = null,
                nextRunAt = if (draft.paused) null else nextAfterStart(rule),
                paused = draft.paused,
                revision = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return AgentScheduledTaskIds(definitionId, scheduleId)
    }

    suspend fun setPaused(scheduleId: String, paused: Boolean): Boolean {
        val schedule = dao.schedule(scheduleId) ?: return false
        val now = clock()
        val nextRunAt = if (paused) null else nextAfterNow(schedule.toRule(), now)
        return dao.setSchedulePausedRow(scheduleId, paused, nextRunAt, now) == 1
    }

    suspend fun delete(definitionId: String) {
        dao.deleteDefinition(definitionId)
    }

    /** Queues one user-triggered execution without changing the recurring schedule. */
    suspend fun runNow(definitionId: String): String? {
        val definition = dao.definition(definitionId) ?: return null
        if (!definition.enabled) return null
        val now = clock()
        val runId = idFactory()
        val inserted = dao.enqueueManual(
            AgentTaskRunEntity(
                id = runId,
                definitionId = definition.id,
                scheduleId = null,
                scheduledFor = now,
                attempt = 1,
                idempotencyKey = "manual:${definition.id}:$runId",
                state = AgentTaskState.QUEUED,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return runId.takeIf { inserted }
    }

    private fun nextAfterStart(rule: AgentScheduleRule): Long? =
        TaskScheduleCalculator.nextOccurrence(rule, cursorBefore(rule.startAt))

    private fun nextAfterNow(rule: AgentScheduleRule, now: Long): Long? =
        TaskScheduleCalculator.nextOccurrence(rule, maxOf(now, cursorBefore(rule.startAt)))

    private fun cursorBefore(startAt: Long): Long =
        if (startAt == Long.MIN_VALUE) Long.MIN_VALUE else startAt - 1L

    private fun AgentTaskScheduleEntity.toRule(): AgentScheduleRule = AgentScheduleRule(
        kind = kind,
        startAt = startAt,
        endAt = endAt,
        intervalMinutes = intervalMinutes,
        localHour = localHour,
        localMinute = localMinute,
        weekdaysMask = weekdaysMask,
        timeZoneId = timeZoneId,
        monthDay = monthDay,
    )
}
