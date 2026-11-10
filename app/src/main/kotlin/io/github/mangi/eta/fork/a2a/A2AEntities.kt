package io.github.mangi.eta.fork.a2a

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
