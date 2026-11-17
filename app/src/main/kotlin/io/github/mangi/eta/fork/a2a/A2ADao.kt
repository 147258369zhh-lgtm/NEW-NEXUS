package io.github.mangi.eta.fork.a2a

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface A2ADao {
    // REPLACE deletes the parent row first and cascades all paired task history away.
    @Upsert
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

    // Updating a receipt must retain its send-intent events and quarantined artifacts.
    @Upsert
    suspend fun upsertTask(task: A2ATaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskIfAbsent(task: A2ATaskEntity): Long

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

    @Query("SELECT * FROM a2a_inbound_tasks WHERE state = 'AUTH_REQUIRED' ORDER BY createdAt, taskId")
    fun observeAuthorizationRequiredInboundTasks(): Flow<List<A2AInboundTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInboundApproval(approval: A2AInboundApprovalEntity)

    @Query("SELECT * FROM a2a_inbound_approvals WHERE taskId = :taskId LIMIT 1")
    suspend fun inboundApproval(taskId: String): A2AInboundApprovalEntity?

    @Query("DELETE FROM a2a_inbound_approvals WHERE taskId = :taskId")
    suspend fun clearInboundApproval(taskId: String): Int

    @Query("UPDATE a2a_inbound_continuations SET outcome = :outcome WHERE taskId = :taskId AND messageId = :messageId AND outcome = 'AUTH_REQUIRED_PENDING'")
    suspend fun decideInboundContinuation(taskId: String, messageId: String, outcome: String): Int

    @Query("UPDATE a2a_inbound_tasks SET state = 'WORKING', statusMessage = NULL, answer = NULL, continuationMessageId = :messageId, continuationInput = :input, continuationCount = continuationCount + 1, updatedAt = :updatedAt WHERE taskId = :taskId AND state = 'AUTH_REQUIRED'")
    suspend fun beginApprovedInboundContinuation(taskId: String, messageId: String, input: String, updatedAt: Long): Int

    @Transaction
    suspend fun decideInboundApproval(
        approval: A2AInboundApprovalEntity,
        approved: Boolean,
        input: String,
        updatedAt: Long,
        approvalSummaryJson: String? = null,
        approvalSummaryDigest: String? = null,
        approvalSummaryChars: Int = 0,
    ): Boolean {
        if (inboundTask(approval.taskId)?.state != "AUTH_REQUIRED") return false
        if (inboundContinuation(approval.messageId)?.let { it.taskId == approval.taskId && it.outcome == "AUTH_REQUIRED_PENDING" } != true) return false
        putInboundApproval(approval)
        if (decideInboundContinuation(approval.taskId, approval.messageId, approval.decision) == 0) return false
        if (!approved) return transitionInboundTask(
            approval.taskId, "AUTH_REQUIRED", "REJECTED", "Authorization denied by device owner.", null, updatedAt,
        ) > 0
        if (approvalSummaryJson != null) {
            // Re-anchor the verified ledger to the exact continuation the owner approved.
            saveInboundApprovalSummary(
                taskId = approval.taskId,
                json = approvalSummaryJson,
                digest = approvalSummaryDigest,
                chars = approvalSummaryChars,
                updatedAt = updatedAt,
            )
        }
        return beginApprovedInboundContinuation(approval.taskId, approval.messageId, input, updatedAt) > 0
    }

    @Query("SELECT * FROM a2a_inbound_tasks WHERE requestMessageId = :messageId LIMIT 1")
    suspend fun inboundTaskByRequestMessage(messageId: String): A2AInboundTaskEntity?

    @Query("SELECT * FROM a2a_inbound_tasks WHERE contextId = :contextId ORDER BY createdAt, taskId")
    suspend fun inboundTasksByContext(contextId: String): List<A2AInboundTaskEntity>

    @Query(
        "SELECT * FROM a2a_inbound_tasks " +
            "WHERE state IN ('SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED') " +
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

    /**
     * Compare-and-set fence taken immediately before a Runtime start.
     *
     * It only succeeds while the row is still [WORKING][A2ATaskState.WORKING], so a remote cancel
     * that already terminalized the task (or another recovery owner that moved it on) makes the
     * update miss and the dispatcher stops without invoking the executor.
     */
    @Query(
        "UPDATE a2a_inbound_tasks SET state = 'WORKING', updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND state = 'WORKING'",
    )
    suspend fun claimInboundTaskWorking(taskId: String, updatedAt: Long): Int

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

    @Query("UPDATE a2a_inbound_tasks SET accessJson = :accessJson, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun setInboundTaskAccess(taskId: String, accessJson: String, updatedAt: Long): Int

    /** Local-only resume ledger of the paused turn; never exposed through the protocol. */
    @Query(
        "UPDATE a2a_inbound_tasks SET approvalSummaryJson = :json, approvalSummaryDigest = :digest, " +
            "approvalSummaryChars = :chars, updatedAt = :updatedAt WHERE taskId = :taskId",
    )
    suspend fun saveInboundApprovalSummary(
        taskId: String,
        json: String?,
        digest: String?,
        chars: Int,
        updatedAt: Long,
    ): Int

    /**
     * Compare-and-set transition into an active peer continuation. The original prompt is retained
     * in [A2AInboundTaskEntity.prompt]; only the follow-up input and its message id change.
     *
     * Only INPUT_REQUIRED may be claimed here. An AUTH_REQUIRED row has an unexecuted call waiting
     * for the device owner, so a peer continuation that races the pause must not resume it through
     * this path; that state is resumed exclusively by [beginApprovedInboundContinuation].
     */
    @Query(
        "UPDATE a2a_inbound_tasks SET state = 'WORKING', statusMessage = NULL, answer = NULL, " +
            "continuationMessageId = :messageId, continuationInput = :input, " +
            "continuationCount = continuationCount + 1, updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND state = 'INPUT_REQUIRED'",
    )
    suspend fun beginInboundContinuation(
        taskId: String,
        messageId: String,
        input: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE a2a_inbound_tasks SET state = 'AUTH_REQUIRED', statusMessage = :statusMessage, " +
            "updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND state IN ('INPUT_REQUIRED','AUTH_REQUIRED')",
    )
    suspend fun requireInboundAuthorization(
        taskId: String,
        statusMessage: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE a2a_inbound_tasks SET state = 'AUTH_REQUIRED', statusMessage = :statusMessage, " +
            "answer = NULL, updatedAt = :updatedAt WHERE taskId = :taskId AND state = 'WORKING'",
    )
    suspend fun pauseInboundTaskForAuthorization(
        taskId: String,
        statusMessage: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE a2a_inbound_continuations SET outcome = 'AUTH_REQUIRED_SUPERSEDED' " +
            "WHERE taskId = :taskId AND messageId != :messageId AND outcome = 'AUTH_REQUIRED_PENDING'",
    )
    suspend fun supersedePendingInboundContinuations(taskId: String, messageId: String): Int

    /**
     * Atomically records a one-shot local approval slot for the currently running turn and parks
     * the task. A decision later has to consume this exact continuation row.
     */
    @Transaction
    suspend fun pauseInboundTurnForAuthorization(
        continuation: A2AInboundContinuationEntity,
        statusMessage: String,
        updatedAt: Long,
        approvalSummaryJson: String?,
        approvalSummaryDigest: String?,
        approvalSummaryChars: Int,
    ): Boolean {
        if (continuation.outcome != "AUTH_REQUIRED_PENDING") return false
        val inserted = insertInboundContinuation(continuation)
        if (inserted <= 0) {
            // A retried pause may find its own durable slot already recorded.
            val existing = inboundContinuation(continuation.messageId)
            if (existing?.taskId != continuation.taskId || existing.outcome != "AUTH_REQUIRED_PENDING") return false
        }
        if (pauseInboundTaskForAuthorization(continuation.taskId, statusMessage, updatedAt) == 0) {
            if (inserted > 0) deleteInboundContinuation(continuation.messageId)
            return false
        }
        // The ledger is persisted in the same transaction as the AUTH_REQUIRED state and the
        // one-shot approval slot, so a visible approval always has a verifiable record.
        saveInboundApprovalSummary(
            taskId = continuation.taskId,
            json = approvalSummaryJson,
            digest = approvalSummaryDigest,
            chars = approvalSummaryChars,
            updatedAt = updatedAt,
        )
        supersedePendingInboundContinuations(continuation.taskId, continuation.messageId)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboundContinuation(continuation: A2AInboundContinuationEntity): Long

    @Query("SELECT * FROM a2a_inbound_continuations WHERE messageId = :messageId LIMIT 1")
    suspend fun inboundContinuation(messageId: String): A2AInboundContinuationEntity?

    @Query("SELECT * FROM a2a_inbound_continuations WHERE taskId = :taskId ORDER BY id")
    suspend fun inboundContinuations(taskId: String): List<A2AInboundContinuationEntity>

    @Query("DELETE FROM a2a_inbound_continuations WHERE messageId = :messageId")
    suspend fun deleteInboundContinuation(messageId: String): Int

    /** Atomically claims a continuation message and moves the waiting task into WORKING. */
    @Transaction
    suspend fun acceptInboundContinuation(
        continuation: A2AInboundContinuationEntity,
        updatedAt: Long,
    ): Boolean {
        if (insertInboundContinuation(continuation) <= 0) return false
        val transitioned = beginInboundContinuation(
            taskId = continuation.taskId,
            messageId = continuation.messageId,
            input = continuation.input,
            updatedAt = updatedAt,
        )
        if (transitioned == 0) {
            deleteInboundContinuation(continuation.messageId)
            return false
        }
        return true
    }

    /** Atomically records a continuation that cannot run until the peer re-authorizes. */
    @Transaction
    suspend fun rejectInboundContinuationForAuthorization(
        continuation: A2AInboundContinuationEntity,
        statusMessage: String,
        updatedAt: Long,
    ): Boolean {
        if (insertInboundContinuation(continuation) <= 0) return false
        val transitioned = requireInboundAuthorization(
            taskId = continuation.taskId,
            statusMessage = statusMessage,
            updatedAt = updatedAt,
        )
        if (transitioned == 0) {
            deleteInboundContinuation(continuation.messageId)
            return false
        }
        if (continuation.outcome == "AUTH_REQUIRED_PENDING") {
            supersedePendingInboundContinuations(continuation.taskId, continuation.messageId)
        }
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInboundArtifact(artifact: A2AInboundArtifactEntity)

    @Query("SELECT * FROM a2a_inbound_artifacts WHERE taskId = :taskId ORDER BY artifactId")
    suspend fun inboundArtifacts(taskId: String): List<A2AInboundArtifactEntity>
}
