package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface RunModelBudgetPort {
    fun reserve(request: ProviderRequest, embeddedLocal: Boolean): RunModelBudgetTicket
    fun settle(ticket: RunModelBudgetTicket, usage: AgentTokenUsage?, successful: Boolean)
}

internal data class RunModelBudgetTicket(val record: ModelBudgetRecord, val price: AgentTokenPrice?, val local: Boolean,
    val outputTokenCeiling: Int? = null)

/** One coordinator per Runtime API; SQLite owns aggregate counts across the complete Run family. */
internal class RunModelBudgetCoordinator(
    private val store: ModelBudgetStore,
    private val priceResolver: (AgentModelClient.ModelConfig) -> AgentTokenPrice?,
    private val abortFamily: (RunId, RuntimeException) -> Unit,
) {
    private val lock = ReentrantLock()
    private val settled = lock.newCondition()
    private val prices = mutableMapOf<String, AgentTokenPrice?>()
    private var renewing = false

    fun bind(controller: AgentRunController, runId: RunId) {
        controller.bindModelBudget(object : RunModelBudgetPort {
            override fun reserve(request: ProviderRequest, embeddedLocal: Boolean): RunModelBudgetTicket {
                val cancellation = controller.register { lock.withLock { settled.signalAll() } }
                try {
                    return lock.withLock {
                        while (true) {
                            controller.throwIfStopped()
                            if (renewing) { settled.await(); continue }
                            val totals = store.modelBudgetTotals(runId)
                            val config = request.effectiveConfig
                            val key = "${totals.familyRunId.value}\u0000${config.providerId}\u0000${config.model}"
                            val price = if (embeddedLocal) AgentTokenPrice("embedded-free", totals.currency, "0", "0", "0", "0", 0)
                                else if (prices.containsKey(key)) prices[key] else priceResolver(config).also { prices[key] = it }
                            val constrained = totals.costLimitMicros != null && !embeddedLocal
                            if (constrained && (config.usesHostedWebSearch ||
                                config.authMode != io.github.mangi.eta.data.model.ProviderAuthModes.API_KEY)) {
                                throw ModelBudgetDenied("MODEL_COST_BOUND_UNSUPPORTED")
                            }
                            val descriptor = ModelBudgetRequest(UUID.randomUUID().toString(), runId, config.providerId.take(256),
                                config.model.take(256), request.purpose.name, price?.currency, price?.revision,
                                price?.input, price?.output, price?.cacheRead, price?.cacheWrite,
                                ModelCostReservation.micros(request, price, embeddedLocal))
                            try {
                                return@withLock RunModelBudgetTicket(store.reserveModelRequest(descriptor, System.currentTimeMillis()), price, embeddedLocal,
                                    config.knownContextWindow.takeIf { constrained })
                            } catch (failure: ModelBudgetDenied) {
                                if (failure.code == "MODEL_COST_PENDING") {
                                    settled.await()
                                } else if (failure.code in setOf("RUN_TOTAL_MODEL_REQUESTS_EXCEEDED", "RUN_COST_BUDGET_EXCEEDED", "RUN_COST_RESERVATION_EXCEEDED") &&
                                    controller.executionIdentity != null && store is ExecutionStore) {
                                    val family = store.find(totals.familyRunId) ?: throw failure
                                    val member = store.find(runId) ?: throw failure
                                    val target = if (failure.code == "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED" &&
                                        (family.request.budget.hardRounds == null || family.request.budget.hardRounds?.let { totals.requests < it } == true) ||
                                        failure.code != "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED" && family.request.budget.costLimitMicros == null) member else family
                                    renewing = true
                                    lock.unlock()
                                    val approved = try {
                                        RunBudgetConfirmation.awaitModel(controller, target.request.budget, failure.code, totals.requests,
                                            totals.knownCostMicros, descriptor.reservedCostMicros, target.request.runId)
                                    } finally {
                                        lock.lock()
                                        renewing = false
                                        settled.signalAll()
                                    }
                                    if (!approved) throw AgentModelFailure("RUN_BUDGET_EXTENSION_NOT_APPROVED", false,
                                        "本次未追加资源额度，原任务与已完成结果已保留。")
                                } else throw failure
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                } catch (failure: AgentRunCancelledException) {
                    throw failure
                } catch (failure: Exception) {
                    val blocked = budgetFailure(failure)
                    abortFamily(runId, blocked)
                    throw blocked
                } finally { cancellation.close() }
            }

            override fun settle(ticket: RunModelBudgetTicket, usage: AgentTokenUsage?, successful: Boolean) {
                try {
                    val normalized = usage?.let(AgentRequestUsage::from) ?: AgentRequestUsage()
                    val estimate = if (ticket.local) AgentTokenCost(ticket.price?.currency, BigDecimal.ZERO, true)
                        else AgentTokenCostEstimator.estimateRequest(normalized, ticket.price)
                    val micros = estimate.amount?.multiply(BigDecimal("1000000"))?.setScale(0, RoundingMode.CEILING)?.longValueExact()
                    store.settleModelRequest(runId, ModelBudgetSettlement(ticket.record.request.requestId, micros,
                        estimate.complete, successful, normalized.uncachedInputTokens, normalized.billableOutputTokens,
                        normalized.cacheReadTokens, normalized.cacheWriteTokens), System.currentTimeMillis())
                    if (ticket.outputTokenCeiling != null && ticket.record.request.reservedCostMicros?.let { micros != null && micros > it } == true) {
                        throw ModelBudgetDenied("MODEL_COST_RESERVATION_OVERRUN")
                    }
                } catch (failure: Exception) {
                    val blocked = budgetFailure(failure)
                    abortFamily(runId, blocked)
                    throw blocked
                } finally { lock.withLock { settled.signalAll() } }
            }
        })
    }

    private fun budgetFailure(failure: Exception): AgentModelFailure {
        if (failure is AgentModelFailure) return failure
        val code = (failure as? ModelBudgetDenied)?.code ?: "MODEL_BUDGET_PERSISTENCE_FAILED"
        val message = when (code) {
            "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED" -> "本次任务及子任务已用完模型请求总额度，已停止后续请求。"
            "MODEL_COST_PRICE_REQUIRED" -> "本次任务设置了费用额度，请先为所用模型配置计费价格。"
            "MODEL_COST_CURRENCY_MISMATCH" -> "模型价格与费用预算的币种不同，请统一币种后继续。"
            "MODEL_COST_UNVERIFIED" -> "此前请求的用量或费用尚不能确认，已停止继续扣费。"
            "RUN_COST_BUDGET_EXCEEDED" -> "本次任务已达到累计费用额度，已停止后续模型请求。"
            "MODEL_COST_RESERVATION_REQUIRED" -> "费用预算需要完整模型价格和上下文长度，无法确认单次费用上限，未发送请求。"
            "RUN_COST_RESERVATION_EXCEEDED" -> "剩余费用额度不足以预留下一次请求，请调整预算或单次模型上下文长度。"
            "MODEL_COST_BOUND_UNSUPPORTED" -> "此连接或服务端工具的费用无法按 Token 限定，未发送本次限额请求。"
            "MODEL_COST_RESERVATION_OVERRUN" -> "服务返回的费用超过请求预留，已保存真实用量并停止后续请求，请核对模型价格和上下文长度。"
            else -> "模型请求预算无法可靠保存或核对，本次请求未继续。"
        }
        return AgentModelFailure(code, false, message, failure)
    }
}
