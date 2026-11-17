package io.github.mangi.eta.fork.tasks

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class AutomationMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { AgentTaskDatabase.closeForTests(); context.deleteDatabase("eta_task.db") }
    @After fun close() { AgentTaskDatabase.closeForTests() }
    @Test fun existingTaskScheduleAndQueuedRunSurviveVersionOneUpgrade() = runBlocking {
        val original = AgentTaskDatabase.get(context)
        val ids = AgentTaskRepository(original.agentTaskDao(), clock = { 1000 }).create(AgentTaskDraft("已有计划", AgentTaskRunKind.CHAT, "default", "读取项目资料", schedule = AgentScheduleRule(AgentScheduleKind.INTERVAL, 1000, intervalMinutes = 60), missedRunPolicy = MissedRunPolicy.RUN_ONCE))
        val run = AgentTaskRepository(original.agentTaskDao(), clock = { 1000 }).runNow(ids.definitionId)!!
        // Reconstruct the exact v1 layout: undo every v2, v3 and v4 addition while keeping the data.
        original.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE automation_triggers")
            execSQL("ALTER TABLE agent_task_schedules DROP COLUMN month_day")
            execSQL("DROP INDEX index_agent_task_runs_a2a_agent_id_a2a_task_id")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_message_seq")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_message_id")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_context_id")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_task_id")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_agent_id")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_review_decision")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN a2a_deadline_at")
            execSQL("ALTER TABLE agent_task_runs DROP COLUMN workflow_checkpoint_json")
            execSQL("DROP TABLE room_master_table")
            execSQL("PRAGMA user_version = 1")
        }
        AgentTaskDatabase.closeForTests()
        val upgraded = AgentTaskDatabase.get(context)
        assertEquals("已有计划", upgraded.agentTaskDao().definition(ids.definitionId)!!.title)
        assertEquals(60L, upgraded.agentTaskDao().schedule(ids.scheduleId)!!.intervalMinutes)
        assertEquals(AgentTaskState.QUEUED, upgraded.agentTaskDao().run(run)!!.state)
        val migratedRun = upgraded.agentTaskDao().run(run)!!
        assertNull(migratedRun.a2aAgentId)
        assertNull(migratedRun.a2aTaskId)
        assertEquals(0, migratedRun.a2aMessageSeq)
        assertNull(migratedRun.a2aDeadlineAt)
        assertNull(migratedRun.a2aReviewDecision)
        assertTrue(upgraded.triggerDao().all().isEmpty())
        assertEquals(5, upgraded.openHelper.readableDatabase.version)
    }
}
