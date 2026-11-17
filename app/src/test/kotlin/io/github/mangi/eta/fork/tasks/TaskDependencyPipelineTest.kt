package io.github.mangi.eta.fork.tasks

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TaskDependencyPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AgentTaskDatabase

    @Before fun setup() {
        AgentTaskDatabase.closeForTests()
        context.deleteDatabase("eta_task.db")
        db = AgentTaskDatabase.get(context)
    }

    @After fun close() = AgentTaskDatabase.closeForTests()

    private suspend fun task(trigger: AutomationTriggerEntity? = null): String = AutomationRepository(db)
        .save(null, "任务", "执行任务", AutomationSpec(), trigger, null)

    private suspend fun finish(sourceId: String, runId: String, at: Long) {
        db.agentTaskDao().insertRunIfAbsent(AgentTaskRunEntity(
            id = runId, definitionId = sourceId, scheduleId = null, scheduledFor = at, attempt = 1,
            idempotencyKey = "source:$runId", state = AgentTaskState.SUCCEEDED,
            createdAt = at, finishedAt = at, updatedAt = at,
        ))
    }

    private suspend fun poll() = AutomationTriggers.poll(context)

    @Test fun all_waitsUntilEachSourceCompletesAndConsumesOneFromEach() = runBlocking {
        val first = task()
        val second = task()
        val target = task(AutomationTriggerEntity(
            id = "", definitionId = "", type = AutomationTrigger.TASK_SUCCEEDED.name,
            source = TaskDependencyConfig(listOf(first, second), TaskDependencyConfig.Mode.ALL).encode(),
        ))
        val now = System.currentTimeMillis()
        finish(first, "first-run", now + 1_000)

        poll()
        assertTrue(db.agentTaskDao().runsForDefinition(target).isEmpty())

        finish(second, "second-run", now + 2_000)
        poll()
        assertEquals(1, db.agentTaskDao().runsForDefinition(target).size)
        val trigger = db.triggerDao().all().single()
        val sourceCursors = JSONObject(trigger.cursor).getJSONObject("sources")
        assertEquals("second-run", TaskCompletionCursor.parse(sourceCursors.getString(second))!!.runIds.single())

        poll()
        assertEquals(1, db.agentTaskDao().runsForDefinition(target).size)
    }

    @Test fun any_consumesEarliestThenNextSourceCompletionAfterCooldown() = runBlocking {
        val first = task()
        val second = task()
        val target = task(AutomationTriggerEntity(
            id = "", definitionId = "", type = AutomationTrigger.TASK_SUCCEEDED.name,
            source = TaskDependencyConfig(listOf(first, second), TaskDependencyConfig.Mode.ANY).encode(),
            cooldownSeconds = 60,
        ))
        val now = System.currentTimeMillis()
        finish(first, "earliest-run", now + 1_000)
        finish(second, "later-run", now + 2_000)

        poll()
        val runsAfterFirst = db.agentTaskDao().runsForDefinition(target)
        assertEquals(1, runsAfterFirst.size)
        val firstEvent = JSONObject(db.agentTaskDao().events(runsAfterFirst.single().id).single().payloadJson)
        assertEquals("earliest-run", firstEvent.getJSONObject("sourceRuns").getString(first))
        assertTrue(!firstEvent.getJSONObject("sourceRuns").has(second))

        val trigger = db.triggerDao().all().single()
        db.triggerDao().save(trigger.copy(lastFiredAt = System.currentTimeMillis() - 61_000))
        poll()

        assertEquals(2, db.agentTaskDao().runsForDefinition(target).size)
        val cursors = JSONObject(db.triggerDao().all().single().cursor).getJSONObject("sources")
        assertEquals("earliest-run", TaskCompletionCursor.parse(cursors.getString(first))!!.runIds.single())
        assertEquals("later-run", TaskCompletionCursor.parse(cursors.getString(second))!!.runIds.single())
        poll()
        assertEquals(2, db.agentTaskDao().runsForDefinition(target).size)
    }
}
