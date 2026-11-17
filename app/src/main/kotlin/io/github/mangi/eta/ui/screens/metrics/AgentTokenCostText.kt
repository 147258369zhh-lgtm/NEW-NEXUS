package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentCostSummary
import io.github.mangi.eta.agent.runtime.AgentTokenCost
import io.github.mangi.eta.agent.runtime.AgentCnyExchangeStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
internal fun metricCostLabel(cost: AgentTokenCost, recordId: String? = null): String {
    val revision by AgentCnyExchangeStore.revision.collectAsState()
    val context = LocalContext.current.applicationContext
    val cny = androidx.compose.runtime.remember(cost, recordId, revision) {
        AgentCnyExchangeStore.convert(context, cost, recordId)
    }
    val amount = cny.amount ?: return "费用未报告"
    return cnyAmountText(amount) + if (cny.complete) " · 估算" else " · 部分估算"
}

@Composable
internal fun AgentTokenCostSummary(summary: AgentCostSummary) {
    Text(stringResource(R.string.metrics_cost_title), style = MiuixTheme.textStyles.body1)
    if (summary.currencies.isEmpty()) Text(stringResource(R.string.metrics_cost_unknown), style = MiuixTheme.textStyles.body2)
    val context = LocalContext.current.applicationContext
    val revision by AgentCnyExchangeStore.revision.collectAsState()
    val converted = androidx.compose.runtime.remember(summary, revision) {
        summary.currencies.map { AgentCnyExchangeStore.convert(context, AgentTokenCost(it.currency, it.amount,
            summary.completeModels == summary.totalModels && summary.totalModels > 0)) }
    }
    val amounts = converted.mapNotNull { it.amount }
    if (amounts.isNotEmpty()) Text(cnyAmountText(amounts.fold(BigDecimal.ZERO, BigDecimal::add)) +
        if (converted.all { it.complete }) " · 估算" else " · 部分估算", style = MiuixTheme.textStyles.body2)
    Text("人民币参考换算；原币金额保留在诊断导出中", style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text(stringResource(R.string.metrics_cost_coverage, summary.completeModels, summary.totalModels),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text(stringResource(R.string.metrics_cost_scope), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
