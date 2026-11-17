package io.github.mangi.eta.fork.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
internal interface AgentTaskDao {
    @Query("SELECT * FROM agent_task_definitions ORDER BY updated_at DESC, id")
    fun observeDefinitions(): kotlinx.coroutines.flow.Flow<List<AgentTaskDefinitionEntity>>

    @Query("SELECT * FROM agent_task_definitions ORDER BY updated_at DESC, id")
    suspend fun definitionsNow(): List<AgentTaskDefinitionEntity>

    @Query("SELECT * FROM agent_task_schedules ORDER BY created_at DESC, id")
    fun observeSchedules(): kotlinx.coroutines.flow.Flow<List<AgentTaskScheduleEntity>>

    @Query("SELECT * FROM agent_task_schedules ORDER BY created_at DESC, id")
    suspend fun schedulesNow(): List<AgentTaskScheduleEntity>

    @Query("SELECT * FROM agent_task_runs ORDER BY scheduled_for DESC, created_at DESC, id LIMIT 100")
    fun observeRecentRuns(): kotlinx.coroutines.flow.Flow<List<AgentTaskRunEntity>>

    @Query("""
        SELECT run.* FROM agent_task_runs AS run
        WHERE run.id = (
            SELECT candidate.id FROM agent_task_runs AS candidate
            WHERE candidate.definition_id = run.definition_id
            ORDER BY candidate.created_at DESC, candidate.id DESC LIMIT 1
        )
    """)
    fun observeLatestRuns(): kotlinx.coroutines.flow.Flow<List<AgentTaskRunEntity>>

    @Query("""
        SELECT DISTINCT run.* FROM agent_task_runs AS run
        INNER JOIN agent_task_attention AS attention ON attention.run_id = run.id
        WHERE attention.status = 'OPEN'
    """)
    fun observeAttentionRuns(): kotlinx.coroutines.flow.Flow<List<AgentTaskRunEntity>>

    @Query("SELECT * FROM agent_task_runs WHERE state = 'A2A_REVIEW' AND a2a_review_decision IS NULL ORDER BY updated_at, id")
    fun observeReviewRuns(): kotlinx.coroutines.flow.Flow<List<AgentTaskRunEntity>>

    @Query("SELECT * FROM agent_task_attention WHERE status = 'OPEN' ORDER BY created_at, id")
    fun observeOpenAttention(): kotlinx.coroutines.flow.Flow<List<AgentTaskAttentionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDefinition(definition: AgentTaskDefinitionEntity)

    @Update suspend fun updateDefinition(definition: AgentTaskDefinitionEntity)

    @Query("SELECT COUNT(*) FROM agent_task_runs WHERE definition_id = :id AND state IN ('QUEUED','LEASED','RUNNING','WAITING_INPUT','WAITING_AUTH','WAITING_REMOTE','A2A_REVIEW')")
    suspend fun activeCount(id: String): Int

    @Query("SELECT COUNT(*) FROM agent_task_runs WHERE state IN ('LEASED','RUNNING')")
    suspend fun executingCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: AgentTaskScheduleEntity)
    @Update suspend fun updateSchedule(schedule: AgentTaskScheduleEntity)
    @Query("DELETE FROM agent_task_schedules WHERE definition_id = :id")
    suspend fun deleteSchedules(id: String)

    @Query("UPDATE agent_task_schedules SET paused = :paused, next_run_at = :nextRunAt, revision = revision + 1, updated_at = :now WHERE id = :scheduleId")
    suspend fun setSchedulePausedRow(
        scheduleId: String,
        paused: Boolean,
        nextRunAt: Long?,
        now: Long,
    ): Int

    @Query("DELETE FROM agent_task_definitions WHERE id = :definitionId")
    suspend fun deleteDefinition(definitionId: String)

    @Transaction
    suspend fun deleteDefinitionIfIdle(definitionId: String) {
        require(activeCount(definitionId) == 0) { "请先结束该任务的运行再删除" }
        deleteDefinition(definitionId)
    }

    @Query("SELECT * FROM agent_task_definitions WHERE id = :id")
    suspend fun definition(id: String): AgentTaskDefinitionEntity?

    @Query("SELECT * FROM agent_task_schedules WHERE id = :id")
    suspend fun schedule(id: String): AgentTaskScheduleEntity?

    @Query(
        """
        SELECT MIN(schedule.next_run_at)
        FROM agent_task_schedules AS schedule
        INNER JOIN agent_task_definitions AS definition
            ON definition.id = schedule.definition_id
        WHERE schedule.paused = 0
          AND definition.enabled = 1
          AND schedule.next_run_at IS NOT NULL
        """,
    )
    suspend fun earliestNextRunAt(): Long?

    @Query("SELECT * FROM agent_task_runs WHERE id = :id")
    suspend fun run(id: String): AgentTaskRunEntity?

    @Query("SELECT * FROM agent_task_runs WHERE definition_id = :definitionId ORDER BY created_at, id")
    suspend fun runsForDefinition(definitionId: String): List<AgentTaskRunEntity>

    @Query("SELECT * FROM agent_task_runs WHERE id = :id")
    fun runFlow(id: String): kotlinx.coroutines.flow.Flow<AgentTaskRunEntity?>

    @Query("SELECT * FROM agent_task_events WHERE run_id = :runId ORDER BY created_at, id")
    suspend fun events(runId: String): List<AgentTaskEventEntity>
    @Query("SELECT * FROM agent_task_events WHERE run_id = :runId ORDER BY created_at, id")
    fun observeEvents(runId: String): kotlinx.coroutines.flow.Flow<List<AgentTaskEventEntity>>

    @Query("SELECT * FROM agent_task_attention WHERE status = 'OPEN' ORDER BY created_at, id")
    suspend fun openAttention(): List<AgentTaskAttentionEntity>

    @Query(
        """
        SELECT schedule.* FROM agent_task_schedules AS schedule
        INNER JOIN agent_task_definitions AS definition
            ON definition.id = schedule.definition_id
        WHERE schedule.paused = 0
          AND definition.enabled = 1
          AND schedule.next_run_at IS NOT NULL
          AND schedule.next_run_at <= :now
        ORDER BY schedule.next_run_at, schedule.id
        LIMIT :limit
        """,
    )
    suspend fun dueSchedules(now: Long, limit: Int): List<AgentTaskScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunIfAbsent(run: AgentTaskRunEntity): Long

    @Transaction
    suspend fun enqueueManual(run: AgentTaskRunEntity): Boolean {
        if (definition(run.definitionId)?.enabled != true || activeCount(run.definitionId) > 0) return false
        return insertRunIfAbsent(run) >= 0
    }

    @Query(
        "UPDATE agent_task_schedules SET paused = 1, updated_at = :now, revision = revision + 1 " +
            "WHERE definition_id = :definitionId AND paused = 0",
    )
    suspend fun pauseSchedulesForDefinitionRow(definitionId: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLease(lease: AgentTaskLeaseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: AgentTaskEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttention(attention: AgentTaskAttentionEntity)

    @Query(
        """
        SELECT run.id FROM agent_task_runs AS run
        INNER JOIN agent_task_definitions AS definition ON definition.id = run.definition_id
        WHERE run.state IN ('QUEUED','WAITING_REMOTE') AND definition.enabled = 1 AND run.scheduled_for <= :now
          AND NOT EXISTS (
              SELECT 1 FROM agent_task_runs AS active
              WHERE active.definition_id = run.definition_id AND active.state IN ('LEASED','RUNNING')
          )
        ORDER BY run.scheduled_for, run.created_at, run.id LIMIT 1
        """,
    )
    suspend fun nextClaimableRunId(now: Long): String?

    @Query("SELECT MIN(run.scheduled_for) FROM agent_task_runs AS run INNER JOIN agent_task_definitions AS definition ON definition.id = run.definition_id WHERE run.state IN ('QUEUED','WAITING_REMOTE') AND definition.enabled = 1 AND NOT EXISTS (SELECT 1 FROM agent_task_runs AS active WHERE active.definition_id = run.definition_id AND active.state IN ('LEASED','RUNNING'))")
    suspend fun earliestQueuedAt(): Long?

    @Query("SELECT MIN(expires_at) FROM agent_task_leases")
    suspend fun earliestLeaseExpiry(): Long?

    @Query("UPDATE agent_task_runs SET state = 'QUEUED', scheduled_for = :until, local_run_id = NULL, updated_at = :now WHERE id = :id AND state = 'RUNNING'")
    suspend fun deferRow(id: String, until: Long, now: Long): Int

    @Transaction
    suspend fun defer(id: String, owner: String, until: Long, now: Long, reason: String? = null): Boolean {
        if (lease(id)?.let { it.owner == owner && it.expiresAt > now } != true || deferRow(id, until, now) == 0) return false
        deleteLease(id)
        appendEvent(id, "WAITING_CONDITIONS", "{\"reason\":${reason.orEmpty().take(300).jsonString()}}", now)
        return true
    }

    @Query(
        """
        UPDATE agent_task_runs
        SET state = :targetState, updated_at = :now
        WHERE id = :runId AND state = :expectedState
        """,
    )
    suspend fun compareAndSetState(
        runId: String,
        expectedState: AgentTaskState,
        targetState: AgentTaskState,
        now: Long,
    ): Int

    @Query("SELECT * FROM agent_task_leases WHERE run_id = :runId")
    suspend fun lease(runId: String): AgentTaskLeaseEntity?

    @Query("DELETE FROM agent_task_leases WHERE run_id = :runId")
    suspend fun deleteLease(runId: String)

    @Query("DELETE FROM agent_task_leases WHERE run_id = :runId AND owner = :owner")
    suspend fun deleteOwnedLease(runId: String, owner: String): Int

    @Query(
        """
        UPDATE agent_task_leases
        SET expires_at = :expiresAt, heartbeat_at = :now
        WHERE run_id = :runId AND owner = :owner
        """,
    )
    suspend fun renewLeaseRow(runId: String, owner: String, expiresAt: Long, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'RUNNING', local_run_id = :localRunId,
            started_at = COALESCE(started_at, :now), updated_at = :now
        WHERE id = :runId AND state = 'LEASED'
        """,
    )
    suspend fun markRunningRow(runId: String, localRunId: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'SUCCEEDED', result_ref = :resultRef, finished_at = :now, updated_at = :now,
            error_code = NULL, error_message = NULL
        WHERE id = :runId AND state = 'RUNNING'
        """,
    )
    suspend fun markSucceededRow(runId: String, resultRef: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'FAILED', error_code = :code, error_message = :message,
            finished_at = :now, updated_at = :now
        WHERE id = :runId AND state IN ('LEASED', 'RUNNING')
        """,
    )
    suspend fun markFailedRow(runId: String, code: String, message: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = :waitingState, updated_at = :now
        WHERE id = :runId AND state = 'RUNNING'
        """,
    )
    suspend fun markWaitingRow(runId: String, waitingState: AgentTaskState, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'RUNNING', local_run_id = :localRunId,
            started_at = COALESCE(started_at, :now), updated_at = :now
        WHERE id = :runId AND state IN ('WAITING_INPUT', 'WAITING_AUTH')
        """,
    )
    suspend fun resumeWaitingRow(runId: String, localRunId: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'QUEUED', updated_at = :now
        WHERE id = :runId AND state = 'WAITING_INPUT'
        """,
    )
    suspend fun queueMissedRunRow(runId: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'CANCELED', finished_at = :now, updated_at = :now
        WHERE id = :runId AND state IN ('QUEUED', 'LEASED', 'RUNNING', 'WAITING_INPUT', 'WAITING_AUTH', 'WAITING_REMOTE', 'A2A_REVIEW')
        """,
    )
    suspend fun cancelRow(runId: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_attention
        SET status = 'RESOLVED', resolved_at = :now
        WHERE run_id = :runId AND status = 'OPEN'
        """,
    )
    suspend fun resolveRunAttentionRows(runId: String, now: Long): Int

    @Query(
        """
        UPDATE agent_task_schedules
        SET last_scheduled_for = :lastScheduledFor,
            next_run_at = :nextRunAt,
            revision = revision + 1,
            updated_at = :now
        WHERE id = :scheduleId AND revision = :expectedRevision
        """,
    )
    suspend fun advanceScheduleRow(
        scheduleId: String,
        expectedRevision: Long,
        lastScheduledFor: Long?,
        nextRunAt: Long?,
        now: Long,
    ): Int

    @Query(
        """
        SELECT lease.run_id AS run_id, run.state AS state, run.local_run_id AS local_run_id,
               run.a2a_task_id AS a2a_task_id,
               run.a2a_message_id AS a2a_message_id,
               run.workflow_checkpoint_json AS workflow_checkpoint_json
        FROM agent_task_leases AS lease
        INNER JOIN agent_task_runs AS run ON run.id = lease.run_id
        WHERE lease.expires_at <= :now AND run.state IN ('LEASED', 'RUNNING')
        ORDER BY lease.expires_at, lease.run_id
        """,
    )
    suspend fun expiredLeases(now: Long): List<AgentTaskExpiredLeaseRow>

    @Transaction
    suspend fun recoverReceipt(
        runId: String,
        ok: Boolean,
        resultRef: String,
        output: String,
        errorCode: String,
        errorMessage: String,
        now: Long,
    ): Boolean {
        val current = run(runId) ?: return false
        val currentLease = lease(runId) ?: return false
        if (current.state != AgentTaskState.RUNNING || currentLease.expiresAt > now) return false
        if (current.workflowCheckpointJson.trim() != "{}") return false
        if (!current.a2aTaskId.isNullOrBlank() || !current.a2aMessageId.isNullOrBlank()) return false
        if (ok) {
            if (markSucceededRow(runId, resultRef, now) == 0) return false
            deleteLease(runId)
            appendEvent(
                runId,
                "RECEIPT_RECOVERED_SUCCEEDED",
                org.json.JSONObject().put("resultRef", resultRef).put("output", output.take(12_000)).toString(),
                now,
            )
        } else {
            if (markFailedRow(runId, errorCode.take(120), errorMessage.take(2_000), now) == 0) return false
            deleteLease(runId)
            resolveRunAttentionRows(runId, now)
            appendEvent(
                runId,
                "RECEIPT_RECOVERED_FAILED",
                org.json.JSONObject().put("code", errorCode.take(120)).toString(),
                now,
            )
        }
        return true
    }

    @Query(
        """
        UPDATE agent_task_runs
        SET workflow_checkpoint_json = :checkpointJson, updated_at = :now
        WHERE id = :runId AND state = 'RUNNING'
          AND EXISTS (
              SELECT 1 FROM agent_task_leases
              WHERE run_id = :runId AND owner = :owner AND expires_at > :now
          )
        """,
    )
    suspend fun updateWorkflowCheckpointRow(
        runId: String,
        owner: String,
        checkpointJson: String,
        now: Long,
    ): Int

    @Transaction
    suspend fun saveWorkflowCheckpoint(
        runId: String,
        owner: String,
        checkpoint: WorkflowCheckpoint,
        now: Long,
    ): Boolean = updateWorkflowCheckpointRow(runId, owner, checkpoint.json(), now) == 1

    @Query(
        """
        DELETE FROM agent_task_leases
        WHERE run_id IN (
            SELECT id FROM agent_task_runs
            WHERE state IN ('QUEUED', 'WAITING_INPUT', 'WAITING_AUTH', 'WAITING_REMOTE', 'SUCCEEDED', 'FAILED', 'CANCELED')
        )
        """,
    )
    suspend fun deleteOrphanLeases(): Int

    @Transaction
    suspend fun insertScheduledTask(
        definition: AgentTaskDefinitionEntity,
        schedule: AgentTaskScheduleEntity,
    ) {
        insertDefinition(definition)
        insertSchedule(schedule)
    }

    @Transaction
    suspend fun claimNextQueued(
        owner: String,
        now: Long,
        leaseExpiresAt: Long,
        maxConcurrentRuns: Int = 1,
    ): AgentTaskClaim? {
        require(maxConcurrentRuns > 0)
        val runId = nextClaimableRunId(now) ?: return null
        if (executingCount() >= maxConcurrentRuns) return null
        val claimable = run(runId) ?: return null
        val claimedState = when (claimable.state) {
            AgentTaskState.QUEUED, AgentTaskState.WAITING_REMOTE -> claimable.state
            else -> return null
        }
        if (compareAndSetState(runId, claimedState, AgentTaskState.LEASED, now) == 0) return null
        insertLease(AgentTaskLeaseEntity(runId = runId, owner = owner, expiresAt = leaseExpiresAt, heartbeatAt = now))
        val eventType = if (claimedState == AgentTaskState.WAITING_REMOTE) "REMOTE_POLL_CLAIMED" else "CLAIMED"
        appendEvent(runId, eventType, "{\"owner\":${owner.jsonString()}}", now)
        return AgentTaskClaim(runId, requireNotNull(run(runId)).definitionId, owner, leaseExpiresAt)
    }

    @Transaction
    suspend fun markRunning(
        runId: String,
        owner: String,
        localRunId: String,
        now: Long,
        leaseExpiresAt: Long,
    ): Boolean {
        val currentLease = lease(runId)
        if (currentLease?.owner != owner || currentLease.expiresAt <= now) return false
        if (markRunningRow(runId, localRunId, now) == 0) return false
        renewLeaseRow(runId, owner, leaseExpiresAt, now)
        appendEvent(runId, "STARTED", "{}", now)
        return true
    }

    @Transaction
    suspend fun renewLease(runId: String, owner: String, now: Long, leaseExpiresAt: Long): Boolean {
        val currentLease = lease(runId) ?: return false
        if (currentLease.owner != owner || currentLease.expiresAt <= now) return false
        val run = run(runId) ?: return false
        if (run.state != AgentTaskState.LEASED && run.state != AgentTaskState.RUNNING) return false
        return renewLeaseRow(runId, owner, leaseExpiresAt, now) == 1
    }

    @Transaction
    suspend fun complete(runId: String, owner: String, resultRef: String, now: Long, output: String = ""): Boolean {
        if (lease(runId)?.let { it.owner == owner && it.expiresAt > now } != true) return false
        if (markSucceededRow(runId, resultRef, now) == 0) return false
        deleteLease(runId)
        appendEvent(runId, "SUCCEEDED", org.json.JSONObject().put("resultRef", resultRef).put("output", output.take(12_000)).toString(), now)
        return true
    }

    @Transaction
    suspend fun fail(runId: String, owner: String, code: String, message: String, now: Long): Boolean {
        if (lease(runId)?.let { it.owner == owner && it.expiresAt > now } != true) return false
        if (markFailedRow(runId, code, message, now) == 0) return false
        deleteLease(runId)
        resolveRunAttentionRows(runId, now)
        appendEvent(runId, "FAILED", "{\"code\":${code.jsonString()}}", now)
        return true
    }

    /** Queues one bounded retry only while the owning schedule is still active. */
    @Transaction
    suspend fun queueRetryIfScheduleActive(
        retry: AgentTaskRunEntity,
        now: Long,
    ): Boolean {
        val scheduleId = retry.scheduleId ?: return false
        val owningSchedule = schedule(scheduleId) ?: return false
        if (owningSchedule.paused) return false
        if (insertRunIfAbsent(retry) < 0) return false
        appendEvent(
            runId = retry.id,
            type = "RETRY_QUEUED",
            payloadJson = "{\"attempt\":${retry.attempt}}",
            now = now,
        )
        return true
    }

    @Transaction
    suspend fun pauseSchedulesAfterRetryExhaustion(
        definitionId: String,
        runId: String,
        maxAttempts: Int,
        now: Long,
    ): Boolean {
        if (pauseSchedulesForDefinitionRow(definitionId, now) == 0) return false
        appendEvent(
            runId = runId,
            type = "SCHEDULE_PAUSED_AFTER_RETRIES",
            payloadJson = "{\"maxAttempts\":$maxAttempts}",
            now = now,
        )
        return true
    }

    @Transaction
    suspend fun waitForAttention(
        runId: String,
        owner: String,
        state: AgentTaskState,
        attentionKind: AgentTaskAttentionKind,
        attentionId: String,
        title: String,
        body: String,
        now: Long,
    ): Boolean {
        require(state == AgentTaskState.WAITING_INPUT || state == AgentTaskState.WAITING_AUTH)
        if (lease(runId)?.let { it.owner == owner && it.expiresAt > now } != true) return false
        if (markWaitingRow(runId, state, now) == 0) return false
        deleteLease(runId)
        insertAttention(
            AgentTaskAttentionEntity(
                id = attentionId,
                runId = runId,
                kind = attentionKind,
                status = AgentTaskAttentionStatus.OPEN,
                title = title,
                body = body,
                createdAt = now,
            ),
        )
        appendEvent(runId, "WAITING", "{\"kind\":\"${attentionKind.name}\"}", now)
        return true
    }

    @Transaction
    suspend fun resumeWaiting(
        runId: String,
        owner: String,
        localRunId: String,
        now: Long,
        leaseExpiresAt: Long,
    ): Boolean {
        if (resumeWaitingRow(runId, localRunId, now) == 0) return false
        insertLease(AgentTaskLeaseEntity(runId = runId, owner = owner, expiresAt = leaseExpiresAt, heartbeatAt = now))
        resolveRunAttentionRows(runId, now)
        appendEvent(runId, "RESUMED", "{}", now)
        return true
    }

    @Transaction
    suspend fun queueMissedRun(runId: String, now: Long): Boolean {
        if (queueMissedRunRow(runId, now) == 0) return false
        resolveRunAttentionRows(runId, now)
        appendEvent(runId, "MISSED_RUN_ACCEPTED", "{}", now)
        return true
    }

    @Transaction
    suspend fun cancel(runId: String, now: Long): Boolean {
        if (cancelRow(runId, now) == 0) return false
        deleteLease(runId)
        resolveRunAttentionRows(runId, now)
        appendEvent(runId, "CANCELED", "{}", now)
        return true
    }

    @Transaction
    suspend fun recoverExpiredLeases(now: Long): AgentTaskLeaseRecoverySummary {
        var requeued = 0
        var failed = 0
        var resumedRemote = 0
        var resumedWorkflow = 0
        expiredLeases(now).forEach { expired ->
            when (expired.state) {
                AgentTaskState.LEASED -> {
                    if (compareAndSetState(expired.runId, AgentTaskState.LEASED, AgentTaskState.QUEUED, now) == 1) {
                        requeued++
                        appendEvent(expired.runId, "LEASE_EXPIRED_REQUEUED", "{}", now)
                    }
                }
                AgentTaskState.RUNNING -> {
                    // A run already bound to a remote A2A task can be verified by querying that task,
                    // so a dead process resumes the wait instead of failing the logical run.
                    if (!expired.a2aTaskId.isNullOrBlank() &&
                        markRemoteWaitingRow(expired.runId, nextPollAt = now, now = now) == 1
                    ) {
                        resumedRemote++
                        appendEvent(expired.runId, "A2A_REMOTE_RESUMED_AFTER_LEASE_EXPIRY", "{}", now)
                    } else if (!expired.a2aMessageId.isNullOrBlank() &&
                        markA2AReviewRow(
                            expired.runId,
                            "A2A_DISPATCH_UNKNOWN",
                            "派发结果未知，已保留消息 ID；不会自动重放，请核对远端任务",
                            now,
                        ) == 1
                    ) {
                        failed++
                        appendEvent(expired.runId, "A2A_DISPATCH_UNKNOWN", "{}", now)
                    } else run {
                        val checkpoint = runCatching {
                            WorkflowCheckpoint.parse(expired.workflowCheckpointJson)
                        }.getOrNull()
                        val hasWorkflowCursor = checkpoint != null && (
                            checkpoint.workflowStartedAt != null || checkpoint.nextStepIndex > 0 ||
                                checkpoint.nextIteration > 1 || checkpoint.waitUntil != null
                            )
                        when {
                            checkpoint == null && expired.workflowCheckpointJson != "{}" -> {
                                if (markFailedRow(
                                        expired.runId,
                                        "WORKFLOW_CHECKPOINT_INVALID",
                                        "工作流断点损坏，未自动重放",
                                        now,
                                    ) == 1
                                ) {
                                    failed++
                                    appendEvent(expired.runId, "WORKFLOW_CHECKPOINT_INVALID", "{}", now)
                                }
                            }
                            checkpoint?.stepStarted == true -> {
                                if (markFailedRow(
                                        expired.runId,
                                        "WORKFLOW_STEP_UNCONFIRMED",
                                        "步骤已开始但未确认完成，未自动重放",
                                        now,
                                    ) == 1
                                ) {
                                    failed++
                                    appendEvent(expired.runId, "WORKFLOW_STEP_UNCONFIRMED", "{}", now)
                                }
                            }
                            hasWorkflowCursor &&
                                compareAndSetState(
                                    expired.runId,
                                    AgentTaskState.RUNNING,
                                    AgentTaskState.QUEUED,
                                    now,
                                ) == 1 -> {
                                resumedWorkflow++
                                appendEvent(expired.runId, "WORKFLOW_RESUMED_AFTER_LEASE_EXPIRY", "{}", now)
                            }
                            else -> if (markFailedRow(
                                    expired.runId,
                                    "LEASE_EXPIRED",
                                    "执行租约已过期，未自动重试以避免重复副作用",
                                    now,
                                ) == 1
                            ) {
                                failed++
                                appendEvent(expired.runId, "LEASE_EXPIRED_FAILED", "{}", now)
                            }
                        }
                    }
                }
                else -> Unit
            }
            deleteLease(expired.runId)
        }
        deleteOrphanLeases()
        return AgentTaskLeaseRecoverySummary(requeued, failed, resumedRemote, resumedWorkflow)
    }

    @Transaction
    suspend fun materializeDueSchedules(
        now: Long,
        limit: Int = 50,
        runIdFactory: () -> String,
        attentionIdFactory: () -> String,
    ): AgentTaskMaterializationSummary {
        require(limit > 0)
        var created = 0
        var skipped = 0
        var asked = 0
        var exhausted = 0
        dueSchedules(now, limit).forEach { schedule ->
            val rule = schedule.toRule()
            val decision = TaskScheduleCalculator.decideAfter(
                rule = rule,
                lastScheduledFor = schedule.lastScheduledFor,
                now = now,
                missedRunPolicy = schedule.missedRunPolicy,
            )
            val nextRunAt: Long?
            val lastScheduledFor: Long?
            when (decision) {
                is MissedRunDecision.Scheduled -> {
                    nextRunAt = decision.scheduledFor
                    lastScheduledFor = schedule.lastScheduledFor
                }
                is MissedRunDecision.RunNow -> {
                    if (insertRunIfAbsent(
                            queuedRunFor(
                                schedule = schedule,
                                scheduledFor = decision.scheduledFor,
                                runId = runIdFactory(),
                                now = now,
                            ),
                        ) >= 0
                    ) {
                        created++
                    } else {
                        skipped++
                    }
                    // A catch-up decision jumps over every intervening slot. The watermark prevents
                    // the next tick from replaying the oldest missed slot instead of next_run_at.
                    lastScheduledFor = now
                    nextRunAt = decision.nextScheduledFor
                }
                is MissedRunDecision.AskUser -> {
                    val runId = runIdFactory()
                    if (insertRunIfAbsent(
                            queuedRunFor(
                                schedule = schedule,
                                scheduledFor = decision.scheduledFor,
                                runId = runId,
                                now = now,
                            ).copy(state = AgentTaskState.WAITING_INPUT),
                        ) >= 0
                    ) {
                        insertAttention(
                            AgentTaskAttentionEntity(
                                id = attentionIdFactory(),
                                runId = runId,
                                kind = AgentTaskAttentionKind.MISSED_RUN_DECISION,
                                status = AgentTaskAttentionStatus.OPEN,
                                title = "需要决定是否补跑任务",
                                body = "计划时间 ${decision.scheduledFor} 的任务已错过。",
                                createdAt = now,
                            ),
                        )
                        asked++
                    } else {
                        skipped++
                    }
                    lastScheduledFor = now
                    nextRunAt = TaskScheduleCalculator.nextOccurrence(rule, now)
                }
                MissedRunDecision.Exhausted -> {
                    lastScheduledFor = schedule.lastScheduledFor
                    nextRunAt = null
                    exhausted++
                }
            }
            advanceScheduleRow(
                scheduleId = schedule.id,
                expectedRevision = schedule.revision,
                lastScheduledFor = lastScheduledFor,
                nextRunAt = nextRunAt,
                now = now,
            )
        }
        return AgentTaskMaterializationSummary(created, skipped, asked, exhausted)
    }

    suspend fun appendEvent(runId: String, type: String, payloadJson: String, now: Long) {
        insertEvent(
            AgentTaskEventEntity(
                runId = runId,
                eventType = type,
                payloadJson = payloadJson,
                createdAt = now,
            ),
        )
    }

    @Query(
        "SELECT * FROM agent_task_runs WHERE a2a_agent_id = :agentId AND a2a_task_id = :remoteTaskId " +
            "ORDER BY created_at DESC, id LIMIT 1",
    )
    suspend fun runByRemoteTask(agentId: String, remoteTaskId: String): AgentTaskRunEntity?

    @Query(
        """
        UPDATE agent_task_runs
        SET a2a_agent_id = :agentId, a2a_message_id = :messageId, a2a_message_seq = :messageSeq,
            updated_at = :now
        WHERE id = :runId AND state = 'RUNNING'
          AND a2a_task_id IS NULL AND a2a_message_id IS NULL
        """,
    )
    suspend fun recordA2ADispatchIntentRow(
        runId: String,
        agentId: String,
        messageId: String,
        messageSeq: Int,
        now: Long,
    ): Int

    /**
     * Records the dispatch intent before the request leaves the device. A later restart can tell
     * that a message id exists without a remote task, so it reports an unknown outcome instead of
     * replaying the prompt and risking a duplicate external effect.
     */
    @Transaction
    suspend fun recordA2ADispatchIntent(
        runId: String,
        agentId: String,
        messageId: String,
        messageSeq: Int,
        now: Long,
    ): Boolean {
        if (recordA2ADispatchIntentRow(runId, agentId, messageId, messageSeq, now) == 0) return false
        setA2ADeadline(runId, saturatingAdd(now, A2A_REVIEW_TIMEOUT_MILLIS), now)
        appendEvent(
            runId,
            "A2A_DISPATCH_INTENT",
            "{\"agentId\":${agentId.jsonString()},\"messageId\":${messageId.jsonString()}}",
            now,
        )
        return true
    }

    @Query("UPDATE agent_task_runs SET a2a_deadline_at = COALESCE(a2a_deadline_at, :deadline), updated_at = :now WHERE id = :runId AND state = 'RUNNING'")
    suspend fun setA2ADeadline(runId: String, deadline: Long, now: Long): Int

    @Query("UPDATE agent_task_runs SET state = 'A2A_REVIEW', error_code = :code, error_message = :message, finished_at = :now, updated_at = :now WHERE id = :runId AND state IN ('RUNNING','WAITING_REMOTE') AND a2a_review_decision IS NULL")
    suspend fun markA2AReviewRow(runId: String, code: String, message: String, now: Long): Int

    @Query("SELECT id FROM agent_task_runs WHERE state = 'WAITING_REMOTE' AND a2a_review_decision IS NULL AND a2a_deadline_at IS NOT NULL AND a2a_deadline_at <= :now")
    suspend fun expiredA2AWaits(now: Long): List<String>

    @Query("UPDATE agent_task_runs SET state = 'A2A_REVIEW', error_code = 'A2A_WAIT_EXPIRED', error_message = '等待 A2A 结果已超时，请核对远端任务', finished_at = :now, updated_at = :now WHERE id = :runId AND state = 'WAITING_REMOTE' AND a2a_review_decision IS NULL")
    suspend fun expireA2AWaitRow(runId: String, now: Long): Int

    @Query("UPDATE agent_task_runs SET state = 'REVIEW_DECIDED', a2a_review_decision = :decision, finished_at = :now, updated_at = :now WHERE id = :runId AND state = 'A2A_REVIEW' AND a2a_review_decision IS NULL")
    suspend fun recordA2AReviewDecisionRow(runId: String, decision: String, now: Long): Int

    @Query("UPDATE agent_task_runs SET state = 'CANCELED', a2a_review_decision = 'CANCELED', finished_at = :now, updated_at = :now WHERE id = :runId AND state = 'A2A_REVIEW' AND a2a_review_decision IS NULL")
    suspend fun cancelA2AReviewRow(runId: String, now: Long): Int

    @Transaction
    suspend fun cancelA2AReview(runId: String, now: Long): Boolean {
        if (cancelA2AReviewRow(runId, now) == 0) return false
        appendEvent(runId, "A2A_REVIEW_DECISION", "{\"decision\":\"CANCELED\"}", now)
        return true
    }

    @Transaction
    suspend fun markA2AReview(runId: String, code: String, message: String, now: Long): Boolean {
        if (markA2AReviewRow(runId, code, message, now) == 0) return false
        deleteLease(runId)
        appendEvent(runId, "A2A_REVIEW_REQUIRED", "{\"code\":${code.jsonString()}}", now)
        return true
    }

    @Transaction
    suspend fun expireA2AWaits(now: Long): Int {
        var count = 0
        expiredA2AWaits(now).forEach { id ->
            if (expireA2AWaitRow(id, now) == 1) {
                count++
                appendEvent(id, "A2A_WAIT_EXPIRED", "{}", now)
            }
        }
        return count
    }

    @Transaction
    suspend fun recordA2AReviewDecision(runId: String, decision: String, now: Long): Boolean {
        require(decision in setOf("CHECKED", "RESTART_REQUESTED"))
        if (recordA2AReviewDecisionRow(runId, decision, now) == 0) return false
        appendEvent(runId, "A2A_REVIEW_DECISION", "{\"decision\":\"$decision\"}", now)
        return true
    }

    @Query(
        """
        UPDATE agent_task_runs
        SET a2a_agent_id = :agentId, a2a_message_id = :messageId, a2a_message_seq = :messageSeq,
            updated_at = :now
        WHERE id = :runId AND state = 'RUNNING'
          AND a2a_task_id = :remoteTaskId AND a2a_message_seq = :expectedSeq
        """,
    )
    suspend fun recordA2AContinuationIntentRow(
        runId: String,
        agentId: String,
        remoteTaskId: String,
        messageId: String,
        expectedSeq: Int,
        messageSeq: Int,
        now: Long,
    ): Int

    /**
     * Continuations are submitted to the original remote task with one stable message id per
     * attempt, so a peer can deduplicate a resume that is retried after a restart.
     */
    @Transaction
    suspend fun recordA2AContinuationIntent(
        runId: String,
        agentId: String,
        remoteTaskId: String,
        messageId: String,
        expectedSeq: Int,
        messageSeq: Int,
        now: Long,
    ): Boolean {
        if (recordA2AContinuationIntentRow(
                runId = runId,
                agentId = agentId,
                remoteTaskId = remoteTaskId,
                messageId = messageId,
                expectedSeq = expectedSeq,
                messageSeq = messageSeq,
                now = now,
            ) == 0
        ) {
            return false
        }
        appendEvent(
            runId,
            "A2A_CONTINUATION_INTENT",
            "{\"messageId\":${messageId.jsonString()},\"seq\":$messageSeq}",
            now,
        )
        return true
    }

    @Query(
        """
        UPDATE agent_task_runs
        SET a2a_agent_id = :agentId, a2a_task_id = :remoteTaskId,
            a2a_context_id = COALESCE(:contextId, a2a_context_id), updated_at = :now
        WHERE id = :runId AND (a2a_task_id IS NULL OR a2a_task_id = :remoteTaskId)
        """,
    )
    suspend fun bindA2ARemoteTaskRow(
        runId: String,
        agentId: String,
        remoteTaskId: String,
        contextId: String?,
        now: Long,
    ): Int

    @Transaction
    suspend fun bindA2ARemoteTask(
        runId: String,
        agentId: String,
        remoteTaskId: String,
        contextId: String?,
        now: Long,
    ): Boolean {
        if (bindA2ARemoteTaskRow(runId, agentId, remoteTaskId, contextId, now) == 0) return false
        appendEvent(
            runId,
            "A2A_REMOTE_BOUND",
            "{\"agentId\":${agentId.jsonString()},\"remoteTaskId\":${remoteTaskId.jsonString()}}",
            now,
        )
        return true
    }

    @Query(
        """
        UPDATE agent_task_runs
        SET state = 'WAITING_REMOTE', scheduled_for = :nextPollAt, updated_at = :now
        WHERE id = :runId AND state = 'RUNNING'
        """,
    )
    suspend fun markRemoteWaitingRow(runId: String, nextPollAt: Long, now: Long): Int

    /** Parks a logical run on its remote task until [nextPollAt] without failing it. */
    @Transaction
    suspend fun waitForRemote(
        runId: String,
        owner: String,
        nextPollAt: Long,
        status: String,
        now: Long,
    ): Boolean {
        if (lease(runId)?.let { it.owner == owner && it.expiresAt > now } != true) return false
        if (markRemoteWaitingRow(runId, nextPollAt, now) == 0) return false
        setA2ADeadline(runId, saturatingAdd(now, A2A_REVIEW_TIMEOUT_MILLIS), now)
        deleteLease(runId)
        appendEvent(runId, "A2A_WAITING_REMOTE", "{\"status\":${status.jsonString()}}", now)
        return true
    }

    private fun queuedRunFor(
        schedule: AgentTaskScheduleEntity,
        scheduledFor: Long,
        runId: String,
        now: Long,
    ): AgentTaskRunEntity = AgentTaskRunEntity(
        id = runId,
        definitionId = schedule.definitionId,
        scheduleId = schedule.id,
        scheduledFor = scheduledFor,
        attempt = 1,
        idempotencyKey = "schedule:${schedule.id}:$scheduledFor",
        state = AgentTaskState.QUEUED,
        createdAt = now,
        updatedAt = now,
    )

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        private const val A2A_REVIEW_TIMEOUT_MILLIS = 24L * 60L * 60L * 1000L
    }

    private fun AgentTaskScheduleEntity.toRule(): AgentScheduleRule = AgentScheduleRule(
        kind = kind,
        startAt = startAt,
        endAt = endAt,
        intervalMinutes = intervalMinutes,
        localHour = localHour,
        localMinute = localMinute,
        weekdaysMask = weekdaysMask,
        timeZoneId = timeZoneId,
        monthDay = monthDay,
    )
}

private fun String.jsonString(): String = org.json.JSONObject.quote(this)
