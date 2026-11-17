package io.github.zhh_lgtm.nexus.kernel.api

enum class RunSource {
    CHAT,
    MEETING_HOST,
    MEETING_PARTICIPANT,
    SUBAGENT,
    GOAL,
    A2A_INBOUND,
    A2A_OUTBOUND,
    AUTOMATION,
    NOTE_CONSOLIDATION,
    PLUGIN,
}

enum class RunMode {
    CHAT,
    PLAN,
    GOAL,
    MEETING_HOST,
    MEETING_PARTICIPANT,
    TASK,
    A2A,
}

enum class RunState {
    CREATED,
    ADMITTED,
    RUNNING,
    WAITING_USER,
    WAITING_AUTH,
    WAITING_EVENT,
    PAUSED,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class RunEventType {
    RUN_CREATED,
    RUN_ADMITTED,
    RUN_STARTED,
    RUN_PAUSED,
    RUN_RESUMED,
    TURN_STARTED,
    MODEL_REQUEST_STARTED,
    MODEL_RESPONSE_STARTED,
    ASSISTANT_ITEM_STARTED,
    ASSISTANT_ITEM_COMPLETED,
    TOOL_ITEM_STARTED,
    TOOL_ITEM_COMPLETED,
    /** A tool whose result could not be confirmed; the original item stays auditable but cannot be replayed automatically. */
    TOOL_EFFECT_REVIEW_RESOLVED,
    USER_INPUT_REQUIRED,
    USER_INPUT_RESOLVED,
    APPROVAL_REQUIRED,
    APPROVAL_RESOLVED,
    EVENT_WAIT_REQUIRED,
    EVENT_WAIT_RESOLVED,
    CONTEXT_COMPACTED,
    WORKFLOW_UPDATE_STARTED,
    WORKFLOW_UPDATE_FAILED,
    BUDGET_RESTRICTED,
    BUDGET_INCREASED,
    MODEL_BUDGET_RESERVED,
    MODEL_BUDGET_SETTLED,
    GOAL_UPDATED,
    PLAN_UPDATED,
    NOTEPAD_UPDATED,
    HOST_CHANGED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_INTERRUPTED,
    EVENT_OVERFLOW,
}

data class RunBudget(
    val softRounds: Int? = null,
    val hardRounds: Int? = null,
    val softDurationMs: Long? = null,
    val hardDurationMs: Long? = null,
    val softToolCalls: Int? = null,
    val hardToolCalls: Int? = null,
    val costLimitMicros: Long? = null,
    val costCurrency: String = "USD",
) {
    init {
        require(softRounds == null || softRounds > 0)
        require(hardRounds == null || hardRounds > 0)
        require(softRounds == null || hardRounds == null || softRounds <= hardRounds)
        require(softDurationMs == null || softDurationMs > 0)
        require(hardDurationMs == null || hardDurationMs > 0)
        require(softDurationMs == null || hardDurationMs == null || softDurationMs <= hardDurationMs)
        require(softToolCalls == null || softToolCalls > 0)
        require(hardToolCalls == null || hardToolCalls > 0)
        require(softToolCalls == null || hardToolCalls == null || softToolCalls <= hardToolCalls)
        require(costLimitMicros == null || costLimitMicros > 0)
        require(costCurrency.matches(Regex("[A-Z]{3}")))
    }
}

data class RunRequest(
    val runId: RunId,
    val source: RunSource,
    val mode: RunMode,
    val hostId: HostId,
    val sessionId: SessionId? = null,
    val projectId: String? = null,
    val parentRunId: RunId? = null,
    val taskId: TaskId? = null,
    val goalId: GoalId? = null,
    val budget: RunBudget = RunBudget(),
)

data class ExecutionEvent(
    val runId: RunId,
    val sequence: Long,
    val type: RunEventType,
    val source: RunSource,
    val hostId: HostId,
    val createdAt: Long,
    val turnId: TurnId? = null,
    val itemId: ItemId? = null,
    val state: RunState? = null,
    val durationMs: Long? = null,
    val payloadRef: String? = null,
    val artifactId: ArtifactId? = null,
    val errorCode: String? = null,
    val schemaVersion: Int = NexusProduct.DATA_SCHEMA_VERSION,
    val budgetLimit: RunBudget? = null,
    val goalId: GoalId? = null,
) {
    init {
        require(sequence >= 0) { "sequence must be non-negative" }
        require(createdAt >= 0) { "createdAt must be non-negative" }
        require(durationMs == null || durationMs >= 0) { "durationMs must be non-negative" }
    }
}

enum class ItemKind {
    MODEL_REQUEST,
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    APPROVAL,
    USER_INPUT_REQUEST,
    EVENT_WAIT,
    CONTEXT_COMPACTION,
    PLAN_REVISION,
    NOTEPAD_REVISION,
    GOAL_UPDATE,
    SUBAGENT,
    ARTIFACT,
}

data class ExecutionItem(
    val itemId: ItemId,
    val runId: RunId,
    val turnId: TurnId?,
    val kind: ItemKind,
    val state: RunState?,
    val startedAt: Long,
    val completedAt: Long? = null,
    val payloadRef: String? = null,
    val artifactId: ArtifactId? = null,
    val schemaVersion: Int = NexusProduct.DATA_SCHEMA_VERSION,
)
