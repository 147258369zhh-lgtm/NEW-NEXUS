package io.github.mangi.eta.ui.screens.metrics

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaMotion
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.components.etaGlassSurface
import top.yukonga.miuix.kmp.basic.*
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AgentMetricsOverview(records: List<AgentRunMetricsRecord>, models: List<AgentMetricModelEntity>,
    metrics: List<AgentRunMetrics>, costAvailable: Boolean,
    requests: List<AgentMetricRequestEntity> = emptyList()) {
    fun total(field: (AgentRunMetrics) -> Long?) = metricKnownSum(metrics, field)
    fun number(value: Long?) = value?.let(::compactMetric) ?: "—"
    val readTokens = total { it.cacheReadTokens ?: it.cachedTokens }
    val cacheRate = metricCacheHitRate(metrics)?.let { "%.1f%%".format(it) } ?: "—"
    val output = total { it.billableOutputTokens ?: it.outputTokens }
    val duration = records.mapNotNull { it.durationMs.takeIf { duration -> duration > 0 } }
    val paired = requests.filter { it.totalMs != null && it.totalMs!! > 0 && it.usage.outputTokens != null }
    val speed = if(paired.isNotEmpty()) "%.1f Token/s".format(paired.sumOf { it.usage.outputTokens ?: 0L } * 1000.0 / paired.sumOf { it.totalMs!! }) else "—"


    val metricsByRecord = models.groupBy { it.recordId }
    val timedUsage = records.mapNotNull { run -> if(run.durationMs <= 0) null else metricKnownSum(metricsByRecord[run.recordId]?.map { it.metrics } ?: listOf(run.metrics)) { it.billableOutputTokens ?: it.outputTokens }?.let { it to run.durationMs } }
    val runSpeed = if(timedUsage.isEmpty()) "—" else "%.1f Token/s".format(timedUsage.sumOf { it.first } * 1000.0 / timedUsage.sumOf { it.second })
    val money = CnyMetricCostText(models)
    MetricStatGrid(listOf(
        MetricStat("总 Token", number(total { it.reportedTotalTokens() }), "输入 ${number(total(::metricReportedInput))} · 输出 ${number(output)}", Icons.Rounded.DataUsage),
        MetricStat("预估费用", if(costAvailable) money ?: "费用未知" else "暂不可用", "人民币 · 按当次单价估算", Icons.Rounded.Payments),
        MetricStat(if(requests.isEmpty()) "运行吞吐" else "请求吞吐", if(requests.isEmpty()) runSpeed else speed, if(requests.isEmpty()) "已报告输出 / 运行耗时" else "已报告输出 / 请求耗时", Icons.Rounded.Speed),
        MetricStat("平均运行耗时", if(duration.isEmpty()) "—" else formatMilliseconds(duration.sum()/duration.size), "${records.size} 次运行", Icons.Rounded.Timer),
    ))
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                EtaIconTile(Icons.Rounded.Cached, tone = io.github.mangi.eta.ui.components.EtaIconTone.MODEL, accentColor = metricAccent(4), size = 28.dp, glyphSize = 16.dp)
                Text("缓存命中 $cacheRate · 读取 ${number(readTokens)} · 写入 ${number(total { it.cacheWriteTokens })}", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                EtaIconTile(Icons.Rounded.DataUsage, tone = io.github.mangi.eta.ui.components.EtaIconTone.MODEL, accentColor = metricAccent(0), size = 28.dp, glyphSize = 16.dp)
                Text("${models.map { it.providerId to it.modelId }.distinct().size} 个模型 · 用量完整 ${metrics.count { it.usageComplete }}/${metrics.size} · 压缩 ${metrics.sumOf { it.completedCompactions }} 次", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun AgentMetricsMonitoring(records: List<AgentRunMetricsRecord>, metrics: List<AgentRunMetrics>,
    approvalMetrics: List<AgentRunMetrics>? = records.map { it.metrics }) {
    val success = records.count { it.status == AgentMetricStatus.SUCCEEDED }
    val failure = records.count { it.status == AgentMetricStatus.FAILED }
    val active = records.count { it.status == AgentMetricStatus.RUNNING }
    val approvals = approvalMetrics?.sumOf { it.approvalRequests }
    MetricStatGrid(listOf(
        MetricStat("运行结果", "$success 成功", "$failure 失败 · $active 运行中", Icons.Rounded.Tune),
        MetricStat("请求与重试", metrics.sumOf { it.requestAttempts }.toString(), "${metrics.sumOf { it.retries }} 次重试", Icons.Rounded.Replay),
        MetricStat("工具执行", metrics.sumOf { it.toolCalls }.toString(), "${metrics.sumOf { it.unresolvedTools }} 项未结算", Icons.Rounded.Bolt),
        MetricStat("审批请求", approvals?.toString() ?: "—", if (approvals == null) "模型筛选下无法精确归属" else "整次运行计数，不重复累加模型", Icons.Rounded.Tune),
    ))
    MonitoringBreakdown("运行状态", listOf(
        Triple("成功", success, Color(0xFF18C895)), Triple("失败", failure, Color(0xFFF45C76)),
        Triple("运行中", active, Color(0xFF338BFF)),
        Triple("取消", records.count { it.status == AgentMetricStatus.CANCELLED }, Color(0xFFFFA044)),
        Triple("中断", records.count { it.status == AgentMetricStatus.INTERRUPTED }, Color(0xFF9A76EE)),
    ), "执行成功与用户目标是否达成分别看待")
    MonitoringBreakdown("工具结果", listOf(
        Triple("成功", metrics.sumOf { it.succeededTools }, Color(0xFF18C895)),
        Triple("失败", metrics.sumOf { it.failedTools }, Color(0xFFF45C76)),
        Triple("未结算", metrics.sumOf { it.unresolvedTools }, Color(0xFFFFA044)),
    ), "仅已结算结果参与工具成功率")
    if (approvalMetrics != null) MonitoringBreakdown("审批结果", listOf(
        Triple("通过", approvalMetrics.sumOf { it.approvalsApproved }, Color(0xFF18C895)),
        Triple("拒绝", approvalMetrics.sumOf { it.approvalsRejected }, Color(0xFFF45C76)),
        Triple("超时", approvalMetrics.sumOf { it.approvalsTimedOut }, Color(0xFFFFA044)),
        Triple("取消", approvalMetrics.sumOf { it.approvalsCancelled }, Color(0xFF9A76EE)),
        Triple("记录失败", approvalMetrics.sumOf { it.approvalsFailed }, Color(0xFFEC6E97)),
        Triple("待处理", (approvalMetrics.sumOf { it.approvalRequests } - approvalMetrics.sumOf {
            it.approvalsApproved + it.approvalsRejected + it.approvalsTimedOut + it.approvalsCancelled + it.approvalsFailed
        }).coerceAtLeast(0), Color(0xFF338BFF)),
    ), "请求、通过、拒绝与等待保留各自含义")
}

@Composable
private fun MonitoringBreakdown(title: String, values: List<Triple<String, Int, Color>>, note: String) {
    val colors = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            val nonzero = values.filter { it.second > 0 }
            if (nonzero.isNotEmpty()) Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                nonzero.forEach { (_, count, color) -> Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color, RoundedCornerShape(5.dp))) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { (label, count, color) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Text(label, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                    Text(count.toString(), style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                } }
            }
            Text(note, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        }
    }
}

private data class MetricStat(val label: String, val value: String, val detail: String, val icon: ImageVector)

@Composable
private fun MetricStatGrid(stats: List<MetricStat>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEachIndexed { row, cells -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEachIndexed { index, stat -> MetricTile(stat, metricAccent(row * 2 + index), Modifier.weight(1f)) }
            if (cells.size == 1) Spacer(Modifier.weight(1f))
        } }
    }
}

@Composable
private fun MetricTile(stat: MetricStat, accent: Color, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaIconTile(stat.icon, tone = io.github.mangi.eta.ui.components.EtaIconTone.MODEL,
                    accentColor = accent, size = 28.dp, glyphSize = 16.dp)
                Text(stat.label, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            }
            Text(stat.value, style = MiuixTheme.textStyles.body1.copy(fontSize = if (stat.value.length > 10) 18.sp else 21.sp, lineHeight = 27.sp), fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(stat.detail, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary, maxLines = 2)
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

internal data class MetricDistribution(val label: String, val value: Long?, val detail: String = "", val incomplete: Boolean = false)

@Composable
internal fun AgentMetricDistributionCard(
    title: String,
    items: List<MetricDistribution>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val palette = listOf(Color(0xFF4C7CF3), Color(0xFF2AA978), Color(0xFFF0A23A), Color(0xFF9A6BE8), Color(0xFF2AA6B8), Color(0xFFE05A67))
    val visible = items.filter { (it.value ?: 0L) > 0 }.sortedByDescending { it.value }
    val total = visible.sumOf { it.value ?: 0L }
    val reveal by animateFloatAsState(
        targetValue = if (visible.isEmpty()) 0f else 1f,
        animationSpec = tween(EtaMotion.chart),
        label = "metric-ring-reveal",
    )
    Card(modifier, colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${if(items.any { it.value != null }) compactMetric(total) else "—"} Token", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = colors.onSurface)
            }
            if (visible.isEmpty()) {
                Text(if(items.isEmpty()) "暂无记录" else if(items.all { it.value == null }) "Token 用量未报告" else "已报告 0 Token", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "$title，已报告 ${compactMetric(total)} Token" }) {
                            val stroke = 13.dp.toPx()
                            val diameter = size.minDimension - stroke
                            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                            var start = -90f
                            drawArc(colors.surfaceContainerHigh, 0f, 360f, false, origin, Size(diameter, diameter), style = Stroke(stroke))
                            visible.forEachIndexed { index, row ->
                                val sweep = ((row.value ?: 0L).toDouble() / total.coerceAtLeast(1) * 360).toFloat()
                                drawArc(palette[index % palette.size], start + .8f, (sweep - 1.6f).coerceAtLeast(.2f) * reveal,
                                    false, origin, Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
                                start += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(compactMetric(total), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                            Text("Token", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        visible.take(3).forEachIndexed { index, row ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).background(palette[index % palette.size], CircleShape))
                                Text(row.label, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${((row.value ?: 0L).toDouble() / total.coerceAtLeast(1) * 100).toInt()}%", style = MiuixTheme.textStyles.footnote2)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.take(6).forEachIndexed { index, item ->
                        Row(
                            Modifier.fillMaxWidth().squircleSurface(colors.surfaceContainerHigh.copy(alpha = .42f), 12.dp)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(palette[index % palette.size], CircleShape))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(item.label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1)
                                if (item.detail.isNotBlank()) Text(item.detail, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${(item.value ?: 0L) * 100 / total.coerceAtLeast(1)}%", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
                                Text(compactMetric(item.value ?: 0L), style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            if(visible.size > 6) Text("其他 ${visible.size - 6} 项 · ${compactMetric(visible.drop(6).sumOf { it.value ?: 0L })} Token", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            items.filter { it.value == null || it.value == 0L }.forEach { item -> Text("${item.label} · ${item.value?.let(::compactMetric) ?: "未报告"}", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary) }
            if(items.any { it.incomplete || it.value == null }) Text("分布包含部分已报告用量", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
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
    val startedAt = AgentMetricsTime.known(record.startedAt)
    val usage = models.map { it.metrics }.ifEmpty { listOf(record.metrics) }
    val input = metricKnownSum(usage, ::metricReportedInput)
    val output = metricKnownSum(usage) { it.billableOutputTokens ?: it.outputTokens }
    val cacheRate = metricCacheHitRate(usage)?.let { "%.1f%%".format(it) } ?: "—"
    val tokensPerSecond = if (output != null && record.durationMs > 0) {
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
                        "${metricSourceLabel(record.source)} · ${providerName.ifBlank { record.providerId }} · " +
                            (startedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                                ?: stringResource(R.string.metrics_time_unknown)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(metricStatusLabel(record.status), style = MiuixTheme.textStyles.footnote1, color = statusColor, fontWeight = FontWeight.Medium)
                    Text(CnyMetricCostText(models) ?: "费用未知", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
            if (projectName.isNotBlank()) Text("项目 · $projectName", style = MiuixTheme.textStyles.footnote2, color = colors.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RunMetricCell("输入", input?.let(::compactMetric) ?: "—", Modifier.weight(1f))
                RunMetricCell("输出", output?.let(::compactMetric) ?: "—", Modifier.weight(1f))
                RunMetricCell("缓存命中", cacheRate, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RunMetricCell("运行吞吐", tokensPerSecond, Modifier.weight(1f))
                RunMetricCell("总耗时", record.durationMs.takeIf { it > 0 }?.let { formatMilliseconds(it) }
                    ?: stringResource(R.string.metrics_duration_unknown), Modifier.weight(1f))
                RunMetricCell("上下文", record.metrics.contextTokens?.toLong()?.let(::compactMetric) ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${usage.sumOf { it.requestAttempts }} 请求 · ${usage.sumOf { it.retries }} 重试", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                Text("${usage.sumOf { it.toolCalls }} 工具 · ${record.metrics.approvalRequests} 审批", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun RunMetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .48f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
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
            NexaSegmentedTabs(tabs = listOf("运行", "Token"), selectedTabIndex = mode, onTabSelected = { mode = it })
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









