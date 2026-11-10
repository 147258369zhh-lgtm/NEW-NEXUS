package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskDatabaseTest {
    private lateinit var database: AgentTaskDatabase
    private lateinit var dao: AgentTaskDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, AgentTaskDatabase::class.java).build()
        dao = database.agentTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun claimedRunCompletesOnlyWhileLeaseIsStillOwned() = runBlocking {
        insertTask("task", now = 1_000)

        val claim = requireNotNull(dao.claimNextQueued("worker", 1_000, 2_000))
        assertTrue(dao.markRunning(claim.runId, "worker", "runtime-1", 1_100, 2_100))
        assertTrue(dao.complete(claim.runId, "worker", "runtime:42", 1_200))

        val run = requireNotNull(dao.run(claim.runId))
        assertEquals(AgentTaskState.SUCCEEDED, run.state)
        assertEquals("runtime:42", run.resultRef)
        assertNull(dao.lease(claim.runId))
        assertEquals(listOf("CLAIMED", "STARTED", "SUCCEEDED"), dao.events(claim.runId).map { it.eventType })
    }

    @Test
    fun lateCompletionCannotOverwriteCancellation() = runBlocking {
        insertTask("task", now = 1_000)

        val claim = requireNotNull(dao.claimNextQueued("worker", 1_000, 60_000))
        assertTrue(dao.markRunning(claim.runId, "worker", "runtime-2", 1_100, 60_000))
        assertTrue(dao.cancel(claim.runId, 1_200))
        assertFalse(dao.complete(claim.runId, "worker", "late-result", 1_300))

        val run = requireNotNull(dao.run(claim.runId))
        assertEquals(AgentTaskState.CANCELED, run.state)
        assertNull(run.resultRef)
    }

    @Test
    fun expiredLeasedRunIsSafeToRequeueButExpiredRunningRunFailsWithoutRetry() = runBlocking {
        insertTask("leased", now = 1_000, scheduledFor = 1_000)
        val leased = requireNotNull(dao.claimNextQueued("worker", 1_000, 2_000))
        val leasedRecovery = dao.recoverExpiredLeases(2_001)

        assertEquals(1, leasedRecovery.requeuedLeasedRuns)
        assertEquals(AgentTaskState.QUEUED, requireNotNull(dao.run(leased.runId)).state)
        assertTrue(dao.cancel(leased.runId, 2_100))

        insertTask("running", now = 3_000, scheduledFor = 3_000)
        val running = requireNotNull(dao.claimNextQueued("worker", 3_000, 4_000))
        assertTrue(dao.markRunning(running.runId, "worker", "runtime-3", 3_100, 4_000))
        val runningRecovery = dao.recoverExpiredLeases(4_001)

        assertEquals(1, runningRecovery.failedRunningRuns)
        val failed = requireNotNull(dao.run(running.runId))
        assertEquals(AgentTaskState.FAILED, failed.state)
        assertEquals("LEASE_EXPIRED", failed.errorCode)
        assertTrue(dao.events(running.runId).any { it.eventType == "LEASE_EXPIRED_FAILED" })
    }

    @Test
    fun missedRunPoliciesMaterializeDurableRunsWithIdempotency() = runBlocking {
        insertTask("run-once", now = 1_000, scheduledFor = 1_000, policy = MissedRunPolicy.RUN_ONCE)
        insertTask("skip", now = 1_000, scheduledFor = 2_000, policy = MissedRunPolicy.SKIP)
        insertTask("ask", now = 1_000, scheduledFor = 3_000, policy = MissedRunPolicy.ASK_USER)
        var sequence = 0
        val idFactory = { "generated-${sequence++}" }

        val summary = dao.materializeDueSchedules(10_000, runIdFactory = idFactory, attentionIdFactory = idFactory)

        assertEquals(1, summary.queuedRuns)
        assertEquals(1, summary.attentionRuns)
        assertEquals(AgentTaskState.QUEUED, requireNotNull(dao.run("generated-0")).state)
        assertEquals(AgentTaskState.WAITING_INPUT, requireNotNull(dao.run("generated-1")).state)
        assertEquals(1, dao.openAttention().size)
        assertNull(requireNotNull(dao.schedule("run-once-schedule")).nextRunAt)
        assertNull(requireNotNull(dao.schedule("skip-schedule")).nextRunAt)
        assertNull(requireNotNull(dao.schedule("ask-schedule")).nextRunAt)
    }

    @Test
    fun catchUpWatermarkDoesNotReplayOlderRecurringSlots() = runBlocking {
        val first = 1_000L
        val next = first + 60_000L
        dao.insertScheduledTask(
            definition = AgentTaskDefinitionEntity(
                id = "daily-definition",
                title = "daily",
                runKind = AgentTaskRunKind.CHAT,
                targetId = "conversation-1",
                promptTemplate = "Daily task",
                createdAt = first,
                updatedAt = first,
            ),
            schedule = AgentTaskScheduleEntity(
                id = "daily-schedule",
                definitionId = "daily-definition",
                kind = AgentScheduleKind.INTERVAL,
                startAt = first,
                endAt = null,
                intervalMinutes = 1,
                localHour = null,
                localMinute = null,
                weekdaysMask = null,
                timeZoneId = "UTC",
                missedRunPolicy = MissedRunPolicy.RUN_ONCE,
                lastScheduledFor = null,
                nextRunAt = first,
                paused = false,
                revision = 0,
                createdAt = first,
                updatedAt = first,
            ),
        )
        var sequence = 0
        val idFactory = { "catch-up-${sequence++}" }

        dao.materializeDueSchedules(first + 30_000L, runIdFactory = idFactory, attentionIdFactory = idFactory)
        val advanced = requireNotNull(dao.schedule("daily-schedule"))
        assertEquals(next, advanced.nextRunAt)
        assertEquals(first + 30_000L, advanced.lastScheduledFor)

        dao.materializeDueSchedules(next, runIdFactory = idFactory, attentionIdFactory = idFactory)
        val secondRun = requireNotNull(dao.run("catch-up-1"))
        assertEquals(next, secondRun.scheduledFor)
    }

    @Test
    fun coordinatorPassesUserContinuationAndCompletesResumedRun() = runBlocking {
        insertTask("coordinated", now = 1_000)
        dao.setSchedulePausedRow("coordinated-schedule", paused = true, nextRunAt = null, now = 1_000)
        val requests = mutableListOf<AgentTaskExecutionRequest>()
        var runnerCall = 0
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner { request ->
                requests += request
                if (runnerCall++ == 0) {
                    AgentTaskExecutionResult.InputRequired("Confirm the target")
                } else {
                    AgentTaskExecutionResult.Succeeded("runtime:44")
                }
            },
            ownerId = "coordinator",
            clock = { 1_000 },
            idFactory = { "attention-id" },
        )

        val tick = coordinator.tick()
        assertEquals(1, tick.executedRuns)
        assertEquals(AgentTaskState.WAITING_INPUT, requireNotNull(dao.run("coordinated-run")).state)
        assertEquals(1, dao.openAttention().size)

        assertTrue(coordinator.resumeWaiting("coordinated-run", "Use the current conversation"))
        assertEquals(2, requests.size)
        assertEquals("Use the current conversation", requests[1].continuationInput)
        assertEquals(AgentTaskState.SUCCEEDED, requireNotNull(dao.run("coordinated-run")).state)
    }

    @Test
    fun retryableFailuresBackOffAndPauseTheScheduleAfterMaxAttempts() = runBlocking {
        insertTask("retrying", now = 1_000, scheduledFor = 1_000)
        dao.setSchedulePausedRow(
            scheduleId = "retrying-schedule",
            paused = false,
            nextRunAt = null,
            now = 1_000,
        )
        var now = 1_000L
        var ids = 0
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner {
                AgentTaskExecutionResult.Failed(
                    code = "TRANSIENT_NETWORK",
                    message = "network unavailable",
                    retryable = true,
                )
            },
            ownerId = "coordinator",
            clock = { now },
            idFactory = { "generated-${ids++}" },
            maxAttempts = 3,
            initialRetryDelayMillis = 10_000,
        )

        repeat(3) { index ->
            coordinator.tick()
            if (index == 0) now = 11_000
            if (index == 1) now = 31_000
        }

        val runs = dao.runsForDefinition("retrying-definition").sortedBy { it.attempt }
        assertEquals(listOf(1, 2, 3), runs.map { it.attempt })
        assertEquals(listOf(1_000L, 11_000L, 31_000L), runs.map { it.scheduledFor })
        assertTrue(runs.all { it.state == AgentTaskState.FAILED })
        assertEquals(2, runs.count { run -> dao.events(run.id).any { it.eventType == "RETRY_QUEUED" } })
        assertTrue(runs.last().let { run ->
            dao.events(run.id).any { it.eventType == "SCHEDULE_PAUSED_AFTER_RETRIES" }
        })
        assertTrue(requireNotNull(dao.schedule("retrying-schedule")).paused)
    }

    private suspend fun insertTask(
        id: String,
        now: Long,
        scheduledFor: Long = now,
        policy: MissedRunPolicy = MissedRunPolicy.RUN_ONCE,
    ) {
        dao.insertScheduledTask(
            definition = AgentTaskDefinitionEntity(
                id = "$id-definition",
                title = id,
                runKind = AgentTaskRunKind.CHAT,
                targetId = "conversation-1",
                promptTemplate = "Do the task",
                createdAt = now,
                updatedAt = now,
            ),
            schedule = AgentTaskScheduleEntity(
                id = "$id-schedule",
                definitionId = "$id-definition",
                kind = AgentScheduleKind.ONCE,
                startAt = scheduledFor,
                endAt = null,
                intervalMinutes = null,
                localHour = null,
                localMinute = null,
                weekdaysMask = null,
                timeZoneId = "UTC",
                missedRunPolicy = policy,
                lastScheduledFor = null,
                nextRunAt = scheduledFor,
                paused = false,
                revision = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (id != "run-once" && id != "skip" && id != "ask") {
            dao.insertRunIfAbsent(
                AgentTaskRunEntity(
                    id = "$id-run",
                    definitionId = "$id-definition",
                    scheduleId = "$id-schedule",
                    scheduledFor = scheduledFor,
                    attempt = 1,
                    idempotencyKey = "manual:$id",
                    state = AgentTaskState.QUEUED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}
