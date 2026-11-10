package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentMetricDay
import io.github.mangi.eta.ui.components.etaGlassSurface
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

@Composable
internal fun AgentMetricsTrendCard(days: List<AgentMetricDay>) {
    if (days.isEmpty()) return
    val buckets = if (days.size <= 10) {
        days.map { TrendBucket(it.date, it.date, it.runs, it.inputTokens, it.outputTokens, it.incompleteUsage) }
    } else {
        val chunkSize = (days.size + 9) / 10
        days.chunked(chunkSize).map { chunk ->
            TrendBucket(
                start = chunk.first().date,
                end = chunk.last().date,
                runs = chunk.sumOf { it.runs },
                inputTokens = chunk.mapNotNull { it.inputTokens }.takeIf { it.isNotEmpty() }?.sum(),
                outputTokens = chunk.mapNotNull { it.outputTokens }.takeIf { it.isNotEmpty() }?.sum(),
                incomplete = chunk.sumOf { it.incompleteUsage },
            )
        }
    }
    val maxRuns = buckets.maxOf { it.runs }.coerceAtLeast(1)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    val colors = MiuixTheme.colorScheme
    Card(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
            .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("运行次数", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.metrics_trend_range, days.first().date.format(dateFormat), days.last().date.format(dateFormat)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Text("${days.sumOf { it.runs }} 次", style = MiuixTheme.textStyles.footnote1, color = colors.primary, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                buckets.forEach { day ->
                    val description = stringResource(R.string.metrics_trend_description, day.start.format(dateFormat), day.runs,
                        metricNumber(day.inputTokens), metricNumber(day.outputTokens), day.incomplete)
                    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(NumberFormat.getIntegerInstance().format(day.runs), style = MiuixTheme.textStyles.footnote1)
                        Box(Modifier.height(92.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((92f * day.runs / maxRuns).coerceAtLeast(2f).dp)
                                    .background(
                                        if (day.runs > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(6.dp),
                                    ),
                            )
                        }
                        Text(
                            if (day.start == day.end) "${day.start.monthValue}/${day.start.dayOfMonth}"
                            else "${day.start.dayOfMonth}-${day.end.dayOfMonth}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (days.any { it.incompleteUsage > 0 }) {
                Text(stringResource(R.string.metrics_trend_note), style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

private data class TrendBucket(
    val start: java.time.LocalDate,
    val end: java.time.LocalDate,
    val runs: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val incomplete: Int,
)

@Composable
internal fun AgentTokenUsageTrendCard(days: List<AgentMetricDay>) {
    if (days.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    val inputColor = Color(0xFF4C7CF3)
    val outputColor = Color(0xFF2AA978)
    val maxValue = max(
        days.maxOfOrNull { it.inputTokens ?: 0L } ?: 0L,
        days.maxOfOrNull { it.outputTokens ?: 0L } ?: 0L,
    ).coerceAtLeast(1L)
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Token 使用趋势", style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrendLegend("输入", inputColor)
                    TrendLegend("输出", outputColor)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val chartLeft = 8.dp.toPx()
                val chartRight = size.width - 8.dp.toPx()
                val chartTop = 8.dp.toPx()
                val chartBottom = size.height - 18.dp.toPx()
                repeat(4) { index ->
                    val y = chartTop + (chartBottom - chartTop) * index / 3f
                    drawLine(colors.outline.copy(alpha = .12f), Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 1.dp.toPx())
                }
                fun pathFor(field: (AgentMetricDay) -> Long?): Path {
                    val path = Path()
                    days.forEachIndexed { index, day ->
                        val x = if (days.size == 1) chartLeft else chartLeft + (chartRight - chartLeft) * index / (days.size - 1)
                        val value = field(day) ?: 0L
                        val y = chartBottom - (chartBottom - chartTop) * value / maxValue
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }
                drawPath(pathFor { it.inputTokens }, inputColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(pathFor { it.outputTokens }, outputColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                days.forEachIndexed { index, day ->
                    val x = if (days.size == 1) chartLeft else chartLeft + (chartRight - chartLeft) * index / (days.size - 1)
                    listOf(day.inputTokens to inputColor, day.outputTokens to outputColor).forEach { (tokens, color) ->
                        if (tokens != null) {
                            val y = chartBottom - (chartBottom - chartTop) * tokens / maxValue
                            drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, y))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { day ->
                    Text("${day.date.monthValue}/${day.date.dayOfMonth}", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
            Text("峰值 ${compactMetric(maxValue)} Token · 缺失用量显示为未报告，不按零推断", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun TrendLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
