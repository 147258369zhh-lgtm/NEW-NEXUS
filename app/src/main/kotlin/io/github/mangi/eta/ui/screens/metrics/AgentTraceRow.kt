package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.AgentMetricTraceEntity
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun AgentTraceRow(step: AgentMetricTraceEntity, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.End) {
            Text("%.2fs".format(step.elapsedMs / 1000.0), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Box(Modifier.padding(top = 5.dp).size(7.dp).background(if (step.success == false || step.kind == "failed") MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary.copy(alpha = .6f), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            val summary = listOfNotNull(step.round?.let { "第 $it 轮" }, step.label.takeIf { it.isNotBlank() }, step.success?.let { if (it) "成功" else "失败" }).joinToString(" · ")
            if (summary.isNotBlank()) Text(summary, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
