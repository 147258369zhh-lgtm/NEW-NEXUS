package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaMotion
import io.github.mangi.eta.ui.components.etaGlassSurface
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AgentMetricsOverview(records: List<AgentRunMetricsRecord>, models: List<AgentMetricModelEntity>,
    metrics: List<AgentRunMetrics>, costAvailable: Boolean) {
    val costs = AgentTokenCostEstimator.summarize(models, models.size + records.count { run -> models.none { it.recordId == run.recordId } })
    val knownCost = costs.currencies.joinToString("\n") { "${it.currency} ${metricMoney(it.amount)}" }
    fun total(field: (AgentRunMetrics) -> Long?): Long? = metrics.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()
    val reads = metrics.filter { it.cacheReadTokens != null && it.uncachedInputTokens != null }
    val readTokens = reads.sumOf { it.cacheReadTokens ?: 0 }
    val allInput = reads.sumOf { (it.uncachedInputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.cacheWriteTokens ?: 0) }
    val cacheRate = if (allInput > 0) "%.1f%%".format(100.0 * readTokens / allInput) else "—"
    val totalTokens = total { it.reportedTotalTokens() } ?: 0L
    val output = total { it.billableOutputTokens ?: it.outputTokens }
    val input = total { (it.uncachedInputTokens ?: it.inputTokens) }
    val cacheWrite = total { it.cacheWriteTokens }
    val retries = metrics.sumOf { it.retries }
    val toolSuccess = if (metrics.sumOf { it.succeededTools + it.failedTools } > 0) {
        "${metrics.sumOf { it.succeededTools }}/${metrics.sumOf { it.succeededTools + it.failedTools }}"
    } else "—"
    val totalDuration = records.sumOf { it.durationMs }
    val speed = if (output != null && totalDuration > 0) {
        "${compactMetric((output * 1_000.0 / totalDuration).toLong())}/s"
    } else "—"
    val stats = listOf(
        MetricStat("运行次数", records.size.toString(), "${models.size} 个模型作用域", Icons.Rounded.Tune),
        MetricStat("请求次数", metricNumber(total { it.requestAttempts.toLong() }), "$retries 次重试", Icons.Rounded.Replay),
        MetricStat("总 Token", compactMetric(totalTokens), "输入 ${compactMetric(input ?: 0)} · 输出 ${compactMetric(output ?: 0)}", Icons.Rounded.DataUsage),
        MetricStat("预估费用", if (costAvailable) knownCost.replace('\n', ' ') else "暂不可用", "完整计价 ${costs.completeModels}/${costs.totalModels}", Icons.Rounded.Payments),
        MetricStat("缓存命中", cacheRate, "读取 ${compactMetric(readTokens)}", Icons.Rounded.Cached),
        MetricStat("缓存写入", compactMetric(cacheWrite ?: 0), if (cacheWrite == null) "未报告" else "已记录", Icons.Rounded.Bolt),
        MetricStat("吞吐速度", speed, "输出 Token / 运行时长", Icons.Rounded.Speed),
        MetricStat("平均耗时", if (records.isEmpty()) "—" else "${formatMilliseconds(totalDuration / records.size)}", "累计 ${formatMilliseconds(totalDuration)}", Icons.Rounded.Timer),
        MetricStat("工具成功", toolSuccess, "成功 / 已结算", Icons.Rounded.Bolt),
        MetricStat("用量完整", "${metrics.count { it.usageComplete }}/${metrics.size}", "已报告用量", Icons.Rounded.DataUsage),
    )
    var accentIndex = 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { rowStats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowStats.forEach { stat ->
                    MetricTile(stat, metricAccent(accentIndex++), Modifier.weight(1f))
                }
                if (rowStats.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("${records.size} 次运行 · ${metrics.count { it.usageComplete }}/${metrics.size} 项用量完整",
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

private data class MetricStat(
    val label: String,
    val value: String,
    val detail: String,
    val icon: ImageVector,
)

@Composable
private fun MetricTile(stat: MetricStat, accent: Color, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Card(modifier.etaGlassSurface(accent = accent, cornerRadius = 18.dp), colors = CardDefaults.defaultColors(
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
    )) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            EtaIconTile(stat.icon, tone = io.github.mangi.eta.ui.components.EtaIconTone.MODEL, accentColor = accent, size = 32.dp, glyphSize = 17.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stat.label, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp), color = colors.onSurfaceVariantSummary)
                Crossfade(
                    targetState = stat.value,
                    animationSpec = tween(EtaMotion.state),
                    label = "metric-value",
                ) { value ->
                    Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(stat.detail, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp), color = colors.onSurfaceVariantSummary, maxLines = 2)
            }
        }
    }
}

private fun metricAccent(index: Int): Color = listOf(
    Color(0xFF4C7CF3), Color(0xFF2AA978), Color(0xFFF0A23A), Color(0xFF9A6BE8),
    Color(0xFF2AA6B8), Color(0xFFE05A67),
)[index % 6]

internal fun compactMetric(value: Long): String = when {
    value >= 1_000_000_000L -> "%.2fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.2fM".format(value / 1_000_000.0)
    value >= 10_000L -> "%.1fK".format(value / 1_000.0)
    else -> java.text.NumberFormat.getIntegerInstance().format(value)
}

internal fun formatMilliseconds(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1_000.0)
    else -> "%dm %02ds".format(ms / 60_000, (ms / 1_000) % 60)
}

internal data class MetricDistribution(val label: String, val value: Long, val detail: String = "")

@Composable
internal fun AgentMetricDistributionCard(
    title: String,
    items: List<MetricDistribution>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val palette = listOf(Color(0xFF4C7CF3), Color(0xFF2AA978), Color(0xFFF0A23A), Color(0xFF9A6BE8), Color(0xFF2AA6B8), Color(0xFFE05A67))
    val visible = items.filter { it.value > 0 }.sortedByDescending(MetricDistribution::value)
    val total = visible.sumOf(MetricDistribution::value).coerceAtLeast(1)
    val reveal by animateFloatAsState(
        targetValue = if (visible.isEmpty()) 0f else 1f,
        animationSpec = tween(EtaMotion.chart),
        label = "metric-ring-reveal",
    )
    Card(modifier.etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp), colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${compactMetric(total)} Token", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            }
            if (visible.isEmpty()) {
                Text("暂无 Token 用量", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            } else {
                Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    visible.forEachIndexed { index, item ->
                        Box(
                            Modifier.weight(item.value.toFloat()).fillMaxHeight().background(
                                palette[index % palette.size].copy(alpha = .68f + .32f * reveal),
                                RoundedCornerShape(5.dp),
                            ),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    visible.take(6).forEachIndexed { index, item ->
                        Row(
                            Modifier.fillMaxWidth().squircleSurface(colors.surfaceContainerHigh.copy(alpha = .52f), 10.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(palette[index % palette.size], CircleShape))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(item.label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1)
                                if (item.detail.isNotBlank()) Text(item.detail, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${item.value * 100 / total}%", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
                                Text(compactMetric(item.value), style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentRunMetricsCard(
    record: AgentRunMetricsRecord,
    models: List<AgentMetricModelEntity>,
    providerName: String,
    projectName: String,
    costLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val usage = models.map { it.metrics }.ifEmpty { listOf(record.metrics) }
    val input = usage.sumOf { it.uncachedInputTokens ?: it.inputTokens ?: 0L }
    val output = usage.sumOf { it.billableOutputTokens ?: it.outputTokens ?: 0L }
    val cached = usage.sumOf { it.cacheReadTokens ?: it.cachedTokens ?: 0L }
    val cacheWrite = usage.sumOf { it.cacheWriteTokens ?: 0L }
    val denominator = input + cached + cacheWrite
    val cacheRate = if (denominator > 0) "%.1f%%".format(cached * 100.0 / denominator) else "—"
    val tokensPerSecond = if (output > 0 && record.durationMs > 0) {
        "${compactMetric((output * 1_000.0 / record.durationMs).toLong())}/s"
    } else "—"
    val statusColor = when (record.status) {
        AgentMetricStatus.RUNNING -> Color(0xFF2FA66A)
        AgentMetricStatus.SUCCEEDED -> Color(0xFF2AA978)
        AgentMetricStatus.FAILED -> colors.error
        AgentMetricStatus.CANCELLED, AgentMetricStatus.INTERRUPTED -> Color(0xFFF0A23A)
        else -> colors.onSurfaceVariantSummary
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(36.dp).background(statusColor.copy(alpha = .13f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Icon(
                        if (record.status == AgentMetricStatus.RUNNING) Icons.Rounded.Speed else Icons.Rounded.DataUsage,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.modelName.ifBlank { providerName.ifBlank { record.modelId } }, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${metricSourceLabel(record.source)} · ${providerName.ifBlank { record.providerId }} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAt))}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(metricStatusLabel(record.status), style = MiuixTheme.textStyles.footnote1, color = statusColor, fontWeight = FontWeight.Medium)
                    Text(costLabel.ifBlank { "费用未知" }, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
            if (projectName.isNotBlank()) Text("项目 · $projectName", style = MiuixTheme.textStyles.footnote2, color = colors.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RunMetricCell("输入", compactMetric(input), Modifier.weight(1f))
                RunMetricCell("输出", compactMetric(output), Modifier.weight(1f))
                RunMetricCell("缓存命中", cacheRate, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RunMetricCell("速度", tokensPerSecond, Modifier.weight(1f))
                RunMetricCell("总耗时", formatMilliseconds(record.durationMs), Modifier.weight(1f))
                RunMetricCell("上下文", record.metrics.contextTokens?.toLong()?.let(::compactMetric) ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${record.metrics.requestAttempts} 请求 · ${record.metrics.retries} 重试", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                Text("${record.metrics.toolCalls} 工具 · ${record.metrics.failedTools} 失败 · ${record.metrics.completedCompactions} 压缩", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun RunMetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .62f), RoundedCornerShape(9.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable internal fun MetricValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body1.copy(fontSize = if (value.substringAfterLast('\n').length > 16) 15.sp else 19.sp, lineHeight = 25.sp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun AgentActivityCalendar(days: List<AgentActivityDay>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    val maximum = days.maxOfOrNull { if (mode == 0) it.runs.toLong() else it.tokens ?: 0 }?.coerceAtLeast(1) ?: 1
    val colors = MiuixTheme.colorScheme
    val locale = LocalLocale.current.platformLocale
    val weekdayLabels = listOf(
        java.time.DayOfWeek.MONDAY,
        java.time.DayOfWeek.TUESDAY,
        java.time.DayOfWeek.WEDNESDAY,
        java.time.DayOfWeek.THURSDAY,
        java.time.DayOfWeek.FRIDAY,
        java.time.DayOfWeek.SATURDAY,
        java.time.DayOfWeek.SUNDAY,
    ).map { it.getDisplayName(TextStyle.NARROW, locale) }
    val leading = days.firstOrNull()?.date?.dayOfWeek?.value?.minus(1) ?: 0
    val cells: List<AgentActivityDay?> = buildList {
        repeat(leading) { add(null) }
        addAll(days)
        while (size % 7 != 0) add(null)
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (days.size) { 1 -> "今天"; 7 -> "最近 7 天"; 30 -> "最近 30 天"; else -> "最近 ${days.size} 天" },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("连续 ${AgentMetricsActivity.streak(days)} 天", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
            TabRow(tabs = listOf("运行", "Token"), selectedTabIndex = mode, onTabSelected = { mode = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote2,
                        textAlign = TextAlign.Center,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        week.forEach { day ->
                            val value = if (mode == 0) day?.runs?.toLong() ?: 0 else day?.tokens ?: 0
                            val level = if (value == 0L) 0f else (value.toFloat() / maximum).coerceIn(.24f, 1f)
                            val fill = if (level == 0f) colors.surfaceContainerHigh.copy(alpha = .72f)
                                else colors.primary.copy(alpha = .18f + .68f * level)
                            val isSelected = day?.date == selected
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .background(if (day == null) androidx.compose.ui.graphics.Color.Transparent else fill, RoundedCornerShape(9.dp))
                                    .then(
                                        if (isSelected) Modifier.border(1.5.dp, colors.primary, RoundedCornerShape(9.dp))
                                        else Modifier,
                                    )
                                    .then(
                                        if (day != null) Modifier
                                            .semantics { contentDescription = "${day.date}，${day.runs} 次运行，${day.tokens?.toString() ?: "未知"} Token" }
                                            .clickable { onSelect(day.date.takeIf { it != selected }) }
                                        else Modifier,
                                    )
                                    .padding(horizontal = 4.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (day != null) {
                                    Text(day.date.dayOfMonth.toString(), style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        if (mode == 0) "${day.runs}次" else if (day.tokens == null) "—" else compactMetric(day.tokens),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = if (day.runs == 0) colors.onSurfaceVariantSummary else colors.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val day = days.firstOrNull { it.date == selected }
            Text(
                day?.let { "${it.date} · ${it.runs} 次运行 · ${it.tokens?.toString() ?: "用量未知"} Token${if (!it.complete) " · 部分用量" else ""}" }
                    ?: "按当前筛选与保留记录统计",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}
