package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val remoteCanceller: AgentTaskRemoteCanceller = AgentTaskRemoteCanceller { _, _ -> },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialRetryDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    private val maxConcurrentRuns: Int = DEFAULT_MAX_CONCURRENT_RUNS,
    private val runTimeoutMillis: Long = DEFAULT_RUN_TIMEOUT_MILLIS,
    private val resultStore: AgentTaskResultStore? = null,
) {
    init {
        require(maxAttempts > 0)
        require(initialRetryDelayMillis > 0)
        require(maxConcurrentRuns > 0)
        require(runTimeoutMillis > 0)
    }

    private val executionMutex = Mutex()

    suspend fun tick(maxRuns: Int = DEFAULT_MAX_RUNS_PER_TICK): AgentTaskSchedulerTickSummary {
        require(maxRuns > 0)
        return executionMutex.withLock {
            dao.expireA2AWaits(clock())
            val recoveryNow = clock()
            var recoveredReceipts = 0
            resultStore?.let { store ->
                dao.expiredLeases(recoveryNow).forEach { expired ->
                    if (expired.state != AgentTaskState.RUNNING || expired.workflowCheckpointJson.trim() != "{}" ||
                        expired.localRunId.isNullOrBlank() || !expired.a2aTaskId.isNullOrBlank() ||
                        !expired.a2aMessageId.isNullOrBlank()
                    ) return@forEach
                    val receipt = runCatching {
                        store.read(checkNotNull(expired.localRunId), taskRunId = expired.runId)
                    }.getOrNull() ?: return@forEach
                    val errorCode = receipt.failureCode?.takeIf(String::isNotBlank)
                        ?: "RUNTIME_FAILED_RECOVERED"
                    val settled = dao.recoverReceipt(
                        runId = expired.runId,
                        ok = receipt.ok,
                        resultRef = receipt.runId,
                        output = receipt.content,
                        errorCode = errorCode,
                        errorMessage = receipt.error.orEmpty().ifBlank { "Agent Runtime returned no result" },
                        now = recoveryNow,
                    )
                    if (settled) {
                        recoveredReceipts++
                        val outcome = if (receipt.ok) {
                            AgentTaskExecutionResult.Succeeded(receipt.runId, receipt.content, receipt.runId)
                        } else {
                            AgentTaskExecutionResult.Failed(
                                code = errorCode,
                                message = receipt.error.orEmpty().ifBlank { "Agent Runtime returned no result" },
                                runtimeResultId = receipt.runId,
                            )
                        }
                        try {
                            runner.acknowledge(outcome)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The terminal task remains committed and the receipt remains available.
                        }
                    }
                }
            }
            val recovery = dao.recoverExpiredLeases(recoveryNow)
                .copy(recoveredReceiptRuns = recoveredReceipts)
            val materialization = dao.materializeDueSchedules(
                now = clock(),
                runIdFactory = idFactory,
                attentionIdFactory = idFactory,
            )
            var executed = 0
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            supervisorScope {
                val jobs = mutableListOf<Job>()
                while (executed < maxRuns) {
                    val now = clock()
                    val claim = dao.claimNextQueued(
                        owner = ownerId,
                        now = now,
                        leaseExpiresAt = saturatingAdd(now, leaseDurationMillis),
                        maxConcurrentRuns = maxConcurrentRuns,
                    ) ?: break
                    jobs += launch(Dispatchers.IO) {
                        try {
                            executeClaimed(claim)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                    executed++
                }
                jobs.forEach { it.join() }
            }
            failures.firstOrNull()?.let { throw it }
            RuntimeDebugTrace.taskTick(
                executedRuns = executed,
                queuedRuns = materialization.queuedRuns,
            )
            AgentTaskSchedulerTickSummary(recovery, materialization, executed)
        }
    }

    suspend fun cancelRun(runId: String): Boolean {
        val run = dao.run(runId)
        if (run?.state == AgentTaskState.A2A_REVIEW) return dao.cancelA2AReview(runId, clock())
        val runtimeRunId = run?.localRunId
        val binding = run?.a2aBinding()
        if (!dao.cancel(runId, clock())) return false
        activeExecutions[runId]?.cancel(CancellationException("任务已取消"))
        if (!runtimeRunId.isNullOrBlank()) {
            try {
                runtimeCanceller.cancel(runtimeRunId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The durable cancel fence is already committed. Runtime cancellation is best effort.
            }
        }
        val agentId = binding?.agentId
        val remoteTaskId = binding?.taskId
        if (agentId != null && remoteTaskId != null) {
            try {
                remoteCanceller.cancel(agentId, remoteTaskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Remote cancellation is best effort; the local run stays CANCELED either way.
            }
        }
        return true
    }

    suspend fun recordA2AReviewDecision(runId: String, decision: String): Boolean =
        if (decision == "CANCELED") dao.cancelA2AReview(runId, clock()) else dao.recordA2AReviewDecision(runId, decision, clock())

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
        val checkpoint = runCatching {
            WorkflowCheckpoint.parse(run.workflowCheckpointJson)
        }.getOrElse {
            dao.fail(
                runId = runId,
                owner = ownerId,
                code = "WORKFLOW_CHECKPOINT_INVALID",
                message = "工作流断点损坏，未自动重放",
                now = clock(),
            )
            return false
        }
        val definitionFingerprint = definition.workflowFingerprint()
        if (checkpoint.definitionFingerprint == null && !checkpoint.isPristine) {
            dao.fail(
                runId = runId,
                owner = ownerId,
                code = "WORKFLOW_CHECKPOINT_UNBOUND",
                message = "旧断点缺少定义绑定，未自动继续",
                now = clock(),
            )
            return false
        }
        if (!checkpoint.matchesDefinition(definitionFingerprint)) {
            dao.fail(
                runId = runId,
                owner = ownerId,
                code = "WORKFLOW_DEFINITION_CHANGED",
                message = "工作流定义已变更，旧断点不继续执行",
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
            executionOwner = ownerId,
            runStartedAt = run.startedAt,
            workflowCheckpoint = checkpoint.copy(definitionFingerprint = definitionFingerprint),
        )

        val completed = try {
            withTimeoutOrNull(runTimeoutMillis) {
                runWithLeaseHeartbeat(dao, runId, ownerId, clock, leaseDurationMillis) {
                    val job = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                    check(activeExecutions.putIfAbsent(runId, job) == null) { "任务已有执行所有者" }
                    try {
                        if (dao.run(runId)?.state != AgentTaskState.RUNNING) throw CancellationException("任务已停止")
                        runner.execute(request)
                    } finally { activeExecutions.remove(runId, job) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentTaskExecutionResult.Failed(
                code = "RUNNER_EXCEPTION",
                message = "Agent runner failed (${error.javaClass.simpleName})",
            )
        }
        val result = completed ?: run {
            cancelTimedOutRuntime(runtimeRunId)
            AgentTaskExecutionResult.Failed(
                code = "RUN_TIMEOUT",
                message = "本次运行超过 ${runTimeoutMillis / 1000L} 秒，已停止并取消 Runtime；未自动重放",
            )
        }

        val now = clock()
        val committed = when (result) {
            is AgentTaskExecutionResult.Deferred -> if (now - run.createdAt >= 86400000L) {
                dao.fail(runId, ownerId, "CONDITION_EXPIRED", "等待设备条件超过 24 小时，本次未执行", now)
            } else {
                val ceiling = saturatingAdd(now, MAX_DEFERRED_DELAY_MILLIS)
                val until = result.wakeAtEpochMillis?.let { deadline ->
                    val earliest = if (now == Long.MAX_VALUE) now else now + 1L
                    deadline.coerceIn(earliest, maxOf(earliest, ceiling))
                }
                    ?: saturatingAdd(
                        now,
                        result.delayMillis.coerceIn(MIN_CONDITION_POLL_MILLIS, MAX_DEFERRED_DELAY_MILLIS),
                    )
                dao.defer(runId, ownerId, until, now, result.reason)
            }
            is AgentTaskExecutionResult.WaitingRemote -> {
                val currentRun = dao.run(runId) ?: return false
                if (currentRun.state != AgentTaskState.RUNNING) return false
                if (currentRun.a2aDeadlineAt != null && now >= currentRun.a2aDeadlineAt) {
                    dao.markA2AReview(runId, "A2A_WAIT_EXPIRED", "等待 A2A 结果已超时，请核对远端任务", now)
                } else dao.waitForRemote(
                    runId = runId,
                    owner = ownerId,
                    nextPollAt = saturatingAdd(
                        now,
                        result.delayMillis.coerceIn(MIN_REMOTE_POLL_DELAY_MILLIS, MAX_REMOTE_POLL_DELAY_MILLIS),
                    ),
                    status = result.status,
                    now = now,
                )
            }
            is AgentTaskExecutionResult.Succeeded -> dao.complete(
                runId = runId,
                owner = ownerId,
                resultRef = result.resultRef,
                now = now,
                output = result.output,
            )
            is AgentTaskExecutionResult.Failed -> {
                RuntimeDebugTrace.taskFailure(result.code)
                if (result.code == "A2A_DISPATCH_UNKNOWN") {
                    return dao.markA2AReview(runId, result.code, result.message, now)
                }
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
        if (committed) {
            try {
                runner.acknowledge(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the outbox and task-owned receipt when delivery acknowledgement fails.
            }
        }
        return committed
    }

    private suspend fun scheduleRetryAfterFailure(
        failedRun: AgentTaskRunEntity,
        retryable: Boolean,
        now: Long,
    ) {
        if (!retryable || failedRun.scheduleId == null) return
        // A workflow retries inside its own step bounds. Forking a new run from a half-finished
        // cursor could replay an unconfirmed step, so this path stays closed.
        if (failedRun.workflowCheckpointJson.isNotBlank() && failedRun.workflowCheckpointJson.trim() != "{}") return
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
        val execution = coroutineContext[Job]!!
        val heartbeatInterval = (leaseDurationMillis / 3L).coerceAtLeast(1L)
        val heartbeat = launch(Dispatchers.IO) {
            while (isActive) {
                delay(heartbeatInterval)
                val now = clock()
                val renewed = dao.renewLease(
                    runId = runId,
                    owner = ownerId,
                    now = now,
                    leaseExpiresAt = saturatingAdd(now, leaseDurationMillis),
                )
                if (!renewed) {
                    execution.cancel(CancellationException("任务执行租约已失效"))
                    return@launch
                }
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    /** A timed-out run is failed closed; the Runtime cancel is best effort after the durable fence. */
    private suspend fun cancelTimedOutRuntime(runtimeRunId: String) {
        try {
            withTimeoutOrNull(RUNTIME_CANCEL_TIMEOUT_MILLIS) {
                runtimeCanceller.cancel(runtimeRunId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The lease-bound failure below is authoritative even when Runtime cancellation fails.
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
        private val activeExecutions = java.util.concurrent.ConcurrentHashMap<String, Job>()
        const val DEFAULT_LEASE_DURATION_MILLIS: Long = 10L * 60L * 1_000L
        const val DEFAULT_MAX_RUNS_PER_TICK: Int = 1
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_INITIAL_RETRY_DELAY_MILLIS: Long = 30L * 1_000L
        const val DEFAULT_MAX_CONCURRENT_RUNS: Int = AgentTaskSchedulerSettings.DEFAULT_MAX_CONCURRENT_RUNS
        /** Leave persistence headroom before WorkManager's ten-minute worker limit. */
        const val DEFAULT_RUN_TIMEOUT_MILLIS: Long = 9L * 60L * 1_000L + 30_000L
        private const val RUNTIME_CANCEL_TIMEOUT_MILLIS: Long = 5_000L
        const val MIN_REMOTE_POLL_DELAY_MILLIS: Long = 15L * 1_000L
        const val MAX_REMOTE_POLL_DELAY_MILLIS: Long = 60L * 60L * 1_000L
        private const val MAX_RETRY_EXPONENT = 10
        private const val MIN_CONDITION_POLL_MILLIS: Long = 60L * 1_000L
        private const val MAX_DEFERRED_DELAY_MILLIS: Long = 15L * 60L * 1_000L
    }
}
