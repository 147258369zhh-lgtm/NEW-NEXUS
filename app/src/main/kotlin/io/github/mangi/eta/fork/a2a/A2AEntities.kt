package io.github.mangi.eta.fork.a2a

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "a2a_agents",
    indices = [Index(value = ["cardUrl"], unique = true)],
)
internal data class A2AAgentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val cardUrl: String,
    val endpointUrl: String,
    val tenant: String?,
    val protocolVersion: String,
    val authSecretAlias: String?,
    val trustState: String,
    val cardJson: String,
    val enabled: Boolean,
    val discoveredAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "a2a_tasks",
    foreignKeys = [
        ForeignKey(
            entity = A2AAgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("agentId"),
        Index(value = ["agentId", "remoteTaskId"], unique = true),
        Index("remoteContextId"),
        Index("updatedAt"),
    ],
)
internal data class A2ATaskEntity(
    @PrimaryKey val localId: String,
    val agentId: String,
    val remoteTaskId: String?,
    val remoteContextId: String?,
    val state: String,
    val cancelRequestedAt: Long?,
    val cancelUnconfirmed: Boolean,
    val lastEventKey: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "a2a_events",
    foreignKeys = [
        ForeignKey(
            entity = A2ATaskEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("localTaskId"),
        Index(value = ["localTaskId", "eventKey"], unique = true),
        Index("receivedAt"),
    ],
)
internal data class A2AEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localTaskId: String,
    val eventKey: String,
    val kind: String,
    val payloadJson: String,
    val receivedAt: Long,
)

@Entity(
    tableName = "a2a_artifacts",
    primaryKeys = ["localTaskId", "artifactId"],
    foreignKeys = [
        ForeignKey(
            entity = A2ATaskEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("localTaskId"), Index("quarantineState")],
)
internal data class A2AArtifactEntity(
    val localTaskId: String,
    val artifactId: String,
    val name: String?,
    val mediaTypeSummary: String?,
    val rawJson: String,
    val quarantineState: String,
    val receivedAt: Long,
)

/** Tasks accepted by this device's inbound A2A endpoint. */
@Entity(
    tableName = "a2a_inbound_tasks",
    indices = [
        Index(value = ["requestMessageId"], unique = true),
        Index("contextId"),
        Index("updatedAt"),
    ],
)
internal data class A2AInboundTaskEntity(
    @PrimaryKey val taskId: String,
    val requestMessageId: String,
    val contextId: String,
    val prompt: String,
    val state: String,
    val statusMessage: String?,
    val answer: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Last continuation message accepted for this task; also fences duplicated retries. */
    val continuationMessageId: String? = null,
    /** Supplemental text supplied with the last accepted continuation. */
    val continuationInput: String? = null,
    /** Number of accepted continuations; non-zero means [prompt] is no longer the current turn. */
    @ColumnInfo(defaultValue = "0") val continuationCount: Int = 0,
    /** Original tool grant captured when the task was accepted; continuations never widen it. */
    val accessJson: String? = null,
    /**
     * Local-only resume ledger of the paused turn: the exact pending call identity and digests of
     * calls that already settled. It never holds transcript text, tool results or argument values
     * and is never returned by the A2A protocol. Verified by [approvalSummaryDigest].
     */
    val approvalSummaryJson: String? = null,
    val approvalSummaryDigest: String? = null,
    @ColumnInfo(defaultValue = "0") val approvalSummaryChars: Int = 0,
)

/** Every accepted or rejected follow-up message, keyed by the peer message id for idempotency. */
@Entity(
    tableName = "a2a_inbound_continuations",
    foreignKeys = [
        ForeignKey(
            entity = A2AInboundTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("taskId"),
        Index(value = ["messageId"], unique = true),
    ],
)
internal data class A2AInboundContinuationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val taskId: String,
    val contextId: String,
    val input: String,
    val outcome: String,
    val acceptedAt: Long,
)

@Entity(
    tableName = "a2a_inbound_artifacts",
    primaryKeys = ["taskId", "artifactId"],
    foreignKeys = [
        ForeignKey(
            entity = A2AInboundTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
internal data class A2AInboundArtifactEntity(
    val taskId: String,
    val artifactId: String,
    val rawJson: String,
    val receivedAt: Long,
)

/** One-shot local owner decision bound to the exact waiting continuation and grant snapshot. */
@Entity(tableName = "a2a_inbound_approvals", indices = [Index("taskId")])
internal data class A2AInboundApprovalEntity(
    @PrimaryKey val taskId: String,
    val messageId: String,
    val originalGrantFingerprint: String,
    val currentGrantFingerprint: String,
    val decision: String,
    val decidedAt: Long,
)
