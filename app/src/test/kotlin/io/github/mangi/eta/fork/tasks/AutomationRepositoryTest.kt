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
        assertFalse(AutomationTriggers.fire(context, t, "event-1", JSONObject(), AutomationState(false, true, true, 90), "event-1"))
        val pending = db.triggerDao().all().single()
        assertTrue(AutomationTriggers.fire(context, pending, "event-1", JSONObject(), AutomationState(true, true, true, 90), "event-1"))
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
        val coordinator = AgentTaskCoordinator(dao, AgentTaskRunner { AgentTaskExecutionResult.Deferred() }, clock = { 1100L })
        coordinator.tick()
        val run = dao.runsForDefinition(id).single()
        assertEquals(AgentTaskState.QUEUED, run.state); assertEquals(61100L, run.scheduledFor)
        assertNull(dao.lease(run.id)); assertEquals(1, run.attempt)
        assertTrue(dao.events(run.id).any { it.eventType == "WAITING_CONDITIONS" })
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
}
