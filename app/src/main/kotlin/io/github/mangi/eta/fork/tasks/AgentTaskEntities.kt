package io.github.mangi.eta.fork.tasks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_task_definitions")
internal data class AgentTaskDefinitionEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "run_kind") val runKind: AgentTaskRunKind,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "prompt_template") val promptTemplate: String,
    @ColumnInfo(name = "participant_config_json", defaultValue = "'{}'")
    val participantConfigJson: String = "{}",
    @ColumnInfo(name = "policy_json", defaultValue = "'{}'")
    val policyJson: String = "{}",
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "agent_task_schedules",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["definition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("definition_id"),
        Index(value = ["paused", "next_run_at"]),
    ],
)
internal data class AgentTaskScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "definition_id") val definitionId: String,
    val kind: AgentScheduleKind,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Long?,
    @ColumnInfo(name = "local_hour") val localHour: Int?,
    @ColumnInfo(name = "local_minute") val localMinute: Int?,
    @ColumnInfo(name = "weekdays_mask") val weekdaysMask: Int?,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    @ColumnInfo(name = "missed_run_policy") val missedRunPolicy: MissedRunPolicy,
    @ColumnInfo(name = "last_scheduled_for") val lastScheduledFor: Long?,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long?,
    val paused: Boolean,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "month_day") val monthDay: Int? = null,
)

@Entity(
    tableName = "agent_task_runs",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["definition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentTaskScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("definition_id"),
        Index("schedule_id"),
        Index(value = ["state", "scheduled_for"]),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["local_run_id"], unique = true),
    ],
)
internal data class AgentTaskRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "definition_id") val definitionId: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String?,
    @ColumnInfo(name = "scheduled_for") val scheduledFor: Long,
    val attempt: Int,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    val state: AgentTaskState,
    @ColumnInfo(name = "local_run_id") val localRunId: String? = null,
    @ColumnInfo(name = "result_ref") val resultRef: String? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "agent_task_leases",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("expires_at")],
)
internal data class AgentTaskLeaseEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    val owner: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "heartbeat_at") val heartbeatAt: Long,
)

@Entity(
    tableName = "agent_task_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id", "created_at"])],
)
internal data class AgentTaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "agent_task_attention",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("run_id"),
        Index(value = ["status", "created_at"]),
    ],
)
internal data class AgentTaskAttentionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "run_id") val runId: String,
    val kind: AgentTaskAttentionKind,
    val status: AgentTaskAttentionStatus,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
)

internal data class AgentTaskExpiredLeaseRow(
    @ColumnInfo(name = "run_id") val runId: String,
    val state: AgentTaskState,
)
