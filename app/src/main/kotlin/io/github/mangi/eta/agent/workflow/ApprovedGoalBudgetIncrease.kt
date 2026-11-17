package io.github.mangi.eta.agent.workflow

import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID

/** Issued only after the owning Run's selected approval policy admits the exact budget change. */
internal class ApprovedGoalBudgetIncrease private constructor(
    val scope: String, val stateHash: String, val goalId: GoalId, val before: RunBudget, val after: RunBudget,
    val approvalRunId: RunId, val capabilityId: String,
) {
    companion object {
        fun await(store: GoalPlanNotepadStore, scope: String, arguments: JSONObject,
            controller: AgentRunController): ApprovedGoalBudgetIncrease {
            val snapshot = store.executionSnapshot(scope)
            val goal = snapshot.goal ?: error("GOAL_NOT_FOUND")
            require(goal.goalId.value == arguments.getString("goal_id")) { "GOAL_SCOPE_MISMATCH" }
            check(goal.state !in setOf(GoalState.COMPLETED, GoalState.CANCELLED, GoalState.FAILED)) { "GOAL_ALREADY_TERMINAL" }
            val run = checkNotNull(controller.executionIdentity) { "RUN_CONTEXT_REQUIRED" }
            val target = target(goal.budget, arguments)
            require(target != goal.budget) { "BUDGET_NOT_CHANGED" }
            goal.budget.withIncreasedGoalBudget(goal.budget, target)
            val id = "workflow.goal.budget:${UUID.randomUUID()}"
            val hash = store.fingerprint(snapshot)
            val details = JSONObject().put("目标", goal.objective.take(180))
                .put("模型请求（软/硬）", "${goal.budget.softRounds}/${goal.budget.hardRounds} → ${target.softRounds}/${target.hardRounds}")
                .put("工具调用（软/硬）", "${goal.budget.softToolCalls}/${goal.budget.hardToolCalls} → ${target.softToolCalls}/${target.hardToolCalls}")
                .put("时长（软/硬，分钟）", "${goal.budget.softDurationMs?.div(60_000)}/${goal.budget.hardDurationMs?.div(60_000)} → ${target.softDurationMs?.div(60_000)}/${target.hardDurationMs?.div(60_000)}")
                .put("费用（百万分之一 ${target.costCurrency}）", "${goal.budget.costLimitMicros ?: "未限制"} → ${target.costLimitMicros ?: "未限制"}")
                .put("累计规则", "保留已用次数、时间和费用，子任务更严格的独立限制保留")
            check(runBlocking(RuntimeInvocationScope.coroutineContext()) {
                EtaAppControlConfirmation.awaitApproval(EtaControlCapabilityInfo(id = id, title = "追加任务预算",
                    category = "runtime_budget", summary = goal.objective, danger = EtaControlDanger.CONFIRM,
                    parameters = details, canRead = false), details, controller = controller)
            }) { "LOCAL_APPROVAL_REJECTED" }
            return ApprovedGoalBudgetIncrease(scope, hash, goal.goalId, goal.budget, target, run.runId, id)
        }

        private fun target(current: RunBudget, args: JSONObject): RunBudget {
            fun value(key: String, old: Long?, max: Long): Long? {
                if (!args.has(key)) return old
                val number = args.get(key) as? Number ?: error("INVALID_GOAL_BUDGET")
                val next = number.toLong()
                require(next in 1..max && number.toDouble() == next.toDouble()) { "INVALID_GOAL_BUDGET" }
                return next
            }
            require(!args.has("cost_currency") || args.getString("cost_currency") == current.costCurrency) { "COST_BUDGET_CURRENCY_MISMATCH" }
            return RunBudget(value("soft_rounds", current.softRounds?.toLong(), 10_000)?.toInt(),
                value("hard_rounds", current.hardRounds?.toLong(), 10_000)?.toInt(),
                value("soft_duration_ms", current.softDurationMs, Int.MAX_VALUE.toLong()),
                value("hard_duration_ms", current.hardDurationMs, Int.MAX_VALUE.toLong()),
                value("soft_tool_calls", current.softToolCalls?.toLong(), 100_000)?.toInt(),
                value("hard_tool_calls", current.hardToolCalls?.toLong(), 100_000)?.toInt(),
                value("cost_limit_micros", current.costLimitMicros, Int.MAX_VALUE.toLong()), current.costCurrency)
        }
    }
}
