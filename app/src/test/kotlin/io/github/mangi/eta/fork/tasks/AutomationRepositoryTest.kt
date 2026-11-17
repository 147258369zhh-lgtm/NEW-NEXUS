package io.github.mangi.eta.fork.tasks

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutomationRepositoryTest {
    private lateinit var db: AgentTaskDatabase
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { AgentTaskDatabase.closeForTests(); context.deleteDatabase("eta_task.db"); db = AgentTaskDatabase.get(context) }
    @After fun close() { AgentTaskDatabase.closeForTests() }
    private suspend fun create(trigger: AutomationTriggerEntity? = null, spec: AutomationSpec = AutomationSpec()): String =
        AutomationRepository(db).save(null, "整理项目文件", "检查输入并生成报告", spec, trigger, null)
    private fun trigger(type: AutomationTrigger = AutomationTrigger.CHARGING, source: String = "") = AutomationTriggerEntity("", "", type.name, source)

    @Test fun nonLocalWorkflowIsRejectedWithoutCreatingDefinition() = runBlocking {
        val repo = AutomationRepository(db)
        for (kind in listOf(AgentTaskRunKind.A2A, AgentTaskRunKind.MEETING)) {
            assertTrue(runCatching {
                repo.save(null, "远程任务", "任务", AutomationSpec(listOf(
                    WorkflowStep("one", WorkflowStepKind.AGENT, "执行", prompt = "处理"),
                )), null, null, kind)
            }.isFailure)
        }
        assertTrue(db.agentTaskDao().definitionsNow().isEmpty())
    }

    @Test fun manualQueueIsAtomicAndPausedTaskCannotBeClaimed() = runBlocking {
        val id = create(); val repo = AgentTaskRepository(db.agentTaskDao())
        val results = coroutineScope { List(10) { async(Dispatchers.IO) { repo.runNow(id) } }.awaitAll() }
        assertEquals(1, results.count { it != null })
        AutomationRepository(db).setEnabled(id, false)
        assertNull(db.agentTaskDao().claimNextQueued("worker", System.currentTimeMillis(), Long.MAX_VALUE))
        AutomationRepository(db).setEnabled(id, true)
        assertNotNull(db.agentTaskDao().claimNextQueued("worker", System.currentTimeMillis(), Long.MAX_VALUE))
    }

    @Test fun eventChecksConditionsAndDeduplicatesDelivery() = runBlocking {
        val id = create(trigger(), AutomationSpec(condition = AutomationCondition(charging = true)))
        val t = db.triggerDao().all().single()
        assertTrue(AutomationTriggers.fire(context, t, "event-1", JSONObject(), AutomationState(false, true, true, 90), "event-1"))
        val detail = db.triggerDao().all().single().detail
        assertTrue(detail.startsWith("事件已记录，等待设备条件满足"))
        assertTrue(detail.contains("充电状态"))
        val pending = db.triggerDao().all().single()
        assertFalse(AutomationTriggers.fire(context, pending, "event-1", JSONObject(), AutomationState(true, true, true, 90), "event-1"))
        assertFalse(AutomationTriggers.fire(context, pending, "event-1", JSONObject(), AutomationState(true, true, true, 90), "event-1"))
        assertEquals(1, db.agentTaskDao().runsForDefinition(id).size)
        assertEquals("TRIGGER", db.agentTaskDao().events(db.agentTaskDao().runsForDefinition(id).single().id).single().eventType)
    }

    @Test fun editingKeepsCursorButChangedDirectoryStartsNewBaseline() = runBlocking {
        val id = create(trigger(AutomationTrigger.FILE_CREATED, "content://folder/one"))
        val t = db.triggerDao().all().single().copy(cursor = "{\"file\":\"version\"}", lastFiredAt = 400, detail = "监听中")
        db.triggerDao().save(t)
        val repo = AutomationRepository(db)
        repo.save(db.agentTaskDao().definition(id), "新标题", "新的要求", AutomationSpec(), t, null)
        assertEquals(t.cursor, db.triggerDao().all().single().cursor)
        assertEquals(400, db.triggerDao().all().single().lastFiredAt)
        repo.save(db.agentTaskDao().definition(id), "新标题", "新的要求", AutomationSpec(), t.copy(source = "content://folder/two"), null)
        assertEquals("", db.triggerDao().all().single().cursor)
    }

    @Test fun cyclesAreRejectedTransactionallyAndActiveDefinitionsCannotChange() = runBlocking {
        val first = create(); val second = create(trigger(AutomationTrigger.TASK_SUCCEEDED, first))
        assertTrue(runCatching { AutomationRepository(db).save(db.agentTaskDao().definition(first), "不能循环", "目标", AutomationSpec(), trigger(AutomationTrigger.TASK_FAILED, second), null) }.isFailure)
        assertEquals("整理项目文件", db.agentTaskDao().definition(first)!!.title)
        AgentTaskRepository(db.agentTaskDao()).runNow(first)
        assertTrue(runCatching { AutomationRepository(db).save(db.agentTaskDao().definition(first), "不能修改", "目标", AutomationSpec(), null, null) }.isFailure)
    }

    @Test fun conditionsDeferSameRunWithoutReplayOrLease() = runBlocking {
        val id = create(); val dao = db.agentTaskDao(); AgentTaskRepository(dao, clock = { 1000L }).runNow(id)
        val coordinator = AgentTaskCoordinator(dao, AgentTaskRunner {
            AgentTaskExecutionResult.Deferred(reason = "充电状态应为充电中，当前未充电")
        }, clock = { 1100L })
        coordinator.tick()
        val run = dao.runsForDefinition(id).single()
        assertEquals(AgentTaskState.QUEUED, run.state); assertEquals(61100L, run.scheduledFor)
        assertNull(dao.lease(run.id)); assertEquals(1, run.attempt)
        val waiting = dao.events(run.id).single { it.eventType == "WAITING_CONDITIONS" }
        assertTrue(JSONObject(waiting.payloadJson).getString("reason").contains("充电状态"))
    }

    @Test fun monthlyCadenceClampsToShortMonths() {
        val zone = java.time.ZoneId.of("UTC")
        val start = java.time.LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val next = TaskScheduleCalculator.nextOccurrence(AgentScheduleRule(AgentScheduleKind.MONTHLY, start, localHour = 8, localMinute = 0, timeZoneId = "UTC", monthDay = 31), start)!!
        assertEquals("2026-02-28T08:00Z[UTC]", java.time.Instant.ofEpochMilli(next).atZone(zone).toString())
    }

    @Test fun switchingTimeTaskToEventDoesNotLeaveAResumableOldSchedule() = runBlocking {
        val repo = AutomationRepository(db)
        val id = repo.save(null, "时间任务", "整理资料", AutomationSpec(), null,
            AgentScheduleRule(AgentScheduleKind.DAILY, System.currentTimeMillis(), localHour = 8, localMinute = 0))
        assertEquals(1, db.agentTaskDao().schedulesNow().size)
        repo.save(db.agentTaskDao().definition(id), "事件任务", "整理资料", AutomationSpec(), trigger(), null)
        assertTrue(db.agentTaskDao().schedulesNow().isEmpty())
        repo.setEnabled(id, false); repo.setEnabled(id, true)
        assertTrue(db.agentTaskDao().schedulesNow().isEmpty())
    }

    @Test fun newEventsQueueWhileThePriorTaskRunsWithoutConcurrentExecutionOrDuplicates() = runBlocking {
        val id = create(trigger(AutomationTrigger.FILE_CREATED, "content://folder/tree/one").copy(cooldownSeconds = 60))
        val dao = db.agentTaskDao()
        val ready = AutomationState(true, true, true, 90)
        assertTrue(AutomationTriggers.fire(context, db.triggerDao().all().single(), "file-1", JSONObject(), ready, "file-1"))
        val now = System.currentTimeMillis()
        val first = dao.claimNextQueued("worker", now, now + 60_000)!!
        assertTrue(dao.markRunning(first.runId, "worker", "runtime-first", now, now + 60_000))
        val trigger = db.triggerDao().all().single().copy(lastFiredAt = now - 60_001)
        db.triggerDao().save(trigger)
        assertTrue(AutomationTriggers.fire(context, trigger, "file-2", JSONObject(), ready, "file-2"))
        assertEquals(2, dao.runsForDefinition(id).size)
        assertNull(dao.claimNextQueued("other-worker", now + 1, now + 60_000))
        assertTrue(dao.complete(first.runId, "worker", "result:first", now + 2))
        val second = dao.claimNextQueued("other-worker", System.currentTimeMillis() + 1, now + 60_000)!!
        assertNotEquals(first.runId, second.runId)
        assertFalse(AutomationTriggers.fire(context, trigger, "file-2", JSONObject(), ready, "file-2"))
        assertEquals(2, dao.runsForDefinition(id).size)
    }

    @Test fun eventWaitsInTheSameDurableRunBeforeTheRealRunnerCanEnterAnyModelOrTool() = runBlocking {
        val id = create(trigger(), AutomationSpec(condition = AutomationCondition(charging = true)))
        val dao = db.agentTaskDao()
        assertTrue(AutomationTriggers.fire(context, db.triggerDao().all().single(), "plug-event", JSONObject().put("source", "fixture"),
            AutomationState(false, true, true, 90), "plug-event"))
        val original = dao.runsForDefinition(id).single()
        val runner = DefaultAgentTaskRunner(context, stateProvider = { AutomationState(false, true, true, 90) })
        val tick = AgentTaskCoordinator(dao, runner, clock = { original.createdAt + 1 })
        tick.tick()
        val deferred = dao.run(original.id)!!
        assertEquals(AgentTaskState.QUEUED, deferred.state)
        assertEquals(1, deferred.attempt)
        assertEquals(1, dao.runsForDefinition(id).size)
        assertNull(dao.lease(original.id))
        assertTrue(dao.events(original.id).any { it.eventType == "WAITING_CONDITIONS" })
        var calls = 0
        AgentTaskCoordinator(dao, AgentTaskRunner { calls++; AgentTaskExecutionResult.Succeeded("report-result") },
            clock = { deferred.scheduledFor + 1 }).tick()
        assertEquals(1, calls)
        assertEquals(AgentTaskState.SUCCEEDED, dao.run(original.id)?.state)
        assertEquals(1, dao.runsForDefinition(id).size)
    }

    @Test fun repositoryCannotPersistBothATimeScheduleAndAnEventSource() = runBlocking {
        assertTrue(runCatching { AutomationRepository(db).save(null, "双重触发", "任务内容", AutomationSpec(), trigger(),
            AgentScheduleRule(AgentScheduleKind.DAILY, System.currentTimeMillis(), localHour = 8, localMinute = 0)) }.isFailure)
        assertTrue(db.agentTaskDao().schedulesNow().isEmpty())
        assertTrue(db.triggerDao().all().isEmpty())
    }
}
