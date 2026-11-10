package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.UUID

/**
 * Coordinates durable schedule materialization and lease-fenced execution.
 *
 * The repository deliberately delegates the actual Agent Runtime call through [AgentTaskRunner].
 * A process that dies after a run starts is failed after lease expiry instead of retried, because a
 * retry cannot prove that the previous attempt produced no external side effects.
 */
internal class AgentTaskCoordinator(
    private val dao: AgentTaskDao,
    private val runner: AgentTaskRunner,
    private val ownerId: String = UUID.randomUUID().toString(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val leaseDurationMillis: Long = DEFAULT_LEASE_DURATION_MILLIS,
    private val runtimeCanceller: AgentTaskRuntimeCanceller = AgentTaskRuntimeCanceller { },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialRetryDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
) {
    init {
        require(maxAttempts > 0)
        require(initialRetryDelayMillis > 0)
    }

    private val executionMutex = Mutex()

    suspend fun tick(maxRuns: Int = DEFAULT_MAX_RUNS_PER_TICK): AgentTaskSchedulerTickSummary {
        require(maxRuns > 0)
        return executionMutex.withLock {
            val recovery = dao.recoverExpiredLeases(clock())
            val materialization = dao.materializeDueSchedules(
                now = clock(),
                runIdFactory = idFactory,
                attentionIdFactory = idFactory,
            )
            var executed = 0
            while (executed < maxRuns) {
                val now = clock()
                val claim = dao.claimNextQueued(
                    owner = ownerId,
                    now = now,
                    leaseExpiresAt = saturatingAdd(now, leaseDurationMillis),
                ) ?: break
                executeClaimed(claim)
                executed++
            }
            RuntimeDebugTrace.taskTick(
                executedRuns = executed,
                queuedRuns = materialization.queuedRuns,
            )
            AgentTaskSchedulerTickSummary(recovery, materialization, executed)
        }
    }

    suspend fun cancelRun(runId: String): Boolean {
        val runtimeRunId = dao.run(runId)?.localRunId
        if (!dao.cancel(runId, clock())) return false
        if (runtimeRunId.isNullOrBlank()) return true
        try {
            runtimeCanceller.cancel(runtimeRunId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable cancel fence is already committed. Runtime cancellation is best effort.
        }
        return true
    }

    /**
     * Resumes a run that requested user input or authorization. [continuationInput] is passed to the
     * Agent Runtime only and is never written into event payloads.
     */
    suspend fun resumeWaiting(runId: String, continuationInput: String): Boolean {
        val now = clock()
        val leaseExpiresAt = saturatingAdd(now, leaseDurationMillis)
        val runtimeRunId = idFactory()
        if (!dao.resumeWaiting(runId, ownerId, runtimeRunId, now, leaseExpiresAt)) return false
        return executeRunning(runId, runtimeRunId, continuationInput)
    }

    /** Runs an explicitly accepted missed schedule, or dismisses it without running. */
    suspend fun resolveMissedRun(runId: String, runNow: Boolean): Boolean {
        if (!runNow) return cancelRun(runId)
        val run = dao.run(runId) ?: return false
        if (run.state != AgentTaskState.WAITING_INPUT) return false
        return resumeWaiting(runId, continuationInput = "")
    }

    /** Persist acceptance of a missed run before WorkManager claims it. */
    suspend fun queueMissedRun(runId: String): Boolean = dao.queueMissedRun(runId, clock())

    private suspend fun executeClaimed(claim: AgentTaskClaim): Boolean {
        val now = clock()
        val runtimeRunId = idFactory()
        if (!dao.markRunning(
                runId = claim.runId,
                owner = ownerId,
                localRunId = runtimeRunId,
                now = now,
                leaseExpiresAt = saturatingAdd(now, leaseDurationMillis),
            )
        ) {
            dao.fail(
                runId = claim.runId,
                owner = ownerId,
                code = "CLAIM_LOST",
                message = "任务租约在开始执行前失效",
                now = clock(),
            )
            return false
        }
        return executeRunning(claim.runId, runtimeRunId, continuationInput = null)
    }

    private suspend fun executeRunning(
        runId: String,
        runtimeRunId: String,
        continuationInput: String?,
    ): Boolean {
        val run = dao.run(runId) ?: return false
        val definition = dao.definition(run.definitionId) ?: run {
            dao.fail(
                runId = runId,
                owner = ownerId,
                code = "DEFINITION_MISSING",
                message = "定时任务定义不存在",
                now = clock(),
            )
            return false
        }
        val request = AgentTaskExecutionRequest(
            taskRunId = run.id,
            runtimeRunId = runtimeRunId,
            definitionId = definition.id,
            kind = definition.runKind,
            targetId = definition.targetId,
            prompt = definition.promptTemplate,
            participantConfigJson = definition.participantConfigJson,
            policyJson = definition.policyJson,
            scheduledFor = run.scheduledFor,
            attempt = run.attempt,
            continuationInput = continuationInput,
        )

        val result = try {
            runWithLeaseHeartbeat(dao, runId, ownerId, clock, leaseDurationMillis) {
                runner.execute(request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentTaskExecutionResult.Failed(
                code = "RUNNER_EXCEPTION",
                message = "Agent runner failed (${error.javaClass.simpleName})",
            )
        }

        val now = clock()
        return when (result) {
            is AgentTaskExecutionResult.Deferred -> if (now - run.createdAt >= 86400000L) {
                dao.fail(runId, ownerId, "CONDITION_EXPIRED", "等待设备条件超过 24 小时，本次未执行", now)
            } else dao.defer(runId, ownerId, saturatingAdd(now, result.delayMillis.coerceIn(60000, 900000)), now)
            is AgentTaskExecutionResult.Succeeded -> dao.complete(
                runId = runId,
                owner = ownerId,
                resultRef = result.resultRef,
                now = now,
            )
            is AgentTaskExecutionResult.Failed -> {
                RuntimeDebugTrace.taskFailure(result.code)
                val failed = dao.fail(
                    runId = runId,
                    owner = ownerId,
                    code = result.code,
                    message = result.message,
                    now = now,
                )
                if (failed) {
                    scheduleRetryAfterFailure(run, result.retryable, now)
                }
                failed
            }
            is AgentTaskExecutionResult.InputRequired -> dao.waitForAttention(
                runId = runId,
                owner = ownerId,
                state = AgentTaskState.WAITING_INPUT,
                attentionKind = AgentTaskAttentionKind.INPUT_REQUIRED,
                attentionId = idFactory(),
                title = "任务需要输入",
                body = result.prompt,
                now = now,
            )
            is AgentTaskExecutionResult.AuthRequired -> dao.waitForAttention(
                runId = runId,
                owner = ownerId,
                state = AgentTaskState.WAITING_AUTH,
                attentionKind = AgentTaskAttentionKind.AUTH_REQUIRED,
                attentionId = idFactory(),
                title = "任务需要授权",
                body = result.reason,
                now = now,
            )
        }
    }

    private suspend fun scheduleRetryAfterFailure(
        failedRun: AgentTaskRunEntity,
        retryable: Boolean,
        now: Long,
    ) {
        if (!retryable || failedRun.scheduleId == null) return
        if (failedRun.attempt >= maxAttempts) {
            dao.pauseSchedulesAfterRetryExhaustion(
                definitionId = failedRun.definitionId,
                runId = failedRun.id,
                maxAttempts = maxAttempts,
                now = now,
            )
            return
        }
        val exponent = (failedRun.attempt - 1).coerceIn(0, MAX_RETRY_EXPONENT)
        val multiplier = 1L shl exponent
        val delayMillis = saturatingMultiply(initialRetryDelayMillis, multiplier)
        val retryAt = saturatingAdd(now, delayMillis)
        val nextAttempt = failedRun.attempt + 1
        dao.queueRetryIfScheduleActive(
            retry = failedRun.copy(
                id = idFactory(),
                scheduledFor = retryAt,
                attempt = nextAttempt,
                idempotencyKey = "${failedRun.idempotencyKey}:retry:$nextAttempt",
                state = AgentTaskState.QUEUED,
                localRunId = null,
                resultRef = null,
                errorCode = null,
                errorMessage = null,
                createdAt = now,
                startedAt = null,
                finishedAt = null,
                updatedAt = now,
            ),
            now = now,
        )
    }

    private suspend fun <T> runWithLeaseHeartbeat(
        dao: AgentTaskDao,
        runId: String,
        ownerId: String,
        clock: () -> Long,
        leaseDurationMillis: Long,
        block: suspend () -> T,
    ): T = coroutineScope {
        val heartbeatInterval = (leaseDurationMillis / 3L).coerceAtLeast(1L)
        val heartbeat = launch(Dispatchers.IO) {
            while (isActive) {
                delay(heartbeatInterval)
                val now = clock()
                dao.renewLease(
                    runId = runId,
                    owner = ownerId,
                    now = now,
                    leaseExpiresAt = saturatingAdd(now, leaseDurationMillis),
                )
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long = when {
        left <= 0L || right <= 0L -> 0L
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    companion object {
        const val DEFAULT_LEASE_DURATION_MILLIS: Long = 10L * 60L * 1_000L
        const val DEFAULT_MAX_RUNS_PER_TICK: Int = 1
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_INITIAL_RETRY_DELAY_MILLIS: Long = 30L * 1_000L
        private const val MAX_RETRY_EXPONENT = 10
    }
}
