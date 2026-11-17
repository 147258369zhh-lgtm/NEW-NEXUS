package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.model.RunBudgetDimension
import io.github.mangi.eta.agent.model.SoftRunBudgetLimit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import java.util.UUID

/** Exact, finite change issued only after the existing approval gate admitted it. */
internal class ApprovedRunBudgetExtension private constructor(
    val before: RunBudget, val after: RunBudget, val approvalRunId: RunId, val targetRunId: RunId, val capabilityId: String,
) {
    companion object {
        internal suspend fun await(controller: AgentRunController, before: RunBudget,
            after: RunBudget, details: JSONObject, targetRunId: RunId? = null): ApprovedRunBudgetExtension? {
            val run = controller.executionIdentity ?: return null
            val id = "run_budget:extend:${UUID.randomUUID()}"
            val approved = EtaAppControlConfirmation.awaitApproval(EtaControlCapabilityInfo(
                id = id, title = "追加本次任务资源额度", category = "runtime_budget",
                summary = "追加一段有限额度，保留原任务与全部累计消耗；下次耗尽时再次确认。",
                danger = EtaControlDanger.CONFIRM, parameters = details, canRead = false,
            ), details, controller = controller)
            controller.throwIfStopped()
            return if (approved) ApprovedRunBudgetExtension(before, after, run.runId, targetRunId ?: run.runId, id) else null
        }
    }
}

/** Extends finite resource limits in place; this never changes tool permissions or task state. */
internal object RunBudgetConfirmation {
    fun awaitModel(controller: AgentRunController, current: RunBudget, code: String,
        requests: Long, spentMicros: Long, reservationMicros: Long?, targetRunId: RunId): Boolean = runBlocking {
        val next = if (code == "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED") {
            extension(current, RunBudgetDimension.ROUNDS, requests)
        } else {
            val old = current.costLimitMicros ?: return@runBlocking false
            val step = maxOf(1_000_000L, reservationMicros ?: 0L)
            val base = maxOf(old, spentMicros)
            current.copy(costLimitMicros = if (base >= Long.MAX_VALUE - step) Long.MAX_VALUE else base + step)
        }
        if (next == current) return@runBlocking false
        val details = JSONObject().put("已用模型请求", requests).put("已用费用（百万分之一 ${current.costCurrency}）", spentMicros)
            .put("本次请求费用预留", reservationMicros ?: JSONObject.NULL)
            .put("模型请求总上限", "${current.hardRounds ?: "未限制"} → ${next.hardRounds ?: "未限制"}")
            .put("累计费用上限", "${current.costLimitMicros ?: "未限制"} → ${next.costLimitMicros ?: "未限制"}")
            .put("累计规则", "保留原任务和已用请求、Token、时间与费用；不重放已执行操作")
        val approval = ApprovedRunBudgetExtension.await(controller, current, next, details, targetRunId) ?: return@runBlocking false
        controller.extendResourceBudget(approval)
        true
    }

    fun await(controller: AgentRunController, limit: SoftRunBudgetLimit): Boolean = runBlocking {
        val label = when (limit.dimension) {
            RunBudgetDimension.ROUNDS -> "模型回合"
            RunBudgetDimension.TOOL_CALLS -> "工具调用"
            RunBudgetDimension.DURATION -> "运行时长（毫秒）"
        }
        val details = JSONObject().put("budget", label).put("observed", limit.observed)
            .put("soft_limit", limit.softLimit).put("hard_limit", limit.hardLimit ?: JSONObject.NULL)
        limit.budget?.let { current ->
            val next = extension(current, limit.dimension, limit.observed)
            if (next == current) return@runBlocking false
            details.put("next_soft_limit", when (limit.dimension) {
                RunBudgetDimension.ROUNDS -> next.softRounds
                RunBudgetDimension.TOOL_CALLS -> next.softToolCalls
                RunBudgetDimension.DURATION -> next.softDurationMs
            }).put("next_hard_limit", when (limit.dimension) {
                RunBudgetDimension.ROUNDS -> next.hardRounds
                RunBudgetDimension.TOOL_CALLS -> next.hardToolCalls
                RunBudgetDimension.DURATION -> next.hardDurationMs
            }).put("累计规则", "已用次数、时间、模型用量与费用继续累计；不重新执行原任务")
            val approval = ApprovedRunBudgetExtension.await(controller, current, next, details) ?: return@runBlocking false
            controller.extendResourceBudget(approval)
            return@runBlocking true
        }
        EtaAppControlConfirmation.awaitApproval(EtaControlCapabilityInfo(
            id = "run_budget:${limit.dimension.name.lowercase()}", title = "继续本次任务",
            category = "runtime_budget", summary = "已达到${label}软预算，继续后仍受原硬上限约束。",
            danger = EtaControlDanger.CONFIRM, parameters = details, canRead = false,
        ), details, controller = controller)
    }

    internal fun extension(current: RunBudget, dimension: RunBudgetDimension, observed: Long): RunBudget {
        fun add(old: Long?, step: Long, ceiling: Long): Long? = old?.let {
            val base = maxOf(it, observed)
            if (base >= ceiling - step) ceiling else base + step
        }
        return when (dimension) {
            RunBudgetDimension.ROUNDS -> current.copy(
                softRounds = add(current.softRounds?.toLong(), 48, Int.MAX_VALUE.toLong())?.toInt(),
                hardRounds = add(current.hardRounds?.toLong(), 64, Int.MAX_VALUE.toLong())?.toInt())
            RunBudgetDimension.TOOL_CALLS -> current.copy(
                softToolCalls = add(current.softToolCalls?.toLong(), 96, Int.MAX_VALUE.toLong())?.toInt(),
                hardToolCalls = add(current.hardToolCalls?.toLong(), 128, Int.MAX_VALUE.toLong())?.toInt())
            RunBudgetDimension.DURATION -> current.copy(
                softDurationMs = add(current.softDurationMs, 30 * 60_000L, Long.MAX_VALUE),
                hardDurationMs = add(current.hardDurationMs, 60 * 60_000L, Long.MAX_VALUE))
        }
    }
}
