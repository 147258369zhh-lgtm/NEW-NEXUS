package io.github.mangi.eta.agent.workflow

import android.content.Context
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.control.EtaControlException
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.RunWorkflowChange
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.zhh_lgtm.nexus.application.api.GoalTransition
import io.github.zhh_lgtm.nexus.application.api.WorkflowStateEngine
import io.github.zhh_lgtm.nexus.kernel.api.RunEventType
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.zhh_lgtm.nexus.kernel.api.GoalId
import io.github.zhh_lgtm.nexus.kernel.api.GoalCompletionNote
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Agent mutations share the Run approval policy, local fallback host and owning Run recorder. */
internal object WorkflowToolExecutor {
    /** The selected approval policy gates starting/resuming; completion follows verification. */
    private val approvalTransitions = setOf(GoalTransition.StartRunning, GoalTransition.Resume)
    private const val TERMINAL_CODE = "WORKFLOW_TERMINAL_SIDE_EFFECT_DENIED"
    private const val TERMINAL_MESSAGE = "目标已进入终态，本 Run 不再执行新的写入或副作用；只读操作仍可继续"
    private const val PAUSED_CODE = "WORKFLOW_PAUSED_SIDE_EFFECT_DENIED"
    private const val PAUSED_MESSAGE = "目标已暂停，本 Run 不再执行新的写入或副作用；只读查询、计划修订和本机确认继续仍可使用"
    private val terminalGoalStates = setOf("COMPLETED", "FAILED", "CANCELLED")

    fun execute(context: Context, scope: String, tool: String, arguments: JSONObject): JSONObject = try {
        executeOwned(context, scope, tool, arguments)
    } catch (failure: AgentRunCancelledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        val code = if (failure is EtaControlException) failure.code else failure.message
            ?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,100}")) } ?: "WORKFLOW_ERROR"
        JSONObject().put("ok", false).put("code", code).put("message", failure.message ?: code)
    }

    private fun executeOwned(context: Context, scope: String, tool: String, arguments: JSONObject): JSONObject {
        val store = GoalPlanNotepadStore.get(context)
        val action = arguments.optString("action", if (tool == "notepad_manage") "read" else "status")
        if (action == "revision") {
            return WorkflowRevisionStore(context).read(scope, kind(tool), arguments.getString("revision_ref")).put("ok", true)
        }
        val mutation = action !in setOf("read", "status")
        val controller = RuntimeInvocationScope.controller
        if (mutation) {
            check(controller != null) { "RUN_CONTEXT_REQUIRED" }
            val identity = controller.executionIdentity
            val effectiveGoalId = identity?.goalId ?: if (
                identity?.mode == RunMode.GOAL || identity?.source == RunSource.GOAL
            ) store.executionSnapshot(scope).goal?.goalId else null
            if (controller.isWorkflowTerminal || identity?.goalId?.let { store.isTerminalGoal(scope, it) } == true) {
                return JSONObject().put("ok", false).put("code", TERMINAL_CODE).put("message", TERMINAL_MESSAGE)
            }
            if (effectiveGoalId?.let { store.isPausedGoal(scope, it) } == true &&
                !workflowPausedAllows(tool, action, arguments)
            ) {
                return JSONObject().put("ok", false).put("code", PAUSED_CODE).put("message", PAUSED_MESSAGE)
            }
            controller.requireWorkflowRecorder()
        }
        val increase = if (tool == "goal_manage" && action == "increase_budget")
            ApprovedGoalBudgetIncrease.await(store, scope, arguments, checkNotNull(controller)) else null
        val transition = if (tool == "goal_manage" && action == "transition") when (arguments.optString("transition")) {
            "start_running" -> GoalTransition.StartRunning
            "resume" -> GoalTransition.Resume
            "complete" -> GoalTransition.Complete
            else -> null
        } else null
        val approval = transition?.let {
            val snapshot = store.executionSnapshot(scope)
            val goal = snapshot.goal ?: error("GOAL_NOT_FOUND")
            require(arguments.optString("goal_id") == goal.goalId.value) { "GOAL_SCOPE_MISMATCH" }
            val completedGoal = if (transition == GoalTransition.Complete) goal.copy(
                completionNote = parseCompletionNote(arguments),
            ) else goal
            // State machine and completion note are validated before any local confirmation or write.
            WorkflowStateEngine.transition(completedGoal, transition)
            if (transition == GoalTransition.StartRunning || transition == GoalTransition.Resume) {
                val plan = snapshot.plan
                check(plan != null && plan.steps.isNotEmpty() && plan.goalId == goal.goalId) { "GOAL_PLAN_REQUIRED" }
            }
            if (transition !in approvalTransitions) return@let null
            val fingerprint = store.fingerprint(snapshot)
            val details = JSONObject().put("goal_id", goal.goalId.value).put("objective", goal.objective)
                .put("state", goal.state.name).put("completion_criteria", JSONArray(goal.completionCriteria))
                .put("transition", arguments.optString("transition"))
                .put("goal_revision", goal.revision)
                .put("plan_revision", snapshot.plan?.revision ?: 0)
                .put("plan_steps", JSONArray(snapshot.plan?.steps.orEmpty().map { it.title }))
                .put("missing_noncritical_items", arguments.optJSONObject("completion")
                    ?.optJSONArray("missing_noncritical_items") ?: JSONArray())
                .put("completion_impact", arguments.optJSONObject("completion")?.optString("impact").orEmpty())
                .put("hard_rounds", goal.budget.hardRounds)
                .put("hard_tool_calls", goal.budget.hardToolCalls)
                .put("hard_duration_ms", goal.budget.hardDurationMs)
            val approved = runBlocking(RuntimeInvocationScope.coroutineContext()) {
                EtaAppControlConfirmation.awaitApproval(EtaControlCapabilityInfo(
                    id = "workflow.goal.${arguments.optString("transition")}",
                    title = "确认目标执行",
                    category = "workflow", summary = goal.objective, danger = EtaControlDanger.CONFIRM,
                    parameters = details, canRead = false,
                ), details, controller = controller)
            }
            check(approved) { "LOCAL_APPROVAL_REJECTED" }
            WorkflowLocalApproval(scope, fingerprint)
        }
        val operationId = UUID.randomUUID().toString()
        if (mutation) controller!!.recordWorkflowChange(RunWorkflowChange(operationId, RunEventType.WORKFLOW_UPDATE_STARTED, tool))
        val result = when (tool) {
            "goal_manage" -> if (increase != null) store.increaseBudget(increase) else store.goalManage(scope, arguments, approval)
            "plan_manage" -> store.planManage(scope, arguments)
            "notepad_manage" -> store.notepadManage(scope, arguments)
            else -> error("UNKNOWN_WORKFLOW_TOOL")
        }
        if (mutation && tool == "goal_manage" && result.optBoolean("ok") &&
            result.optJSONObject("goal")?.optString("state") in terminalGoalStates
        ) {
            controller?.markWorkflowTerminal()
        }
        return if (mutation) {
            // The JSON state is already committed. A ledger failure means uncertain recording,
            // never "nothing happened" and never permission to retry the mutation automatically.
            try {
                if (!result.optBoolean("ok")) {
                    controller!!.recordWorkflowChange(RunWorkflowChange(operationId, RunEventType.WORKFLOW_UPDATE_FAILED, tool))
                    return result
                }
                // Only goal creation introduces a new narrowing budget. State transitions and
                // revisions retain that goal's budget fields and must not erase an approved
                // extension of the currently running task.
                val receipt = receipt(context, scope, tool, result, operationId,
                    includeBudget = increase == null && (tool != "goal_manage" || action == "create"))
                controller!!.recordWorkflowChange(receipt)
                increase?.let(controller::increaseApprovedBudget)
                result.put("execution_reference", receipt.payloadRef)
            } catch (failure: Exception) {
                controller!!.abort(WorkflowMutationCommitException(failure))
                val applied = result.optBoolean("ok")
                result.put("ok", false).put("code", "WORKFLOW_RESULT_UNRECORDED")
                    .put("mutation_applied", applied)
                    .put("message", "执行记录未完成。请核对当前状态，勿重复执行。")
            }
        } else result
    }

    private fun receipt(context: Context, scope: String, tool: String, result: JSONObject, operationId: String, includeBudget: Boolean = true): RunWorkflowChange {
        val (type, body) = when (tool) {
            "goal_manage" -> result.getJSONObject("goal").let {
                RunEventType.GOAL_UPDATED to it
            }
            "plan_manage" -> result.getJSONObject("plan").let {
                RunEventType.PLAN_UPDATED to it
            }
            else -> JSONObject(result.toString()).also { it.remove("ok") }.let {
                RunEventType.NOTEPAD_UPDATED to it
            }
        }
        val reference = WorkflowRevisionStore(context).save(scope, kind(tool), body)
        val budget = if (type == RunEventType.GOAL_UPDATED && includeBudget) body.getJSONObject("budget").let { limits ->
            fun number(key: String): Long? = if (limits.isNull(key)) null else limits.getLong(key)
            RunBudget(number("soft_rounds")?.toInt(), number("hard_rounds")?.toInt(),
                number("soft_duration_ms"), number("hard_duration_ms"),
                number("soft_tool_calls")?.toInt(), number("hard_tool_calls")?.toInt(), number("cost_limit_micros"),
                limits.optString("cost_currency", "USD"))
        } else null
        return RunWorkflowChange(operationId, type, tool, reference, budget,
            body.optString("goal_id").takeIf { budget != null }?.let(::GoalId))
    }

    private fun kind(tool: String) = when (tool) {
        "goal_manage" -> "goal"
        "plan_manage" -> "plan"
        "notepad_manage" -> "notepad"
        else -> error("UNKNOWN_WORKFLOW_TOOL")
    }

    private fun workflowPausedAllows(tool: String, action: String, arguments: JSONObject): Boolean = when (tool) {
        "goal_manage" -> when (action) {
            "status", "revision", "revise", "pause", "cancel", "increase_budget" -> true
            "transition" -> arguments.optString("transition") in setOf("resume", "fail", "cancel")
            else -> false
        }
        "plan_manage" -> action in setOf("status", "revision", "replace")
        "notepad_manage" -> action in setOf("read", "revision")
        else -> false
    }

    private fun parseCompletionNote(arguments: JSONObject): GoalCompletionNote {
        val completion = arguments.optJSONObject("completion")
            ?: error("GOAL_COMPLETION_NOTE_REQUIRED")
        return GoalCompletionNote(
            coreCriteriaVerified = completion.optBoolean("core_criteria_verified", false),
            missingNoncriticalItems = completion.optJSONArray("missing_noncritical_items").stringList(),
            impact = completion.optString("impact").take(4_000),
            evidence = completion.optJSONArray("evidence").stringList(),
        )
    }
}

private fun org.json.JSONArray?.stringList(): List<String> = buildList {
    if (this@stringList == null) return@buildList
    for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

internal class WorkflowMutationCommitException(cause: Throwable) : RuntimeException("WORKFLOW_RESULT_UNRECORDED", cause)
