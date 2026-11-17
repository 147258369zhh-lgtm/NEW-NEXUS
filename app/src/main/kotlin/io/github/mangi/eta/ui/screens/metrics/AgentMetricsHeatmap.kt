package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexusSettingsCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Calendar cells use the same real, filtered/adaptive buckets as the adjacent trend. */
@Composable
internal fun AgentMetricsHeatmap(timeline: MetricTimeline, tokenMode: Boolean = false, onSelect: (Long, Long) -> Unit) {
    var selected by remember(timeline) { mutableStateOf<Int?>(null) }
    val max = timeline.buckets.maxOfOrNull { if (tokenMode) it.tokens ?: 0 else it.runs.toLong() } ?: 0
    val zone = ZoneId.systemDefault()
    val colors = MiuixTheme.colorScheme
    val palette = listOf(lerp(colors.surfaceContainerHigh, Color(0xFF50C77C), .08f), Color(0xFFB2EFC3), Color(0xFF68D88B), Color(0xFF2DBE62), Color(0xFF138B46))
    val daily = timeline.granularity.contains("按天")
    val intraday = timeline.granularity.contains("分钟") || timeline.granularity.contains("小时")
    val rows = if (daily) 7 else 6
    val grid = remember(timeline, rows, daily) {
        val padding = if (daily) (timeline.buckets.firstOrNull()?.start?.dayOfWeek?.value ?: 1) - 1 else 0
        (List<Int?>(padding) { null } + timeline.buckets.indices.map { it }).chunked(rows)
    }
    NexusSettingsCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行活动", style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                Text(if (tokenMode) "Token" else "运行次数", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            }
            Text(timeline.granularity, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(if (tokenMode) "用量为整次运行汇总，归入开始时间。" else "按运行开始时间计数。",
                style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            if (timeline.buckets.isEmpty()) Text("暂无活动记录", style = MiuixTheme.textStyles.footnote1)
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (daily) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.height(17.dp))
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Box(Modifier.height(21.dp).width(18.dp), contentAlignment = Alignment.Center) {
                            Text(it, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                        }
                    }
                }
                grid.forEach { cells ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val first = cells.firstNotNullOfOrNull { it?.let(timeline.buckets::get) }
                        Text(first?.start?.format(DateTimeFormatter.ofPattern(if (intraday) "HH:mm" else if (timeline.granularity.contains("月" ) && !daily) "yy/M" else "M/d")).orEmpty(),
                            modifier = Modifier.height(17.dp), style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                        cells.forEach { index ->
                            if (index == null) { Spacer(Modifier.size(21.dp)); return@forEach }
                            val cell = timeline.buckets[index]
                            val value = if (tokenMode) cell.tokens else cell.runs.toLong()
                            val strength = if (value == null || value <= 0 || max <= 0) 0 else (1 + ((value.toDouble() / max) * 3).toInt()).coerceIn(1, 4)
                            val label = "${cell.start.toLocalDate()} ${cell.label} · ${value ?: if (cell.runs == 0) "无记录" else "未报告"} ${if(tokenMode) "Token" else "次运行开始"}"
                            Box(Modifier.size(21.dp).background(if(selected == index) Color(0xFF287BE8) else palette[strength], RoundedCornerShape(5.dp))
                                .semantics { contentDescription = label }
                                .clickable { selected = index })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("少", style = MiuixTheme.textStyles.footnote2)
                palette.forEach { Box(Modifier.size(10.dp).background(it, RoundedCornerShape(3.dp))) }
                Text("多", style = MiuixTheme.textStyles.footnote2)
                Spacer(Modifier.weight(1f))
                Text("点选查看轨迹", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            selected?.let { timeline.buckets.getOrNull(it) }?.let { cell ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${cell.start.toLocalDate()} · ${cell.label}", style = MiuixTheme.textStyles.footnote1)
                        Text(if (tokenMode) "${cell.tokens?.let(::compactMetric) ?: if (cell.runs == 0) "无记录" else "未报告"} Token${if(cell.incomplete) " · 部分用量" else ""}"
                            else "${cell.runs} 次运行开始", style = MiuixTheme.textStyles.footnote2)
                    }
                    NexaCompactAction("查看轨迹", onClick = { onSelect(cell.start.atZone(zone).toInstant().toEpochMilli(), cell.endExclusive.atZone(zone).toInstant().toEpochMilli()) })
                }
            }
        }
    }
}
