package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.AgentMetricDay
import io.github.mangi.eta.ui.components.NexusSettingsCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

@Composable
internal fun AgentAdaptiveTrendCard(
    timeline: MetricTimeline,
    tokenMode: Boolean,
    onSelect: ((LocalDate, LocalDate) -> Unit)? = null,
    onSelectWindow: ((Long, Long) -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val buckets = timeline.buckets
    val maximum = buckets.maxOfOrNull { if (tokenMode) maxOf(it.input ?: 0L, it.output ?: 0L) else it.runs.toLong() }?.coerceAtLeast(1L) ?: 1L
    NexusSettingsCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tokenMode) "Token 使用趋势" else "运行轨迹", Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Text(timeline.granularity, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            }
            if (buckets.isEmpty()) {
                Text("暂无可定位到时间的记录", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            } else {
                Text("${buckets.first().start.toLocalDate()} — ${buckets.last().endExclusive.minusNanos(1).toLocalDate()}", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                if (tokenMode) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetricTrendLegend("输入", Color(0xFF4C7CF3)); MetricTrendLegend("输出", Color(0xFF2AA978))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buckets.forEach { bucket ->
                        val value = if(tokenMode) bucket.tokens?.let(::compactMetric) ?: if (bucket.activeWithoutSample) "·" else "" else bucket.runs.toString()
                        val description = if (tokenMode) "${bucket.start} 至 ${bucket.endExclusive}，Token 汇总 ${bucket.tokens ?: "未报告"}，输入 ${bucket.input ?: "未报告"}，输出 ${bucket.output ?: "未报告"}"
                            else "${bucket.start} 至 ${bucket.endExclusive}，${bucket.runs} 次运行开始"
                        Column(Modifier.width(48.dp).semantics { contentDescription = description }.then(
                            if(onSelect == null && onSelectWindow == null) Modifier else Modifier.clickable {
                                if(onSelectWindow != null) { val zone = java.time.ZoneId.systemDefault(); onSelectWindow(bucket.start.atZone(zone).toInstant().toEpochMilli(), bucket.endExclusive.atZone(zone).toInstant().toEpochMilli()) }
                                else onSelect?.invoke(bucket.start.toLocalDate(), bucket.endExclusive.minusNanos(1).toLocalDate())
                            }
                        ), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1)
                            Row(Modifier.height(104.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                                val values = if (tokenMode) listOf(bucket.input to Color(0xFF4C7CF3), bucket.output to Color(0xFF2AA978)) else listOf(bucket.runs.toLong() to colors.primary)
                                values.forEach { (count, color) ->
                                    if(count == null && bucket.runs > 0) Text("—", modifier = Modifier.width(if(tokenMode) 15.dp else 28.dp), style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                                    else if (count == null) Spacer(Modifier.width(if(tokenMode) 15.dp else 28.dp))
                                    else Box(Modifier.width(if(tokenMode) 15.dp else 28.dp).height((104f * count / maximum).coerceAtLeast(2f).dp)
                                        .background(if(count == 0L) color.copy(alpha = .35f) else color, RoundedCornerShape(5.dp)))
                                }
                            }
                            Text(bucket.label, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary, maxLines = 1)
                        }
                    }
                }
                if (tokenMode && buckets.any { it.incomplete || it.tokens == null && it.runs > 0 }) Text("未报告的用量保留为空；部分记录仅展示已知用量。", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                if (tokenMode) Text("用量为整次运行汇总，归入开始时间；期间未分时采样。", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                if (!tokenMode) Text("运行次数按开始时间统计。", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                if (onSelect != null || onSelectWindow != null) Text("点选时间段查看真实运行记录", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

private fun dailyTimeline(days: List<AgentMetricDay>) = MetricTimeline("按天", days.map { day ->
    MetricTimelineBucket(day.date.atStartOfDay(), day.date.plusDays(1).atStartOfDay(), day.runs,
        if(day.runs == 0) 0L else day.inputTokens, if(day.runs == 0) 0L else day.outputTokens, if(day.runs == 0) 0L else listOfNotNull(day.inputTokens, day.outputTokens).takeIf { it.isNotEmpty() }?.sum(), day.incompleteUsage > 0)
})

@Composable internal fun AgentMetricsTrendCard(days: List<AgentMetricDay>) = AgentAdaptiveTrendCard(dailyTimeline(days), false)
@Composable internal fun AgentTokenUsageTrendCard(days: List<AgentMetricDay>) = AgentAdaptiveTrendCard(dailyTimeline(days), true)

@Composable private fun MetricTrendLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}


