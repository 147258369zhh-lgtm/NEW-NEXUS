package io.github.mangi.eta.fork.tasks

import android.app.Application
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutomationCompletionAndLocationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AgentTaskDatabase
    private val ready = AutomationState(true, true, true, 80)

    @Before fun setup() {
        AgentTaskDatabase.closeForTests()
        context.deleteDatabase("eta_task.db")
        db = AgentTaskDatabase.get(context)
    }

    @After fun close() = AgentTaskDatabase.closeForTests()

    private suspend fun task(trigger: AutomationTriggerEntity? = null): String = AutomationRepository(db)
        .save(null, "任务", "整理并生成报告", AutomationSpec(), trigger, null)

    private suspend fun finished(source: String, id: String, at: Long, state: AgentTaskState = AgentTaskState.SUCCEEDED) {
        db.agentTaskDao().insertRunIfAbsent(AgentTaskRunEntity(id, source, null, at, 1, "source:$id", state,
            createdAt = at, finishedAt = at, updatedAt = at))
    }

    private suspend fun poll() = AutomationTriggers.pollTaskCompletion(context, db.triggerDao().all().single(), ready)

    @Test fun removedHistoryNeverReplaysAndEqualTimestampCompletionsAreNotSkipped() = runBlocking {
        val source = task()
        val time = System.currentTimeMillis() - 10_000
        finished(source, "old", time)
        finished(source, "z-first", time + 1)
        val target = task(AutomationTriggerEntity("", "", AutomationTrigger.TASK_SUCCEEDED.name, source))
        db.openHelper.writableDatabase.execSQL("DELETE FROM agent_task_runs WHERE id = ?", arrayOf("z-first"))
        poll()
        assertTrue(db.agentTaskDao().runsForDefinition(target).isEmpty())
        finished(source, "a-later-same-time", time + 1)
        poll()
        val run = db.agentTaskDao().runsForDefinition(target).single()
        val event = JSONObject(db.agentTaskDao().events(run.id).single().payloadJson)
        assertEquals("a-later-same-time", event.getString("sourceRun"))
        poll()
        assertEquals(1, db.agentTaskDao().runsForDefinition(target).size)
    }

    @Test fun missingLegacyCursorEstablishesBaselineThenOnlyNewCompletionTriggers() = runBlocking {
        val source = task()
        val time = System.currentTimeMillis() - 10_000
        finished(source, "surviving-history", time)
        val target = task(AutomationTriggerEntity("", "", AutomationTrigger.TASK_FAILED.name, source))
        val original = db.triggerDao().all().single()
        db.triggerDao().save(original.copy(type = AutomationTrigger.TASK_SUCCEEDED.name, cursor = "deleted-old-run"))
        poll()
        assertTrue(db.agentTaskDao().runsForDefinition(target).isEmpty())
        assertNotNull(TaskCompletionCursor.parse(db.triggerDao().all().single().cursor))
        finished(source, "new", time + 1)
        poll()
        assertEquals(1, db.agentTaskDao().runsForDefinition(target).size)
    }

    @Test fun legacyExistingCursorMigratesWithoutLosingPendingFailures() = runBlocking {
        val source = task()
        val time = System.currentTimeMillis() - 10_000
        finished(source, "failure-a", time, AgentTaskState.FAILED)
        val target = task(AutomationTriggerEntity("", "", AutomationTrigger.TASK_FAILED.name, source))
        db.triggerDao().save(db.triggerDao().all().single().copy(cursor = "failure-a"))
        finished(source, "failure-b", time + 1, AgentTaskState.FAILED)
        finished(source, "success-c", time + 2)
        poll()
        assertEquals(1, db.agentTaskDao().runsForDefinition(target).size)
        assertTrue(TaskCompletionCursor.parse(db.triggerDao().all().single().cursor)!!.runIds.contains("failure-b"))
    }

    private fun location(latitude: Double = 0.0, accuracyMeters: Float = 10f): Location = Location("gps").apply {
        this.latitude = latitude
        longitude = 0.0
        accuracy = accuracyMeters
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test fun staleFutureOrUnmeasuredLocationsCannotTrigger() {
        val sample = location()
        val now = 1_000_000L
        sample.elapsedRealtimeNanos = now * 1_000_000
        assertTrue(AutomationLocationPolicy.isRecent(sample, elapsedNanos = (now + 119_000) * 1_000_000))
        assertFalse(AutomationLocationPolicy.isRecent(sample, elapsedNanos = (now + 121_000) * 1_000_000))
        assertFalse(AutomationLocationPolicy.isRecent(sample, elapsedNanos = (now - 1) * 1_000_000))
        sample.removeAccuracy()
        assertFalse(AutomationLocationPolicy.isRecent(sample, elapsedNanos = now * 1_000_000))
    }

    @Test fun uncertainRegionBoundaryKeepsPriorSideUntilPositionIsClear() {
        val area = LocationAutomationConfig(0.0, 0.0, 100)
        assertEquals(true, AutomationLocationPolicy.isInside(location(), area))
        assertNull(AutomationLocationPolicy.isInside(location(0.00090, 30f), area))
        assertEquals(false, AutomationLocationPolicy.isInside(location(0.002, 10f), area))
    }
}
