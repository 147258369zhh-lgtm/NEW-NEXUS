package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.capability.NexusToolEffectClassifier
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import io.github.zhh_lgtm.nexus.application.api.ExecutionStore
import io.github.zhh_lgtm.nexus.application.api.RunEventListener
import io.github.zhh_lgtm.nexus.kernel.api.*
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/** Commits semantic boundaries before the runtime is allowed to publish or execute past them. */
internal class NexusExecutionRecorder(
    private val store: ExecutionStore,
    val request: RunRequest,
    private val now: () -> Long = System::currentTimeMillis,
    private val listener: RunEventListener = RunEventListener {},
    private val onTerminal: () -> Unit = {},
    private val onBudgetRestricted: (RunId, String, RunBudget, GoalId) -> Unit = { _, _, _, _ -> },
    private val onBudgetIncreased: ((io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) -> Unit)? = null,
    private val onResourceBudgetExtended: ((ApprovedRunBudgetExtension) -> Unit)? = null,
) {
    private val requestAttempts = mutableMapOf<Int, Int>()
    private var inputMessages=emptyList<AgentModelClient.ConversationMessage>()
    private var omittedInputImages=0
    private var approvalBinding: AutoCloseable? = null
    private var pauseBinding: AutoCloseable? = null
    private var workflowBinding: AutoCloseable? = null
    private var inputBinding: AutoCloseable? = null
    private var controller: AgentRunController? = null
    private val pendingApprovals=linkedSetOf<String>()
    private val pendingInputs=linkedSetOf<String>()
    private val pendingEvents=linkedSetOf<String>()
    private val pendingEffectReviews=linkedSetOf<String>()
    private var paused = false
    private var pauseRevision = 0
    private var finished=false
    init {
        store.create(request, now())
        publish(store.append(request.runId, ExecutionFact("run:admitted", RunEventType.RUN_ADMITTED, now(), state = RunState.ADMITTED)))
    }

    @Synchronized fun record(event: AgentEvent) {
        if (finished) return
        val fact = fact(event) ?: return
        publish(store.append(request.runId, fact))
    }

    fun start() {
        publish(store.append(request.runId, ExecutionFact("run:started", RunEventType.RUN_STARTED, now(), state = RunState.RUNNING)))
    }

    @Synchronized fun bindController(controller: AgentRunController) {
        check(approvalBinding == null && !finished) { "RECORDER_ALREADY_BOUND_OR_FINISHED" }
        controller.bindExecutionIdentity(request)
        this.controller = controller
        approvalBinding=controller.observeApprovals(::approvalChanged)
        pauseBinding=controller.observePause(::pauseChanged)
        workflowBinding=controller.observeWorkflow(::workflowChanged)
        inputBinding=controller.observeUserInput(::inputChanged)
        controller.bindBudgetIncreaseOwner { approval ->
            if (onBudgetIncreased != null) onBudgetIncreased.invoke(approval) else increaseBudget(approval)
        }
        controller.bindResourceBudgetOwner { approval ->
            if (onResourceBudgetExtended != null) onResourceBudgetExtended.invoke(approval) else extendResourceBudget(approval)
        }
    }

    private fun workflowChanged(change: RunWorkflowChange) {
        synchronized(this) {
            check(!finished) { "RUN_ALREADY_TERMINAL" }
            val success = when (change.type) {
                RunEventType.WORKFLOW_UPDATE_STARTED -> null
                RunEventType.WORKFLOW_UPDATE_FAILED -> false
                else -> true
            }
            publish(store.append(request.runId, ExecutionFact("workflow:${change.id}:${change.type.name}", change.type, now(),
                itemId = ItemId(identity("workflow:${change.id}")), toolName = change.toolName,
                success = success, payloadRef = change.payloadRef, budgetLimit = change.budgetLimit, goalId = change.goalId)))
        }
        // Do not hold one Run's lock while propagating a limit into sibling recorders.
        change.budgetLimit?.let { onBudgetRestricted(request.runId, change.id, it, checkNotNull(change.goalId)) }
    }

    @Synchronized fun restrictBudget(changeId: String, limit: RunBudget, goalId: GoalId) {
        if (finished) return
        val current = store.find(request.runId) ?: error("RUN_NOT_FOUND")
        val effective = current.request.budget.intersect(limit)
        if (current.request.budget != effective || current.request.goalId == null) {
            publish(store.append(request.runId, ExecutionFact("budget:$changeId", RunEventType.BUDGET_RESTRICTED, now(),
                budgetLimit = limit, goalId = goalId)))
        }
        controller?.applyCommittedBudgetLimit(effective)
    }

    fun abort(failure: RuntimeException) { controller?.abort(failure) }

    @Synchronized fun extendResourceBudget(approval: ApprovedRunBudgetExtension) {
        if (finished) return
        val current = store.find(request.runId) ?: error("RUN_NOT_FOUND")
        if (current.request.runId == approval.targetRunId) {
            check(current.request.budget == approval.before) { "BUDGET_CHANGED_SINCE_APPROVAL" }
        }
        val next = current.request.budget.withIncreasedGoalBudget(approval.before, approval.after)
        if (next == current.request.budget) return
        publish(store.increaseBudget(request.runId, current.request.budget,
            ExecutionFact("budget-extension:${approval.capabilityId}", RunEventType.BUDGET_INCREASED, now(),
                payloadRef = "nexus-budget://${approval.approvalRunId.value}", budgetLimit = next, goalId = current.request.goalId),
            approval.approvalRunId, approval.capabilityId))
        controller?.applyCommittedBudgetIncrease(next)
    }

    @Synchronized fun increaseBudget(approval: io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) {
        if (finished) return
        val current = store.find(request.runId) ?: error("RUN_NOT_FOUND")
        check(current.request.goalId == approval.goalId) { "GOAL_SCOPE_MISMATCH" }
        val next = current.request.budget.withIncreasedGoalBudget(approval.before, approval.after)
        publish(store.increaseBudget(request.runId, current.request.budget,
            ExecutionFact("budget-increase:${approval.capabilityId}", RunEventType.BUDGET_INCREASED, now(),
                payloadRef = "nexus-budget://${approval.approvalRunId.value}", budgetLimit = next, goalId = approval.goalId),
            approval.approvalRunId, approval.capabilityId))
        controller?.applyCommittedBudgetIncrease(next)
    }

    private fun activeState(approvals: Set<String> = pendingApprovals, isPaused: Boolean = paused,
        inputs: Set<String> = pendingInputs, events: Set<String> = pendingEvents,
        reviews: Set<String> = pendingEffectReviews): RunState = when {
        approvals.isNotEmpty() -> RunState.WAITING_AUTH
        isPaused -> RunState.PAUSED
        inputs.isNotEmpty() -> RunState.WAITING_USER
        events.isNotEmpty() -> RunState.WAITING_EVENT
        reviews.isNotEmpty() -> RunState.VERIFYING
        else -> RunState.RUNNING
    }

    @Synchronized private fun eventWaitChanged(event: AgentEvent.AccessibilityWaiting) {
        check(!finished) { "RUN_ALREADY_TERMINAL" }
        val id = "event:accessibility:${event.outage}"
        val requested = event.phase == io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.WAITING
        val next = pendingEvents.toMutableSet().apply { if (requested) add(id) else remove(id) }
        val errorCode = when (event.phase) {
            io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.TIMED_OUT -> "EVENT_ACCESSIBILITY_TIMED_OUT"
            io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.CANCELLED -> "EVENT_ACCESSIBILITY_CANCELLED"
            else -> null
        }
        publish(store.append(request.runId, ExecutionFact(
            key = "$id:${if (requested) "required" else "resolved"}",
            type = if (requested) RunEventType.EVENT_WAIT_REQUIRED else RunEventType.EVENT_WAIT_RESOLVED,
            createdAt = now(),
            itemId = ItemId(identity(id)),
            state = activeState(events = next),
            success = if (requested) null else event.phase == io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.RESTORED,
            errorCode = errorCode,
            payloadRef = "nexus-event://accessibility-service/${event.outage}",
        )))
        pendingEvents.clear(); pendingEvents.addAll(next)
    }

    @Synchronized private fun inputChanged(change: RunInputChange) {
        check(!finished) { "RUN_ALREADY_TERMINAL" }
        val requested = change.phase == RunInputPhase.REQUESTED
        val next = pendingInputs.toMutableSet().apply { if (requested) add(change.id) else remove(change.id) }
        publish(store.append(request.runId, ExecutionFact("input:${change.id}:${change.phase.name}",
            if (requested) RunEventType.USER_INPUT_REQUIRED else RunEventType.USER_INPUT_RESOLVED, now(),
            itemId = ItemId(identity("input:${change.id}")), state = activeState(inputs = next),
            success = if (requested) null else change.phase == RunInputPhase.ANSWERED,
            errorCode = if (requested || change.phase == RunInputPhase.ANSWERED) null else "INPUT_${change.phase.name}",
            payloadRef = change.payloadRef)))
        pendingInputs.clear(); pendingInputs.addAll(next)
    }

    @Synchronized private fun pauseChanged(isPaused: Boolean) {
        if (finished || paused == isPaused) return
        val revision = pauseRevision + 1
        publish(store.append(request.runId, ExecutionFact(
            "pause:$revision", if (isPaused) RunEventType.RUN_PAUSED else RunEventType.RUN_RESUMED, now(),
            state = activeState(isPaused = isPaused),
        )))
        pauseRevision = revision
        paused = isPaused
    }

    @Synchronized private fun approvalChanged(change: RunApprovalChange) {
        if(finished) return
        val requested=change.phase == RunApprovalPhase.REQUESTED
        val next=pendingApprovals.toMutableSet().apply { if(requested) add(change.id) else remove(change.id) }
        val fact=ExecutionFact("approval:${change.id}:${if(requested) "requested" else "resolved"}",
            if(requested) RunEventType.APPROVAL_REQUIRED else RunEventType.APPROVAL_RESOLVED,now(),
            itemId=ItemId(identity("approval:${change.id}")),
            state=activeState(approvals=next),
            toolName=change.toolName.take(256),success=if(requested) null else change.phase == RunApprovalPhase.APPROVED,
            errorCode=if(requested || change.phase == RunApprovalPhase.APPROVED) null else "APPROVAL_${change.phase.name}")
        publish(store.append(request.runId,fact))
        pendingApprovals.clear();pendingApprovals.addAll(next)
    }

    @Synchronized fun prepareInput(history: List<AgentModelClient.ConversationMessage>, prompt: String, imageCount: Int) {
        require(imageCount >= 0)
        inputMessages=history+AgentModelClient.ConversationMessage(role="user",content=prompt)
        omittedInputImages=imageCount
        saveCheckpoint(emptyList(),null)
    }

    @Synchronized fun saveCheckpoint(transcript: List<AgentModelClient.ConversationMessage>, context: AgentContextSnapshot?) {
        val run = store.find(request.runId) ?: error("RUN_NOT_FOUND")
        val cleanContext=context?.copy(messages=AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(context.messages)))
        val body=JSONObject().put("format","nexus-runtime-checkpoint").put("schema_version",1)
            .put("run_id",request.runId.value)
            .put("effective_budget", run.request.budget.let { budget -> JSONObject()
                .put("soft_rounds", budget.softRounds).put("hard_rounds", budget.hardRounds)
                .put("soft_tool_calls", budget.softToolCalls).put("hard_tool_calls", budget.hardToolCalls)
                .put("soft_duration_ms", budget.softDurationMs).put("hard_duration_ms", budget.hardDurationMs)
                .put("cost_limit_micros", budget.costLimitMicros).put("cost_currency", budget.costCurrency) })
            .put("input",JSONArray(AgentConversationCodec.encodeTranscriptForStorage(inputMessages)))
            .put("omitted_input_images",omittedInputImages)
            .put("transcript",JSONArray(AgentConversationCodec.encodeTranscriptForStorage(transcript)))
            .put("context",cleanContext?.let { JSONObject(it.encode()) } ?: JSONObject.NULL)
            .toString()
        store.saveCheckpoint(request.runId,run.lastSequence,body,now())
    }

    @Synchronized fun finish(result: AgentRuntimeWire.RunResult, cancelled: Boolean) {
        if(finished) return
        if (!cancelled && result.ok && hasPendingEffectReview()) {
            try { throw EffectReviewPendingException() } finally {
                finished=true
                approvalBinding?.close()
                pauseBinding?.close()
                workflowBinding?.close()
                inputBinding?.close()
                onTerminal()
            }
        }
        val state = when { cancelled -> RunState.CANCELLED; result.ok -> RunState.SUCCEEDED; else -> RunState.FAILED }
        try { publish(store.append(request.runId, ExecutionFact("run:terminal", when (state) {
            RunState.CANCELLED -> RunEventType.RUN_CANCELLED
            RunState.SUCCEEDED -> RunEventType.RUN_SUCCEEDED
            else -> RunEventType.RUN_FAILED
        }, now(), state = state, errorCode = if (!result.ok && !cancelled) result.failureCode
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) } ?: "RUNTIME_FAILED" else null))) }
        finally {
            finished=true
            approvalBinding?.close()
            pauseBinding?.close()
            workflowBinding?.close()
            inputBinding?.close()
            onTerminal()
        }
    }

    private fun fact(event: AgentEvent): ExecutionFact? {
        fun make(key: String, type: RunEventType, round: Int? = null, item: String? = null,
            state: RunState? = null, name: String? = null, success: Boolean? = null) = ExecutionFact(
            key, type, now(), round?.let { TurnId(identity("turn:$it")) }, item?.let { ItemId(identity(it)) }, state,
            name?.take(256), success,
        )
        return when (event) {
            is AgentEvent.RunStarted -> make("run:started", RunEventType.RUN_STARTED, state = RunState.RUNNING)
            is AgentEvent.RoundStarted -> make("turn:${event.round}", RunEventType.TURN_STARTED, event.round)
            is AgentEvent.ProviderRequestStarted -> {
                val attempt = (requestAttempts[event.round] ?: 0) + 1
                requestAttempts[event.round] = attempt
                make("request:${event.round}:$attempt", RunEventType.MODEL_REQUEST_STARTED, event.round)
            }
            is AgentEvent.ProviderResponseStarted -> make("response:${event.round}:${requestAttempts[event.round] ?: 0}", RunEventType.MODEL_RESPONSE_STARTED, event.round)
            is AgentEvent.AssistantReceived -> make("assistant:${event.round}", RunEventType.ASSISTANT_ITEM_COMPLETED,
                event.round, "assistant:${event.round}")
            is AgentEvent.ToolStarted -> make("tool:start:${event.round}:${event.toolCallId}", RunEventType.TOOL_ITEM_STARTED,
                event.round, "tool:${event.round}:${event.toolCallId}", name = event.name)
            is AgentEvent.ToolFinished -> {
                val itemKey = "tool:${event.round}:${event.toolCallId}"
                val review = event.effectUnknown && NexusToolEffectClassifier.classify(event.name) != EffectClass.READ
                if (review) pendingEffectReviews.add(itemKey) else pendingEffectReviews.remove(itemKey)
                make("tool:finish:${event.round}:${event.toolCallId}", RunEventType.TOOL_ITEM_COMPLETED,
                    event.round, itemKey, state = if (review || pendingEffectReviews.isNotEmpty()) activeState() else null,
                    name = event.name, success = if (review) null else event.success)
                    .copy(payloadRef = event.artifactId?.let { "nexus-artifact://$it" } ?: event.workflowReference)
            }
            is AgentEvent.HostedToolStarted -> make("hosted:start:${event.round}:${event.toolCallId}", RunEventType.TOOL_ITEM_STARTED,
                event.round, "hosted:${event.round}:${event.toolCallId}", name = event.name)
            is AgentEvent.HostedToolFinished -> make("hosted:finish:${event.round}:${event.toolCallId}", RunEventType.TOOL_ITEM_COMPLETED,
                event.round, "hosted:${event.round}:${event.toolCallId}", name = event.name, success = event.success)
            is AgentEvent.ContextCompaction -> {
                val operation=identity("compact:${event.operationId}")
                make("compact:$operation:${event.phase}", RunEventType.CONTEXT_COMPACTED,
                    item = "compact:$operation", success = when(event.phase) {
                        AgentEvent.ContextCompaction.PHASE_COMPLETED -> true
                        AgentEvent.ContextCompaction.PHASE_STARTED -> null
                        else -> false
                    })
            }
            is AgentEvent.AccessibilityWaiting -> {
                eventWaitChanged(event)
                null
            }
            // Per-character output remains in the content/checkpoint store, never the fact ledger.
            else -> null
        }
    }

    private fun identity(value: String): String = NexusExecutionIds.item(request.runId, value)

    private fun publish(commit: io.github.zhh_lgtm.nexus.application.api.ExecutionCommit) {
        // A disconnected observer must not turn a committed effect into a failed operation.
        runCatching { listener.onEvent(commit.event) }
    }

    private fun hasPendingEffectReview(): Boolean {
        val persisted = (store as? io.github.mangi.eta.data.repository.NexusExecutionStore)
            ?.pendingEffectReviewCount(request.runId)
        return persisted?.let { it > 0 } ?: pendingEffectReviews.isNotEmpty()
    }

}

internal class EffectReviewPendingException : IllegalStateException(
    "外部操作结果未确认，已保留“待核对”记录；禁止自动重放，请在本机核对后显式重新发起。",
)
