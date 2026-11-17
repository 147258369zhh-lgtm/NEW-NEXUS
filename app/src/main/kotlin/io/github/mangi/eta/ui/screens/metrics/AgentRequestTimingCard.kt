package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.AgentRequestTimingSummary
import io.github.mangi.eta.ui.components.NexusSettingsCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentRequestTimingCard(summaries: List<AgentRequestTimingSummary>) {
    val responseCount = summaries.sumOf { it.responseSamples }
    val firstTextCount = summaries.sumOf { it.firstTextSamples }
    val durationCount = summaries.sumOf { it.durationSamples }
    fun average(sum: Long, count: Long) = if(count > 0L) formatMilliseconds(sum / count) else "—"
    NexusSettingsCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("请求响应", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricValue("平均首字", average(summaries.sumOf { it.firstTextSumMs }, firstTextCount), Modifier.weight(1f))
                MetricValue("平均响应", average(summaries.sumOf { it.responseSumMs }, responseCount), Modifier.weight(1f))
                MetricValue("平均请求耗时", average(summaries.sumOf { it.durationSumMs }, durationCount), Modifier.weight(1f))
            }
            Text("有效样本：首字 $firstTextCount · 响应 $responseCount · 请求耗时 $durationCount",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
