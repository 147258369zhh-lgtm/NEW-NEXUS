package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
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
    fun expiredWorkflowResumesAtCursorButUnconfirmedStepFailsClosed() = runBlocking {
        insertTask("workflow-resume", now = 1_000, scheduledFor = 1_000)
        val resumable = requireNotNull(dao.claimNextQueued("worker", 1_000, 2_000))
        assertTrue(dao.markRunning(resumable.runId, "worker", "runtime-w1", 1_000, 2_000))
        assertTrue(
            dao.saveWorkflowCheckpoint(
                resumable.runId,
                "worker",
                WorkflowCheckpoint(nextStepIndex = 1, workflowStartedAt = 1_000),
                1_100,
            ),
        )
        val resumed = dao.recoverExpiredLeases(2_001)
        assertEquals(1, resumed.resumedWorkflowRuns)
        assertEquals(AgentTaskState.QUEUED, requireNotNull(dao.run(resumable.runId)).state)

        assertTrue(dao.cancel(resumable.runId, 2_002))
        insertTask("workflow-started", now = 3_000, scheduledFor = 3_000)
        val unresolved = requireNotNull(dao.claimNextQueued("worker", 3_000, 4_000))
        assertTrue(dao.markRunning(unresolved.runId, "worker", "runtime-w2", 3_000, 4_000))
        assertTrue(
            dao.saveWorkflowCheckpoint(
                unresolved.runId,
                "worker",
                WorkflowCheckpoint(stepStarted = true, workflowStartedAt = 3_000),
                3_100,
            ),
        )
        dao.recoverExpiredLeases(4_001)
        val failed = requireNotNull(dao.run(unresolved.runId))
        assertEquals(AgentTaskState.FAILED, failed.state)
        assertEquals("WORKFLOW_STEP_UNCONFIRMED", failed.errorCode)
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
    fun deferredWorkflowWaitUsesExactDeadlineAndResumesFromCheckpoint() = runBlocking {
        insertTask("persistent-wait", now = 1_000)
        dao.setSchedulePausedRow("persistent-wait-schedule", paused = true, nextRunAt = null, now = 1_000)
        var now = 1_000L
        var calls = 0
        val checkpoints = mutableListOf<WorkflowCheckpoint>()
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner { request ->
                checkpoints += request.workflowCheckpoint
                if (calls++ == 0) {
                    val waiting = request.workflowCheckpoint.copy(
                        nextStepIndex = 1,
                        waitUntil = 6_000L,
                        workflowStartedAt = 1_000L,
                    )
                    assertTrue(dao.saveWorkflowCheckpoint(request.taskRunId, request.executionOwner, waiting, now))
                    AgentTaskExecutionResult.Deferred(
                        delayMillis = waiting.waitUntil!! - now,
                        reason = "等待步骤",
                        wakeAtEpochMillis = waiting.waitUntil,
                    )
                } else {
                    AgentTaskExecutionResult.Succeeded("resumed")
                }
            },
            ownerId = "coordinator",
            clock = { now },
            idFactory = { "persistent-wait-id" },
        )

        assertEquals(1, coordinator.tick().executedRuns)
        val waiting = requireNotNull(dao.run("persistent-wait-run"))
        assertEquals(AgentTaskState.QUEUED, waiting.state)
        assertEquals(6_000L, waiting.scheduledFor)
        assertEquals(6_000L, WorkflowCheckpoint.parse(waiting.workflowCheckpointJson).waitUntil)

        now = 6_000L
        assertEquals(1, coordinator.tick().executedRuns)
        assertEquals(AgentTaskState.SUCCEEDED, requireNotNull(dao.run("persistent-wait-run")).state)
        assertEquals(2, checkpoints.size)
        assertEquals(0, checkpoints[0].nextStepIndex)
        assertEquals(1, checkpoints[1].nextStepIndex)
        assertEquals(6_000L, checkpoints[1].waitUntil)
        assertTrue(checkpoints[1].definitionFingerprint != null)
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

    @Test
    fun configuredConcurrencyCapRunsEligibleTasksInParallelAndLeavesTheRestQueued() = runBlocking {
        insertTask("parallel-a", now = 1_000, scheduledFor = 1_000)
        insertTask("parallel-b", now = 1_000, scheduledFor = 1_000)
        insertTask("parallel-c", now = 1_000, scheduledFor = 1_000)
        dao.insertRunIfAbsent(
            AgentTaskRunEntity(
                id = "parallel-a-second-run",
                definitionId = "parallel-a-definition",
                scheduleId = null,
                scheduledFor = 1_000,
                attempt = 1,
                idempotencyKey = "manual:parallel-a-second",
                state = AgentTaskState.QUEUED,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        listOf("parallel-a", "parallel-b", "parallel-c").forEach { id ->
            dao.setSchedulePausedRow("$id-schedule", paused = true, nextRunAt = null, now = 1_000)
        }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner {
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                delay(100)
                active.decrementAndGet()
                AgentTaskExecutionResult.Succeeded("parallel-result")
            },
            ownerId = "coordinator",
            clock = { 1_000 },
            maxConcurrentRuns = 3,
        )

        val summary = coordinator.tick(maxRuns = 4)

        assertEquals(3, summary.executedRuns)
        assertEquals(3, maxActive.get())
        assertEquals(AgentTaskState.SUCCEEDED, requireNotNull(dao.run("parallel-a-run")).state)
        assertEquals(AgentTaskState.SUCCEEDED, requireNotNull(dao.run("parallel-b-run")).state)
        assertEquals(AgentTaskState.SUCCEEDED, requireNotNull(dao.run("parallel-c-run")).state)
        assertEquals(AgentTaskState.QUEUED, requireNotNull(dao.run("parallel-a-second-run")).state)
    }

    @Test
    fun runDeadlineFailsClosedAndCancelsRuntimeWithoutQueueingARetry() = runBlocking {
        insertTask("deadline", now = 1_000)
        dao.setSchedulePausedRow("deadline-schedule", paused = true, nextRunAt = null, now = 1_000)
        var runtimeCancelled = false
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner {
                delay(5_000)
                AgentTaskExecutionResult.Succeeded("late-result")
            },
            ownerId = "coordinator",
            clock = { 1_000 },
            runtimeCanceller = AgentTaskRuntimeCanceller { runtimeCancelled = true },
            runTimeoutMillis = 100,
        )

        val summary = coordinator.tick()

        assertEquals(1, summary.executedRuns)
        val run = requireNotNull(dao.run("deadline-run"))
        assertEquals(AgentTaskState.FAILED, run.state)
        assertEquals("RUN_TIMEOUT", run.errorCode)
        assertTrue(runtimeCancelled)
        assertEquals(1, dao.runsForDefinition("deadline-definition").size)
    }

    @Test fun resultIsAcknowledgedOnlyAfterSuccessTransactionAndCompleteOutputIsPreserved() = runBlocking {
        insertTask("receipt", 1_000)
        dao.setSchedulePausedRow("receipt-schedule", true, null, 1_000)
        val output = "完整结果".repeat(5_000)
        val context = RuntimeEnvironment.getApplication()
        var acknowledgements = 0
        val runner = object : AgentTaskRunner {
            override suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
                AgentTaskResultStore(context).save(request.taskRunId,
                    io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunResult(request.runtimeRunId, true, output, reasoningContent = "reasoning"))
                return AgentTaskExecutionResult.Succeeded(request.runtimeRunId, output, request.runtimeRunId)
            }
            override suspend fun acknowledge(result: AgentTaskExecutionResult) {
                assertEquals(AgentTaskState.SUCCEEDED, dao.run("receipt-run")!!.state)
                assertEquals(output, AutomationRepository(database).savedOutput(context, "receipt-run"))
                assertEquals(12_000, org.json.JSONObject(dao.events("receipt-run").last().payloadJson).getString("output").length)
                acknowledgements++
            }
        }
        AgentTaskCoordinator(dao, runner, clock = { 1_000 }).tick()
        assertEquals(1, acknowledgements)
    }

    @Test fun expiredRunningRunUsesCompleteReceiptBeforeGenericLeaseFailure() = runBlocking {
        insertTask("receipt-recovery", 1_000)
        dao.setSchedulePausedRow("receipt-recovery-schedule", true, null, 1_000)
        val claim = requireNotNull(dao.claimNextQueued("old-owner", 1_000, 2_000))
        val runtimeRunId = "runtime-receipt-recovery-${System.nanoTime()}"
        assertTrue(dao.markRunning(claim.runId, "old-owner", runtimeRunId, 1_100, 2_000))

        val context = RuntimeEnvironment.getApplication()
        val output = "已完整返回的自动任务结果"
        val store = AgentTaskResultStore(context)
        store.save(claim.runId, io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunResult(
            runId = runtimeRunId,
            ok = true,
            content = output,
        ))
        var acknowledgements = 0
        val runner = object : AgentTaskRunner {
            override suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult =
                error("Recovered terminal receipt must not execute again")

            override suspend fun acknowledge(result: AgentTaskExecutionResult) {
                assertEquals(AgentTaskState.SUCCEEDED, dao.run(claim.runId)?.state)
                assertEquals(output, AutomationRepository(database).savedOutput(context, claim.runId))
                acknowledgements++
            }
        }

        val summary = AgentTaskCoordinator(
            dao = dao,
            runner = runner,
            clock = { 2_001 },
            resultStore = store,
        ).tick()

        assertEquals(1, summary.recovery.recoveredReceiptRuns)
        val recovered = requireNotNull(dao.run(claim.runId))
        assertEquals(AgentTaskState.SUCCEEDED, recovered.state)
        assertEquals(runtimeRunId, recovered.resultRef)
        assertNull(dao.lease(claim.runId))
        assertTrue(dao.events(claim.runId).any { it.eventType == "RECEIPT_RECOVERED_SUCCEEDED" })
        assertEquals(1, acknowledgements)
    }

    @Test fun transactionFailureAndExpiredLeaseCannotAcknowledgeOrComplete() = runBlocking {
        insertTask("uncommitted", 1_000)
        dao.setSchedulePausedRow("uncommitted-schedule", true, null, 1_000)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_success BEFORE INSERT ON agent_task_events
            WHEN NEW.event_type = 'SUCCEEDED' BEGIN SELECT RAISE(ABORT, 'fixture commit failure'); END
        """.trimIndent())
        var acknowledged = false
        val runner = object : AgentTaskRunner {
            override suspend fun execute(request: AgentTaskExecutionRequest) = AgentTaskExecutionResult.Succeeded("result", "output", "result")
            override suspend fun acknowledge(result: AgentTaskExecutionResult) { acknowledged = true }
        }
        try { AgentTaskCoordinator(dao, runner, clock = { 1_000 }).tick(); org.junit.Assert.fail("Commit must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertFalse(acknowledged)
        assertEquals(AgentTaskState.RUNNING, dao.run("uncommitted-run")!!.state)
        val expiry = dao.lease("uncommitted-run")!!.expiresAt
        assertFalse(dao.complete("uncommitted-run", dao.lease("uncommitted-run")!!.owner, "late", expiry))
        assertEquals(AgentTaskState.RUNNING, dao.run("uncommitted-run")!!.state)
    }

    @Test fun cancelStopsActiveOwnerAndDuplicateResumeCreatesNoLease() = runBlocking {
        insertTask("active-cancel", 1_000)
        dao.setSchedulePausedRow("active-cancel-schedule", true, null, 1_000)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        var acknowledged = false
        val coordinator = AgentTaskCoordinator(dao, object : AgentTaskRunner {
            override suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
                entered.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { stopped.complete(Unit) }
            }
            override suspend fun acknowledge(result: AgentTaskExecutionResult) { acknowledged = true }
        }, clock = { 1_000 })
        val tick = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch { coordinator.tick() }
        kotlinx.coroutines.withTimeout(3_000) { entered.await() }
        assertTrue(coordinator.cancelRun("active-cancel-run"))
        kotlinx.coroutines.withTimeout(3_000) { stopped.await(); tick.join() }
        assertFalse(acknowledged)
        assertFalse(dao.resumeWaiting("active-cancel-run", "new", "invalid", 1_000, 2_000))
        assertNull(dao.lease("active-cancel-run"))
        insertTask("resume-once", 1_000)
        val claim = dao.claimNextQueued("worker", 1_000, 2_000)!!
        assertTrue(dao.markRunning(claim.runId, "worker", "first", 1_000, 2_000))
        assertTrue(dao.waitForAttention(claim.runId, "worker", AgentTaskState.WAITING_INPUT, AgentTaskAttentionKind.INPUT_REQUIRED, "ask", "Input", "Question", 1_000))
        assertTrue(dao.resumeWaiting(claim.runId, "new", "resumed", 1_100, 3_000))
        assertFalse(dao.resumeWaiting(claim.runId, "other", "duplicate", 1_100, 3_000))
        assertEquals("new", dao.lease(claim.runId)!!.owner)
    }

    @Test fun losingLeaseStopsExecutionAndEventsRemainValidJson() = runBlocking {
        insertTask("lease-lost", 1_000)
        dao.setSchedulePausedRow("lease-lost-schedule", true, null, 1_000)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        var acknowledged = false
        val coordinator = AgentTaskCoordinator(dao, object : AgentTaskRunner {
            override suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
                entered.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { stopped.complete(Unit) }
            }
            override suspend fun acknowledge(result: AgentTaskExecutionResult) { acknowledged = true }
        }, ownerId = "owner\"\n", clock = { 1_000 }, leaseDurationMillis = 90)
        val tick = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch { coordinator.tick() }
        kotlinx.coroutines.withTimeout(3_000) { entered.await() }
        dao.deleteLease("lease-lost-run")
        kotlinx.coroutines.withTimeout(3_000) { stopped.await(); tick.join() }
        assertFalse(acknowledged)
        val events = dao.events("lease-lost-run").map { org.json.JSONObject(it.payloadJson) }
        assertEquals("owner\"\n", events.first().getString("owner"))
    }

    @Test fun blockedDefinitionDoesNotWakeQueuedWorkUntilLeaseExpires() = runBlocking {
        insertTask("blocked", 1_000)
        val claim = dao.claimNextQueued("worker", 1_000, 5_000)!!
        assertTrue(dao.markRunning(claim.runId, "worker", "running", 1_000, 5_000))
        dao.insertRunIfAbsent(dao.run(claim.runId)!!.copy(id = "queued-followup", localRunId = null,
            idempotencyKey = "followup", state = AgentTaskState.QUEUED))
        assertNull(dao.earliestQueuedAt())
        assertEquals(5_000L, dao.earliestLeaseExpiry())
        assertFalse(dao.defer(claim.runId, "worker", 6_000, 5_000, "expired"))
        assertTrue(dao.defer(claim.runId, "worker", 6_000, 1_100, "等\"待\n设备\u0001"))
        assertEquals("等\"待\n设备\u0001", org.json.JSONObject(dao.events(claim.runId).last().payloadJson).getString("reason"))
        assertEquals(1_000L, dao.earliestQueuedAt())
    }

    @Test fun allDeleteEntryPointsRejectActiveTaskUntilItIsDurablyCanceled() = runBlocking {
        insertTask("delete-owner", 1_000)
        val repository = AgentTaskRepository(dao)
        try { repository.delete("delete-owner-definition"); org.junit.Assert.fail("Active task deleted") }
        catch (_: IllegalArgumentException) { }
        assertNotNull(dao.definition("delete-owner-definition"))
        try { AutomationRepository(database).delete("delete-owner-definition"); org.junit.Assert.fail("Active task deleted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(dao.cancel("delete-owner-run", 1_001))
        repository.delete("delete-owner-definition")
        assertNull(dao.definition("delete-owner-definition"))
    }

    @Test fun attentionAndLatestQueriesIncludeRunsOutsideRecentHistory() = runBlocking {
        insertTask("old", 1_000)
        val old = requireNotNull(dao.run("old-run"))
        dao.insertAttention(AgentTaskAttentionEntity("attention-old", old.id, AgentTaskAttentionKind.INPUT_REQUIRED,
            AgentTaskAttentionStatus.OPEN, "Input", "Required", 1_000))
        repeat(105) { index ->
            dao.insertRunIfAbsent(old.copy(id = "new-$index", idempotencyKey = "new-$index",
                scheduledFor = 2_000L + index, createdAt = 2_000L + index, updatedAt = 2_000L + index))
        }
        assertFalse(dao.observeRecentRuns().first().any { it.id == old.id })
        assertEquals(old.id, dao.observeAttentionRuns().first().single().id)
        assertEquals("new-104", dao.observeLatestRuns().first().single().id)
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
