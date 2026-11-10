package io.github.mangi.eta.fork.tasks

import androidx.room.withTransaction
import java.util.UUID

internal class AutomationRepository(private val db: AgentTaskDatabase) {
    suspend fun save(existing: AgentTaskDefinitionEntity?, title: String, prompt: String, spec: AutomationSpec,
        trigger: AutomationTriggerEntity?, schedule: AgentScheduleRule?, kind: AgentTaskRunKind = AgentTaskRunKind.CHAT, target: String = "default",
        missedRunPolicy: MissedRunPolicy = MissedRunPolicy.RUN_ONCE): String = db.withTransaction {
        require(title.isNotBlank() && title.length <= 100 && prompt.length in 1..12000)
        spec.validate()
        val dao = db.agentTaskDao(); val now = System.currentTimeMillis()
        val id = existing?.id ?: UUID.randomUUID().toString()
        require(dao.activeCount(id) == 0) { "请先结束该任务的运行再修改" }
        if (existing != null) require(dao.definition(id)?.updatedAt == existing.updatedAt) { "任务已变化，请重新打开" }
        val definition = AgentTaskDefinitionEntity(id, title.trim(), kind, target, prompt.trim(), existing?.participantConfigJson ?: "{}", spec.json(), existing?.enabled ?: true,
            existing?.createdAt ?: now, now)
        if (existing == null) dao.insertDefinition(definition) else dao.updateDefinition(definition)
        val oldSchedule = dao.schedulesNow().firstOrNull { it.definitionId == id }
        if (schedule == null) dao.deleteSchedules(id)
        else {
            val next = TaskScheduleCalculator.nextOccurrence(schedule, maxOf(now - 1, schedule.startAt - 1))
            val value = AgentTaskScheduleEntity(oldSchedule?.id ?: UUID.randomUUID().toString(), id, schedule.kind, schedule.startAt, schedule.endAt,
                schedule.intervalMinutes, schedule.localHour, schedule.localMinute, schedule.weekdaysMask, schedule.timeZoneId, missedRunPolicy,
                null, next, !definition.enabled, (oldSchedule?.revision ?: -1) + 1, oldSchedule?.createdAt ?: now, now, schedule.monthDay)
            if (oldSchedule == null) dao.insertSchedule(value) else dao.updateSchedule(value)
        }
        val previousTrigger = db.triggerDao().all().firstOrNull { it.definitionId == id }
        val other = db.triggerDao().all().filter { it.definitionId != id }
        val value = trigger?.copy(id = "trigger:$id", definitionId = id)
        if (value != null) {
            require(value.cooldownSeconds in 60..86400)
            AutomationTriggerConfig.validate(AutomationTrigger.valueOf(value.type), value.source)
            if (value.type in setOf(AutomationTrigger.TASK_FAILED.name, AutomationTrigger.TASK_SUCCEEDED.name)) {
                require(dao.definition(value.source) != null) { "来源任务不存在" }
                require(value.source != id) { "任务不能触发自己" }
                // New dependency starts after the latest completed run, never replays historical tasks.
            }
            AutomationTriggers.requireAcyclic(other + value)
        }
        db.triggerDao().deleteFor(id)
        if (value != null) {
            val baseline = if (value.type in setOf(AutomationTrigger.TASK_FAILED.name, AutomationTrigger.TASK_SUCCEEDED.name))
                dao.runsForDefinition(value.source).filter { it.state == if (value.type == AutomationTrigger.TASK_FAILED.name) AgentTaskState.FAILED else AgentTaskState.SUCCEEDED }
                    .maxByOrNull { it.finishedAt ?: 0 }?.id.orEmpty() else ""
            val sameSource = previousTrigger?.let { it.type == value.type && it.source == value.source && it.extension == value.extension } == true
            db.triggerDao().save(if (sameSource) value.copy(cursor = previousTrigger.cursor, lastFiredAt = previousTrigger.lastFiredAt, detail = previousTrigger.detail)
                else value.copy(cursor = baseline))
        }
        id
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = db.withTransaction {
        val dao = db.agentTaskDao(); val definition = dao.definition(id) ?: return@withTransaction
        dao.updateDefinition(definition.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        dao.schedulesNow().filter { it.definitionId == id }.forEach { schedule ->
            if (db.triggerDao().all().none { it.definitionId == id }) AgentTaskRepository(dao).setPaused(schedule.id, !enabled)
        }
    }
}
