package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentCostSummary
import io.github.mangi.eta.agent.runtime.AgentTokenCost
import java.math.BigDecimal
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun metricMoney(amount: BigDecimal): String {
    // Never display a tiny positive estimate as free; JSON always retains the exact decimal.
    if (amount.signum() > 0 && amount < BigDecimal("0.00000001")) return amount.stripTrailingZeros().toPlainString()
    return NumberFormat.getNumberInstance().apply { maximumFractionDigits = 8 }.format(amount)
}

@Composable
internal fun metricCostLabel(cost: AgentTokenCost): String {
    val amount = cost.amount ?: return stringResource(R.string.metrics_cost_unknown)
    return stringResource(if (cost.complete) R.string.metrics_cost_estimate else R.string.metrics_cost_partial,
        cost.currency.orEmpty(), metricMoney(amount))
}

@Composable
internal fun AgentTokenCostSummary(summary: AgentCostSummary) {
    Text(stringResource(R.string.metrics_cost_title), style = MiuixTheme.textStyles.body1)
    if (summary.currencies.isEmpty()) Text(stringResource(R.string.metrics_cost_unknown), style = MiuixTheme.textStyles.body2)
    summary.currencies.forEach { cost ->
        Text(stringResource(if (summary.completeModels == summary.totalModels && summary.totalModels > 0)
            R.string.metrics_cost_estimate else R.string.metrics_cost_partial, cost.currency, metricMoney(cost.amount)),
            style = MiuixTheme.textStyles.body2)
    }
    Text(stringResource(R.string.metrics_cost_coverage, summary.completeModels, summary.totalModels),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text(stringResource(R.string.metrics_cost_scope), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
