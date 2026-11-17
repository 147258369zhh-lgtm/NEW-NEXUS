package io.github.mangi.eta.fork.tasks

/** Durable lifecycle for one local automation attempt. */
internal enum class AgentTaskState(val wireValue: String, val terminal: Boolean = false) {
    QUEUED("QUEUED"),
    LEASED("LEASED"),
    RUNNING("RUNNING"),
    WAITING_INPUT("WAITING_INPUT"),
    WAITING_AUTH("WAITING_AUTH"),
    /**
     * The remote A2A task is still running. The logical run keeps its remote task binding and is
     * claimed again at [AgentTaskRunEntity.scheduledFor] to query that same remote task.
     */
    WAITING_REMOTE("WAITING_REMOTE"),
    A2A_REVIEW("A2A_REVIEW"),
    REVIEW_DECIDED("REVIEW_DECIDED", terminal = true),
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
        AgentTaskState.WAITING_REMOTE,
        AgentTaskState.SUCCEEDED,
        AgentTaskState.FAILED,
        AgentTaskState.CANCELED,
        AgentTaskState.A2A_REVIEW,
    )
    AgentTaskState.WAITING_REMOTE -> target in setOf(
        AgentTaskState.LEASED,
        AgentTaskState.RUNNING,
        AgentTaskState.FAILED,
        AgentTaskState.CANCELED,
        AgentTaskState.A2A_REVIEW,
    )
    AgentTaskState.A2A_REVIEW -> target in setOf(AgentTaskState.CANCELED, AgentTaskState.REVIEW_DECIDED)
    AgentTaskState.WAITING_INPUT,
    AgentTaskState.WAITING_AUTH,
    -> target in setOf(AgentTaskState.RUNNING, AgentTaskState.CANCELED)
    AgentTaskState.REVIEW_DECIDED -> false
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
    val executionOwner: String = "",
    val runStartedAt: Long? = null,
    val workflowCheckpoint: WorkflowCheckpoint = WorkflowCheckpoint(),
)

/** Identity of the definition revision that produced a workflow cursor. */
internal fun AgentTaskDefinitionEntity.workflowFingerprint(): String {
    val canonical = org.json.JSONObject()
        .put("id", id)
        .put("kind", runKind.name)
        .put("targetId", targetId)
        .put("promptTemplate", promptTemplate)
        .put("participantConfigJson", participantConfigJson)
        .put("policyJson", policyJson)
        .toString()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** A durable cursor at a step boundary. The current step is committed only after it settles. */
internal data class WorkflowCheckpoint(
    val nextStepIndex: Int = 0,
    val nextIteration: Int = 1,
    val previous: String = "",
    val variables: Map<String, String> = emptyMap(),
    val waitUntil: Long? = null,
    val stepStarted: Boolean = false,
    val workflowStartedAt: Long? = null,
    /** Binds the cursor to the definition identity and version that produced it. */
    val definitionFingerprint: String? = null,
) {
    fun json(): String = org.json.JSONObject()
        .put("nextStepIndex", nextStepIndex)
        .put("nextIteration", nextIteration)
        .put("previous", previous.take(MAX_PREVIOUS_CHARS))
        .put("variables", org.json.JSONObject(variables.toSortedMap()))
        .put("waitUntil", waitUntil ?: org.json.JSONObject.NULL)
        .put("stepStarted", stepStarted)
        .put("workflowStartedAt", workflowStartedAt ?: org.json.JSONObject.NULL)
        .put("definitionFingerprint", definitionFingerprint ?: org.json.JSONObject.NULL)
        .toString()

    /** Drops a cursor that was bound to a different definition or that cannot be parsed. */
    fun matchesDefinition(fingerprint: String): Boolean =
        definitionFingerprint == null || definitionFingerprint == fingerprint

    /** Only a brand-new run may persist a cursor without a definition binding. */
    val isPristine: Boolean get() = this == WorkflowCheckpoint()

    companion object {
        private const val MAX_PREVIOUS_CHARS = 12_000
        private const val MAX_VARIABLES = 32
        private const val MAX_VARIABLE_VALUE_CHARS = 12_000
        private const val MAX_FINGERPRINT_CHARS = 128
        /** Same shape as workflow step ids; `task` and `definition` are reserved variables. */
        private val VARIABLE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
        private val RESERVED_VARIABLES = setOf("task", "definition", "previous")
        private val CHECKPOINT_KEYS = setOf(
            "nextStepIndex",
            "nextIteration",
            "previous",
            "variables",
            "waitUntil",
            "stepStarted",
            "workflowStartedAt",
            "definitionFingerprint",
        )

        fun parse(raw: String): WorkflowCheckpoint {
            if (raw.isBlank()) return WorkflowCheckpoint()
            val value = org.json.JSONObject(raw)
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key in CHECKPOINT_KEYS) { "未知工作流断点字段：$key" }
            }
            val rawVariables = if (!value.has("variables")) org.json.JSONObject() else
                value.get("variables") as? org.json.JSONObject ?: error("工作流变量不是对象")
            require(rawVariables.length() <= MAX_VARIABLES) { "工作流变量过多" }
            val variables = buildMap {
                rawVariables.keys().forEach { key ->
                    require(key.matches(VARIABLE_NAME) && key !in RESERVED_VARIABLES) { "工作流变量名无效" }
                    val item = rawVariables.get(key) as? String ?: error("工作流变量值不是字符串")
                    require(item.length <= MAX_VARIABLE_VALUE_CHARS) { "工作流变量值过长" }
                    put(key, item)
                }
            }

            fun intField(name: String, default: Int, range: IntRange): Int {
                if (!value.has(name)) return default
                val number = value.get(name) as? Number ?: error("$name 类型无效")
                require(number.toDouble() == number.toLong().toDouble()) { "$name 不是整数" }
                val long = number.toLong()
                require(long in range.first.toLong()..range.last.toLong()) { "$name 超出范围" }
                return long.toInt()
            }

            fun longField(name: String, minimum: Long = Long.MIN_VALUE): Long? {
                if (!value.has(name) || value.isNull(name)) return null
                val number = value.get(name) as? Number ?: error("$name 类型无效")
                require(number.toDouble() == number.toLong().toDouble()) { "$name 不是整数" }
                return number.toLong().also { require(it >= minimum) { "$name 超出范围" } }
            }

            fun stringField(name: String, default: String): String =
                if (!value.has(name) || value.isNull(name)) default else
                    value.get(name) as? String ?: error("$name 类型无效")

            fun booleanField(name: String, default: Boolean): Boolean =
                if (!value.has(name)) default else value.get(name) as? Boolean ?: error("$name 类型无效")

            val fingerprint = stringField("definitionFingerprint", "").takeIf(String::isNotBlank)
            require(fingerprint == null || fingerprint.length <= MAX_FINGERPRINT_CHARS) {
                "工作流定义指纹过长"
            }
            return WorkflowCheckpoint(
                nextStepIndex = intField("nextStepIndex", 0, 0..20),
                nextIteration = intField("nextIteration", 1, 1..5),
                previous = stringField("previous", "").take(MAX_PREVIOUS_CHARS),
                variables = variables,
                waitUntil = longField("waitUntil", minimum = 0L),
                stepStarted = booleanField("stepStarted", false),
                workflowStartedAt = longField("workflowStartedAt", minimum = 0L),
                definitionFingerprint = fingerprint,
            )
        }
    }
}

internal sealed interface AgentTaskExecutionResult {
    data class Succeeded(val resultRef: String, val output: String = "", val runtimeResultId: String? = null) : AgentTaskExecutionResult
    /**
     * No task effect has started; the same durable run can wait for device conditions. A workflow
     * WAIT sets [wakeAtEpochMillis] so the original absolute deadline is used for the next claim.
     */
    data class Deferred(
        val delayMillis: Long = 60000,
        val reason: String? = null,
        val wakeAtEpochMillis: Long? = null,
    ) : AgentTaskExecutionResult
    /**
     * Runners set [retryable] only when they can prove that repeating the request cannot duplicate
     * an external side effect. Unknown failures remain terminal by default.
     */
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
        val runtimeResultId: String? = null,
    ) : AgentTaskExecutionResult
    data class InputRequired(val prompt: String) : AgentTaskExecutionResult
    data class AuthRequired(val reason: String) : AgentTaskExecutionResult
    /**
     * The remote task exists and is bound to this logical run but is still working. Nothing is
     * re-sent: the same run is claimed again after [delayMillis] and queries that remote task.
     */
    data class WaitingRemote(
        val delayMillis: Long,
        val status: String,
        val detail: String? = null,
    ) : AgentTaskExecutionResult
}

/** Thin seam around AgentRuntime; this phase does not duplicate the runtime executor. */
internal fun interface AgentTaskRunner {
    suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult

    /** Called only after the task state or settled workflow checkpoint is durable. */
    suspend fun acknowledge(result: AgentTaskExecutionResult) { }
}

internal fun interface AgentTaskRuntimeCanceller {
    suspend fun cancel(runtimeRunId: String)
}

/** Cancels the remote work bound to a logical run; failures are best effort after the local fence. */
internal fun interface AgentTaskRemoteCanceller {
    suspend fun cancel(agentId: String, remoteTaskId: String)
}

internal data class AgentTaskLeaseRecoverySummary(
    val requeuedLeasedRuns: Int,
    val failedRunningRuns: Int,
    val resumedRemoteRuns: Int = 0,
    val resumedWorkflowRuns: Int = 0,
    val recoveredReceiptRuns: Int = 0,
)

/**
 * Durable binding between one logical [AgentTaskRunEntity] and the remote A2A work it started.
 * [messageId] identifies the message that was sent for this logical run; dispatch runtime ids are
 * never used as identity because they change on every process restart.
 */
internal data class AgentTaskA2ABinding(
    val agentId: String? = null,
    val taskId: String? = null,
    val contextId: String? = null,
    val messageId: String? = null,
    val messageSeq: Int = 0,
) {
    val hasDispatchIntent: Boolean get() = messageId != null
    val isBound: Boolean get() = taskId != null
}

internal fun AgentTaskRunEntity.a2aBinding(): AgentTaskA2ABinding = AgentTaskA2ABinding(
    agentId = a2aAgentId?.takeIf(String::isNotBlank),
    taskId = a2aTaskId?.takeIf(String::isNotBlank),
    contextId = a2aContextId?.takeIf(String::isNotBlank),
    messageId = a2aMessageId?.takeIf(String::isNotBlank),
    messageSeq = a2aMessageSeq,
)

/** Stable A2A ids derived from the logical run id, so restarts never mint a second identity. */
internal object AgentTaskA2AIds {
    const val INITIAL_MESSAGE_SEQ: Int = 0

    fun messageId(taskRunId: String, seq: Int): String = "a2a-run:$taskRunId:$seq"

    fun localTaskId(taskRunId: String): String = "a2a-run:$taskRunId"
}

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
