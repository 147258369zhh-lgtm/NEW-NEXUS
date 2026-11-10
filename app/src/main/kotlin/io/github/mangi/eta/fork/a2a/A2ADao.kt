package io.github.mangi.eta.fork.a2a

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface A2ADao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: A2AAgentEntity)

    @Query("SELECT * FROM a2a_agents ORDER BY displayName COLLATE NOCASE")
    fun observeAgents(): Flow<List<A2AAgentEntity>>

    @Query("SELECT * FROM a2a_agents WHERE id = :id")
    suspend fun agent(id: String): A2AAgentEntity?

    @Query("SELECT * FROM a2a_agents WHERE cardUrl = :cardUrl LIMIT 1")
    suspend fun agentByCardUrl(cardUrl: String): A2AAgentEntity?

    @Query("DELETE FROM a2a_agents WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("UPDATE a2a_agents SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setAgentEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: A2ATaskEntity)

    @Query("SELECT * FROM a2a_tasks WHERE localId = :localId")
    suspend fun task(localId: String): A2ATaskEntity?

    @Query("SELECT * FROM a2a_tasks WHERE agentId = :agentId AND remoteTaskId = :remoteTaskId LIMIT 1")
    suspend fun taskByRemoteId(agentId: String, remoteTaskId: String): A2ATaskEntity?

    @Query("SELECT * FROM a2a_tasks ORDER BY updatedAt DESC")
    fun observeTasks(): Flow<List<A2ATaskEntity>>

    @Query(
        "UPDATE a2a_tasks SET state = :state, lastEventKey = COALESCE(:eventKey, lastEventKey), " +
            "errorCode = :errorCode, errorMessage = :errorMessage, updatedAt = :updatedAt " +
            "WHERE localId = :localId",
    )
    suspend fun updateTaskState(
        localId: String,
        state: String,
        eventKey: String?,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE a2a_tasks SET cancelRequestedAt = :requestedAt, cancelUnconfirmed = 0, updatedAt = :requestedAt " +
            "WHERE localId = :localId AND state IN ('SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED')",
    )
    suspend fun markCancelRequested(localId: String, requestedAt: Long): Int

    @Query(
        "UPDATE a2a_tasks SET cancelUnconfirmed = 1, errorCode = :errorCode, errorMessage = :message, " +
            "updatedAt = :updatedAt WHERE localId = :localId",
    )
    suspend fun markCancelUnconfirmed(
        localId: String,
        errorCode: String,
        message: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: A2AEventEntity): Long

    @Query("SELECT * FROM a2a_events WHERE localTaskId = :localTaskId ORDER BY id")
    fun observeEvents(localTaskId: String): Flow<List<A2AEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifact(artifact: A2AArtifactEntity)

    @Query("SELECT * FROM a2a_artifacts WHERE localTaskId = :localTaskId ORDER BY artifactId")
    fun observeArtifacts(localTaskId: String): Flow<List<A2AArtifactEntity>>

    @Query(
        "UPDATE a2a_artifacts SET quarantineState = :state " +
            "WHERE localTaskId = :localTaskId AND artifactId = :artifactId",
    )
    suspend fun setArtifactQuarantineState(localTaskId: String, artifactId: String, state: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInboundTask(task: A2AInboundTaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboundTask(task: A2AInboundTaskEntity): Long

    @Query("SELECT * FROM a2a_inbound_tasks WHERE taskId = :taskId")
    suspend fun inboundTask(taskId: String): A2AInboundTaskEntity?

    @Query("SELECT * FROM a2a_inbound_tasks WHERE requestMessageId = :messageId LIMIT 1")
    suspend fun inboundTaskByRequestMessage(messageId: String): A2AInboundTaskEntity?

    @Query("SELECT * FROM a2a_inbound_tasks WHERE contextId = :contextId ORDER BY createdAt, taskId")
    suspend fun inboundTasksByContext(contextId: String): List<A2AInboundTaskEntity>

    @Query(
        "SELECT * FROM a2a_inbound_tasks WHERE state IN ('SUBMITTED','WORKING') " +
            "ORDER BY createdAt, taskId",
    )
    suspend fun pendingInboundTasks(): List<A2AInboundTaskEntity>

    @Query(
        "UPDATE a2a_inbound_tasks SET state = :state, statusMessage = :statusMessage, " +
            "answer = :answer, updatedAt = :updatedAt WHERE taskId = :taskId",
    )
    suspend fun updateInboundTask(
        taskId: String,
        state: String,
        statusMessage: String?,
        answer: String?,
        updatedAt: Long,
    )

    /** Compare-and-set transition used to fence late completions after cancel/restart. */
    @Query(
        "UPDATE a2a_inbound_tasks SET state = :newState, statusMessage = :statusMessage, " +
            "answer = :answer, updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND state = :expectedState",
    )
    suspend fun transitionInboundTask(
        taskId: String,
        expectedState: String,
        newState: String,
        statusMessage: String?,
        answer: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE a2a_inbound_tasks SET state = 'CANCELED', statusMessage = :statusMessage, " +
            "answer = NULL, updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND state IN ('SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED')",
    )
    suspend fun cancelInboundTaskIfActive(
        taskId: String,
        statusMessage: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInboundArtifact(artifact: A2AInboundArtifactEntity)

    @Query("SELECT * FROM a2a_inbound_artifacts WHERE taskId = :taskId ORDER BY artifactId")
    suspend fun inboundArtifacts(taskId: String): List<A2AInboundArtifactEntity>
}
