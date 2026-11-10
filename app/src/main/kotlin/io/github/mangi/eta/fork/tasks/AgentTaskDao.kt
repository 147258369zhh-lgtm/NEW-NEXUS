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

    @Query("SELECT * FROM agent_task_attention WHERE status = 'OPEN' ORDER BY created_at, id")
    fun observeOpenAttention(): kotlinx.coroutines.flow.Flow<List<AgentTaskAttentionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDefinition(definition: AgentTaskDefinitionEntity)

    @Update suspend fun updateDefinition(definition: AgentTaskDefinitionEntity)

    @Query("SELECT COUNT(*) FROM agent_task_runs WHERE definition_id = :id AND state IN ('QUEUED','LEASED','RUNNING','WAITING_INPUT','WAITING_AUTH')")
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

    @Query("SELECT run.id FROM agent_task_runs AS run INNER JOIN agent_task_definitions AS definition ON definition.id = run.definition_id WHERE run.state = 'QUEUED' AND definition.enabled = 1 AND run.scheduled_for <= :now ORDER BY run.scheduled_for, run.created_at, run.id LIMIT 1")
    suspend fun nextClaimableRunId(now: Long): String?

    @Query("SELECT MIN(run.scheduled_for) FROM agent_task_runs AS run INNER JOIN agent_task_definitions AS definition ON definition.id = run.definition_id WHERE run.state = 'QUEUED' AND definition.enabled = 1")
    suspend fun earliestQueuedAt(): Long?

    @Query("UPDATE agent_task_runs SET state = 'QUEUED', scheduled_for = :until, local_run_id = NULL, updated_at = :now WHERE id = :id AND state = 'RUNNING'")
    suspend fun deferRow(id: String, until: Long, now: Long): Int

    @Transaction
    suspend fun defer(id: String, owner: String, until: Long, now: Long): Boolean {
        if (lease(id)?.owner != owner || deferRow(id, until, now) == 0) return false
        deleteLease(id)
        appendEvent(id, "WAITING_CONDITIONS", "{}", now)
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
        WHERE id = :runId AND state IN ('QUEUED', 'LEASED', 'RUNNING', 'WAITING_INPUT', 'WAITING_AUTH')
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
        SELECT lease.run_id AS run_id, run.state AS state
        FROM agent_task_leases AS lease
        INNER JOIN agent_task_runs AS run ON run.id = lease.run_id
        WHERE lease.expires_at <= :now AND run.state IN ('LEASED', 'RUNNING')
        ORDER BY lease.expires_at, lease.run_id
        """,
    )
    suspend fun expiredLeases(now: Long): List<AgentTaskExpiredLeaseRow>

    @Query(
        """
        DELETE FROM agent_task_leases
        WHERE run_id IN (
            SELECT id FROM agent_task_runs
            WHERE state IN ('QUEUED', 'WAITING_INPUT', 'WAITING_AUTH', 'SUCCEEDED', 'FAILED', 'CANCELED')
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
    ): AgentTaskClaim? {
        val runId = nextClaimableRunId(now) ?: return null
        if (executingCount() > 0) return null
        if (compareAndSetState(runId, AgentTaskState.QUEUED, AgentTaskState.LEASED, now) == 0) {
            return null
        }
        insertLease(AgentTaskLeaseEntity(runId = runId, owner = owner, expiresAt = leaseExpiresAt, heartbeatAt = now))
        appendEvent(runId, "CLAIMED", "{\"owner\":\"${owner.jsonString()}\"}", now)
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
    suspend fun complete(runId: String, owner: String, resultRef: String, now: Long): Boolean {
        if (lease(runId)?.owner != owner) return false
        if (markSucceededRow(runId, resultRef, now) == 0) return false
        deleteLease(runId)
        appendEvent(runId, "SUCCEEDED", "{\"resultRef\":\"${resultRef.jsonString()}\"}", now)
        return true
    }

    @Transaction
    suspend fun fail(runId: String, owner: String, code: String, message: String, now: Long): Boolean {
        if (lease(runId)?.owner != owner) return false
        if (markFailedRow(runId, code, message, now) == 0) return false
        deleteLease(runId)
        resolveRunAttentionRows(runId, now)
        appendEvent(runId, "FAILED", "{\"code\":\"${code.jsonString()}\"}", now)
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
        if (lease(runId)?.owner != owner) return false
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
        insertLease(AgentTaskLeaseEntity(runId = runId, owner = owner, expiresAt = leaseExpiresAt, heartbeatAt = now))
        if (resumeWaitingRow(runId, localRunId, now) == 0) return false
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
        expiredLeases(now).forEach { expired ->
            when (expired.state) {
                AgentTaskState.LEASED -> {
                    if (compareAndSetState(expired.runId, AgentTaskState.LEASED, AgentTaskState.QUEUED, now) == 1) {
                        requeued++
                        appendEvent(expired.runId, "LEASE_EXPIRED_REQUEUED", "{}", now)
                    }
                }
                AgentTaskState.RUNNING -> {
                    if (markFailedRow(
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
                else -> Unit
            }
            deleteLease(expired.runId)
        }
        deleteOrphanLeases()
        return AgentTaskLeaseRecoverySummary(requeued, failed)
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

private fun String.jsonString(): String = buildString(length + 8) {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}
