package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRequestUsage
import io.github.mangi.eta.agent.runtime.AgentMetricRequestEntity
import io.github.mangi.eta.agent.runtime.AgentMetricStatus
import io.github.mangi.eta.agent.runtime.AgentTokenCostEstimator
import io.github.mangi.eta.ui.components.PreferenceIcon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentRequestRow(request: AgentMetricRequestEntity, onClick: () -> Unit) {
    val cost = AgentTokenCostEstimator.estimateRequest(request.usage, request.price)
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PreferenceIcon(requestStatusIcon(request.status))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            request.name.ifBlank { request.modelId },
                            style = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 21.sp),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                request.round?.let { append("第 ").append(it).append(" 轮") }
                                if (isNotEmpty()) append(" · ")
                                append("尝试 ").append(request.attempt)
                                request.httpCode?.let { append(" · HTTP ").append(it) }
                            },
                            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        metricCostLabel(cost),
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RequestFact(stringResource(R.string.metrics_request_input, metricNumber(request.usage.inputTokens)))
                    RequestFact(stringResource(R.string.metrics_request_output, metricNumber(request.usage.outputTokens)))
                    RequestFact(stringResource(R.string.metrics_request_first_text, metricDuration(request.firstTextMs)))
                    RequestFact(stringResource(R.string.metrics_request_total, metricDuration(request.totalMs)))
                    requestOutputSpeed(request.usage, request.firstTextMs, request.totalMs)?.let {
                        RequestFact(stringResource(R.string.metrics_request_speed, it))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        metricStatusLabel(request.status),
                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = requestStatusColor(request.status),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "· ${request.providerId.ifBlank { stringResource(R.string.metrics_request_provider_unknown) }}",
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestFact(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
    )
}

@Composable
internal fun AgentRequestDetails(request: AgentMetricRequestEntity) {
    val cost = AgentTokenCostEstimator.estimateRequest(request.usage, request.price)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PreferenceIcon(requestStatusIcon(request.status))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(request.name.ifBlank { request.modelId }, style = MiuixTheme.textStyles.body1)
                Text(
                    buildString {
                        append(request.providerId.ifBlank { stringResource(R.string.metrics_request_provider_unknown) })
                        append(" · ").append(request.modelId)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        RequestDetailLine(stringResource(R.string.metrics_request_round_attempt,
            request.round ?: 0, request.attempt))
        RequestDetailLine(stringResource(R.string.metrics_request_status_http,
            metricStatusLabel(request.status), request.httpCode?.toString() ?: "—"))
        RequestDetailLine(stringResource(R.string.metrics_request_timing_detail,
            metricDuration(request.responseMs), metricDuration(request.firstTextMs), metricDuration(request.totalMs)))
        RequestDetailLine(requestUsageSummary(request.usage))
        RequestDetailLine(stringResource(R.string.metrics_request_speed_line,
            requestOutputSpeed(request.usage, request.firstTextMs, request.totalMs) ?: "—"))
        RequestDetailLine(metricCostLabel(cost))
        if (request.failureCode.isNotBlank()) {
            RequestDetailLine(stringResource(R.string.metrics_request_failure, request.failureCode))
        }
        Text(
            stringResource(R.string.metrics_request_privacy),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun RequestDetailLine(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2)
}

private fun requestStatusIcon(status: String) = when (status) {
    AgentMetricStatus.SUCCEEDED -> Icons.Rounded.CheckCircle
    AgentMetricStatus.FAILED -> Icons.Rounded.ErrorOutline
    AgentMetricStatus.CANCELLED -> Icons.Rounded.Cancel
    AgentMetricStatus.INTERRUPTED -> Icons.Rounded.Block
    else -> Icons.Rounded.HourglassTop
}

@Composable
private fun requestStatusColor(status: String): Color = when (status) {
    AgentMetricStatus.SUCCEEDED -> MiuixTheme.colorScheme.primary
    AgentMetricStatus.FAILED -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
internal fun requestUsageSummary(usage: AgentRequestUsage): String {
    if (!usage.reported) return stringResource(R.string.metrics_request_usage_not_reported)
    return stringResource(
        R.string.metrics_request_usage_detail,
        metricNumber(usage.inputTokens),
        metricNumber(usage.outputTokens),
        metricNumber(usage.reasoningTokens),
        metricNumber(usage.cacheReadTokens ?: usage.cachedTokens),
        metricNumber(usage.cacheWriteTokens),
    )
}

internal fun requestOutputSpeed(usage: AgentRequestUsage, firstTextMs: Long?, totalMs: Long?): String? {
    val tokens = usage.billableOutputTokens ?: usage.outputTokens ?: return null
    val finished = totalMs ?: return null
    val streamingMs = (finished - (firstTextMs ?: 0L)).coerceAtLeast(0L)
    val durationMs = if (streamingMs > 0L) streamingMs else finished
    if (durationMs <= 0L) return null
    return java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
        .format(tokens * 1_000.0 / durationMs)
}

internal fun metricDuration(value: Long?): String {
    if (value == null) return "—"
    return if (value < 1_000L) "${value} ms" else "%.2fs".format(value / 1_000.0)
}
