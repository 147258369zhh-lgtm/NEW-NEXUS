package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.ProviderRequest
import java.math.BigDecimal
import java.math.RoundingMode

/** Uses the configured model window as a declared ceiling, never the prompt's token estimate. */
internal object ModelCostReservation {
    fun micros(request: ProviderRequest, price: AgentTokenPrice?, local: Boolean): Long? {
        if (local) return 0
        if (price == null) return null
        val window = request.effectiveConfig.knownContextWindow ?: return null
        val rates = listOf(price.input, price.output, price.cacheRead, price.cacheWrite)
        if (rates.any { it == null }) return null
        // Cache writes may be charged in addition to ordinary input. Reserve both conservatively.
        val input = maxOf(BigDecimal(price.input), BigDecimal(price.cacheRead)) + BigDecimal(price.cacheWrite)
        val totalRate = input + BigDecimal(price.output)
        return try { totalRate.multiply(BigDecimal(window)).setScale(0, RoundingMode.CEILING).longValueExact() }
        catch (_: ArithmeticException) { null }
    }
}
