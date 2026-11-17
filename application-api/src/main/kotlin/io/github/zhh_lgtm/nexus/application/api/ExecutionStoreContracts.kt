package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.*

/** Semantic fact keys survive redelivery; streaming chunks belong in content storage. */
data class ExecutionFact(
    val key: String,
    val type: RunEventType,
    val createdAt: Long,
    val turnId: TurnId? = null,
    val itemId: ItemId? = null,
    val state: RunState? = null,
    val toolName: String? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val payloadRef: String? = null,
    val budgetLimit: RunBudget? = null,
    val goalId: GoalId? = null,
) {
    init {
        require(key.isNotBlank() && key.length <= 1024)
        require(createdAt >= 0)
        require(toolName == null || toolName.length <= 256)
        require(errorCode == null || errorCode.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(payloadRef == null || payloadRef.matches(Regex("[A-Za-z0-9._:/@#-]{1,1024}")))
        // A finite allowance can be renewed for an ordinary Run before it creates a goal.
        // The execution owner still verifies the exact approved budget and Run family.
        require((budgetLimit == null) == (goalId == null) ||
            type == RunEventType.BUDGET_INCREASED && budgetLimit != null)
        require(budgetLimit == null || type in setOf(RunEventType.GOAL_UPDATED, RunEventType.BUDGET_RESTRICTED, RunEventType.BUDGET_INCREASED))
        require(type !in setOf(RunEventType.BUDGET_RESTRICTED, RunEventType.BUDGET_INCREASED) || budgetLimit != null)
    }
}

data class ExecutionCommit(val eventId: String, val event: ExecutionEvent, val fact: ExecutionFact)

data class ExecutionRunSnapshot(
    val request: RunRequest,
    val state: RunState,
    val lastSequence: Long,
    val checkpointSequence: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val admittedRequest: RunRequest = request,
)

/** Private checkpoint content is stored separately from the metadata-only event rows. */
data class ExecutionCheckpoint(
    val runId: RunId,
    val throughSequence: Long,
    val sha256: String,
    val createdAt: Long,
    val payload: String,
) {
    init {
        require(throughSequence >= 0 && createdAt >= 0)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }

    override fun toString(): String = "ExecutionCheckpoint(runId=$runId, throughSequence=$throughSequence, sha256=$sha256, createdAt=$createdAt, payloadChars=${payload.length})"
}

/** A single owner commits an event and its state projection in one storage transaction. */
interface ExecutionStore {
    fun create(request: RunRequest, now: Long): ExecutionRunSnapshot
    fun append(runId: RunId, fact: ExecutionFact): ExecutionCommit
    fun increaseBudget(runId: RunId, expected: RunBudget, fact: ExecutionFact, approvalRunId: RunId,
        approvalCapability: String): ExecutionCommit = error("BUDGET_INCREASE_OWNER_UNAVAILABLE")
    fun find(runId: RunId): ExecutionRunSnapshot?
    fun timeline(runId: RunId, afterSequence: Long = -1, limit: Int = 200): List<ExecutionCommit>
    fun unfinished(): List<ExecutionRunSnapshot>
    fun items(runId: RunId): List<ExecutionItem>
    fun saveCheckpoint(runId: RunId, throughSequence: Long, payload: String, now: Long): ExecutionCheckpoint
    fun checkpoint(runId: RunId): ExecutionCheckpoint?
}
