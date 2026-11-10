package io.github.mangi.eta.agent.runtime

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** Immutable manual rates per million tokens. History stores this value, never a live price reference. */
internal data class AgentTokenPrice(
    val revision: String,
    val currency: String,
    val input: String,
    val output: String,
    val cacheRead: String?,
    val cacheWrite: String?,
    val capturedAt: Long,
) {
    companion object {
        private val DECIMAL = Regex("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,8})?")
        private val MAX_RATE = BigDecimal("1000000")

        fun create(currency: String, input: String, output: String, cacheRead: String = "", cacheWrite: String = "",
            now: Long = System.currentTimeMillis()): AgentTokenPrice {
            val code = currency.trim().uppercase(Locale.ROOT)
            require(code.matches(Regex("[A-Z]{3}")))
            requireNotNull(runCatching { Currency.getInstance(code) }.getOrNull())
            require(now >= 0)
            fun rate(value: String): String {
                val text = value.trim()
                require(DECIMAL.matches(text))
                val amount = BigDecimal(text)
                require(amount <= MAX_RATE)
                return amount.stripTrailingZeros().toPlainString()
            }
            return AgentTokenPrice(UUID.randomUUID().toString(), code, rate(input), rate(output),
                cacheRead.trim().takeIf(String::isNotEmpty)?.let(::rate),
                cacheWrite.trim().takeIf(String::isNotEmpty)?.let(::rate), now)
        }
    }
}

internal data class AgentTokenCost(val currency: String?, val amount: BigDecimal?, val complete: Boolean)
internal data class AgentCurrencyCost(val currency: String, val amount: BigDecimal)
internal data class AgentCostSummary(val currencies: List<AgentCurrencyCost>, val completeModels: Int, val totalModels: Int)

/** Only reported, normalized counters are charged; omitted rates and counters stay unknown. */
internal object AgentTokenCostEstimator {
    private val MILLION = BigDecimal("1000000")

    fun estimate(metrics: AgentRunMetrics, price: AgentTokenPrice?): AgentTokenCost {
        if (price == null) return AgentTokenCost(null, null, false)
        return runCatching {
            var known = false
            var complete = metrics.rounds > 0 && metrics.billingReportedRounds == metrics.rounds &&
                metrics.requestAttempts <= metrics.rounds && metrics.retries == 0
            var amount = BigDecimal.ZERO
            listOf(metrics.uncachedInputTokens to price.input, metrics.billableOutputTokens to price.output,
                metrics.cacheReadTokens to price.cacheRead, metrics.cacheWriteTokens to price.cacheWrite).forEach { (tokens, rate) ->
                if (tokens == null || tokens < 0) complete = false
                else if (tokens == 0L) known = true // A reported zero needs no rate.
                else if (rate == null) complete = false
                else {
                    val value = BigDecimal(rate)
                    require(value.signum() >= 0)
                    amount += BigDecimal.valueOf(tokens).multiply(value).divide(MILLION)
                    known = true
                }
            }
            AgentTokenCost(price.currency, amount.takeIf { known }, complete && known)
        }.getOrElse { AgentTokenCost(price.currency, null, false) }
    }

    fun estimateRequest(usage: AgentRequestUsage, price: AgentTokenPrice?): AgentTokenCost {
        if (price == null || !usage.reported) return AgentTokenCost(price?.currency, null, false)
        return runCatching {
            var amount = BigDecimal.ZERO
            var known = false
            var complete = true
            listOf(usage.uncachedInputTokens to price.input, usage.billableOutputTokens to price.output,
                usage.cacheReadTokens to price.cacheRead, usage.cacheWriteTokens to price.cacheWrite).forEach { (tokens, rate) ->
                if (tokens == null || tokens < 0) complete = false
                else if (tokens == 0L) known = true
                else if (rate == null) complete = false
                else {
                    val value = BigDecimal(rate)
                    require(value.signum() >= 0)
                    amount += BigDecimal.valueOf(tokens).multiply(value).divide(MILLION)
                    known = true
                }
            }
            AgentTokenCost(price.currency, amount.takeIf { known }, complete && known)
        }.getOrElse { AgentTokenCost(price.currency, null, false) }
    }

    fun summarize(models: List<AgentMetricModelEntity>, expectedModels: Int = models.size): AgentCostSummary {
        val costs = models.map { estimate(it.metrics, it.price) }
        val grouped = costs.filter { it.currency != null && it.amount != null }.groupBy { requireNotNull(it.currency) }
        return AgentCostSummary(grouped.toSortedMap().map { (currency, values) ->
            AgentCurrencyCost(currency, values.fold(BigDecimal.ZERO) { sum, cost -> sum + requireNotNull(cost.amount) })
        }, costs.count { it.complete }, maxOf(expectedModels, costs.size))
    }
}
