package io.github.mangi.eta.fork.tasks

/** Durable lifecycle for one local automation attempt. */
internal enum class AgentTaskState(val wireValue: String, val terminal: Boolean = false) {
    QUEUED("QUEUED"),
    LEASED("LEASED"),
    RUNNING("RUNNING"),
    WAITING_INPUT("WAITING_INPUT"),
    WAITING_AUTH("WAITING_AUTH"),
    SUCCEEDED("SUCCEEDED", terminal = true),
    FAILED("FAILED", terminal = true),
    CANCELED("CANCELED", terminal = true);

    companion object {
        fun fromWire(value: String): AgentTaskState = entries.firstOrNull { it.wireValue == value }
            ?: error("Unknown AgentTaskState: $value")
    }
}

internal fun AgentTaskState.canTransitionTo(target: AgentTaskState): Boolean = when (this) {
    AgentTaskState.QUEUED -> target in setOf(
        AgentTaskState.LEASED,
        AgentTaskState.FAILED,
        AgentTaskState.CANCELED,
    )
    AgentTaskState.LEASED -> target in setOf(
        AgentTaskState.QUEUED,
        AgentTaskState.RUNNING,
        AgentTaskState.FAILED,
        AgentTaskState.CANCELED,
    )
    AgentTaskState.RUNNING -> target in setOf(
        AgentTaskState.WAITING_INPUT,
        AgentTaskState.WAITING_AUTH,
        AgentTaskState.SUCCEEDED,
        AgentTaskState.FAILED,
        AgentTaskState.CANCELED,
    )
    AgentTaskState.WAITING_INPUT,
    AgentTaskState.WAITING_AUTH,
    -> target in setOf(AgentTaskState.RUNNING, AgentTaskState.CANCELED)
    AgentTaskState.SUCCEEDED,
    AgentTaskState.FAILED,
    AgentTaskState.CANCELED,
    -> false
}

internal enum class AgentTaskRunKind {
    CHAT,
    MEETING,
    A2A,
    MAINTENANCE,
}

internal enum class AgentTaskAttentionKind {
    INPUT_REQUIRED,
    AUTH_REQUIRED,
    APPROVAL_REQUIRED,
    RETRY_REQUIRED,
    MISSED_RUN_DECISION,
}

internal enum class AgentTaskAttentionStatus {
    OPEN,
    RESOLVED,
    DISMISSED,
}

internal data class AgentTaskExecutionRequest(
    val taskRunId: String,
    /** Per-dispatch Runtime identity; use this id for Runtime cancellation and result correlation. */
    val runtimeRunId: String,
    val definitionId: String,
    val kind: AgentTaskRunKind,
    val targetId: String,
    val prompt: String,
    val participantConfigJson: String,
    val policyJson: String,
    val scheduledFor: Long,
    val attempt: Int,
    val continuationInput: String? = null,
)

internal sealed interface AgentTaskExecutionResult {
    data class Succeeded(val resultRef: String, val output: String = "") : AgentTaskExecutionResult
    /** No task effect has started; the same durable run can wait for device conditions. */
    data class Deferred(val delayMillis: Long = 60000) : AgentTaskExecutionResult
    /**
     * Runners set [retryable] only when they can prove that repeating the request cannot duplicate
     * an external side effect. Unknown failures remain terminal by default.
     */
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : AgentTaskExecutionResult
    data class InputRequired(val prompt: String) : AgentTaskExecutionResult
    data class AuthRequired(val reason: String) : AgentTaskExecutionResult
}

/** Thin seam around AgentRuntime; this phase does not duplicate the runtime executor. */
internal fun interface AgentTaskRunner {
    suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult
}

internal fun interface AgentTaskRuntimeCanceller {
    suspend fun cancel(runtimeRunId: String)
}

internal data class AgentTaskLeaseRecoverySummary(
    val requeuedLeasedRuns: Int,
    val failedRunningRuns: Int,
)

internal data class AgentTaskMaterializationSummary(
    val queuedRuns: Int,
    val duplicateSlots: Int,
    val attentionRuns: Int,
    val exhaustedSchedules: Int,
)

internal data class AgentTaskSchedulerTickSummary(
    val recovery: AgentTaskLeaseRecoverySummary,
    val materialization: AgentTaskMaterializationSummary,
    val executedRuns: Int,
)

internal data class AgentTaskDraft(
    val title: String,
    val runKind: AgentTaskRunKind,
    val targetId: String,
    val promptTemplate: String,
    val participantConfigJson: String = "{}",
    val policyJson: String = "{}",
    val schedule: AgentScheduleRule,
    val missedRunPolicy: MissedRunPolicy,
    val paused: Boolean = false,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(targetId.isNotBlank()) { "targetId must not be blank" }
        require(promptTemplate.isNotBlank()) { "promptTemplate must not be blank" }
    }
}

internal data class AgentScheduledTaskIds(
    val definitionId: String,
    val scheduleId: String,
)

internal data class AgentTaskClaim(
    val runId: String,
    val definitionId: String,
    val owner: String,
    val leaseExpiresAt: Long,
)
