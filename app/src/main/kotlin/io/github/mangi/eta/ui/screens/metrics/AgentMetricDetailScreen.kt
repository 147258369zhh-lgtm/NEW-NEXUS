package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.agent.runtime.AgentTokenCostEstimator
import io.github.mangi.eta.agent.runtime.AgentMetricTraceEntity
import io.github.mangi.eta.agent.runtime.AgentMetricRequestEntity
import io.github.mangi.eta.agent.runtime.AgentMetricModelEntity
import io.github.mangi.eta.ui.components.DetailListRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Compress
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentMetricDetailScreen(recordId: String, onBack: () -> Unit, onOpenConversation: (String) -> Unit,
    onOpenMeeting: (String) -> Unit, onOpenTasks: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var traceFilter by remember { mutableIntStateOf(0) }
    var showTechnical by remember { mutableStateOf(false) }
    var inspectedStep by remember { mutableStateOf<AgentMetricTraceEntity?>(null) }
    var inspectedModel by remember { mutableStateOf<AgentMetricModelEntity?>(null) }
    var inspectedRequest by remember { mutableStateOf<AgentMetricRequestEntity?>(null) }
    val detail by remember(context, recordId, reload) {
        AgentRunMetricsStore.detail(context, recordId).onEach { loading = false; failed = false }
            .catch { loading = false; failed = true }
    }.collectAsState(initial = null)
    MiuixScaffoldPage(title = stringResource(R.string.metrics_detail_title), onBack = onBack, overlay = {
        inspectedModel?.let { model -> WindowDialog(show = true, title = model.name.ifBlank { model.modelId }, onDismissRequest = { inspectedModel = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${model.providerId} · ${model.modelId}", style = MiuixTheme.textStyles.footnote1)
                Text(metricCostLabel(AgentTokenCostEstimator.estimate(model.metrics, model.price)), style = MiuixTheme.textStyles.body2)
                Text("${model.metrics.requestAttempts} 次请求 · ${model.metrics.retries} 次重试", style = MiuixTheme.textStyles.body2)
                model.price?.let { price ->
                    Text("每百万 Token\n输入 ${price.currency} ${price.input}\n输出 ${price.currency} ${price.output}\n缓存读取 ${price.cacheRead ?: "未知"}\n缓存写入 ${price.cacheWrite ?: "未知"}", style = MiuixTheme.textStyles.footnote1)
                }
                TextButton("关闭", onClick = { inspectedModel = null })
            }
        } }
        inspectedStep?.let { step -> WindowDialog(show = true, title = metricTraceLabel(step.kind), summary =
            "开始后 %.3f 秒".format(step.elapsedMs / 1000.0) + "\n事件 #${step.sequence}" + (step.round?.let { " · 第 $it 轮" } ?: "") +
                step.label.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() + step.success?.let { if (it) "\n成功" else "\n失败" }.orEmpty(),
            onDismissRequest = { inspectedStep = null }) {
            TextButton("关闭", onClick = { inspectedStep = null })
        } }
        inspectedRequest?.let { request -> WindowDialog(
            show = true,
            title = request.name.ifBlank { request.modelId },
            onDismissRequest = { inspectedRequest = null },
        ) {
            AgentRequestDetails(request)
            TextButton(stringResource(R.string.metrics_clear), onClick = { inspectedRequest = null })
        } }
    }) {
        val loaded = detail
        if (loaded == null) item(key = "state") {
            Card(Modifier.padding(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (loading) R.string.metrics_loading else if (failed) R.string.metrics_load_failed else R.string.metrics_record_missing))
                    if (failed) TextButton(stringResource(R.string.metrics_refresh), onClick = { loading = true; reload++ })
                }
            }
        } else {
            val record = loaded.run
            if (failed) item(key = "stale_error") {
                Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.metrics_load_failed), color = MiuixTheme.colorScheme.error)
                        TextButton(stringResource(R.string.metrics_refresh), onClick = { loading = true; reload++ })
                    }
                }
            }
            item(key = "overview") {
                Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(metricSourceLabel(record.source) + " · " + metricStatusLabel(record.status), style = MiuixTheme.textStyles.body1)
                        Text(DateFormat.getDateTimeInstance().format(Date(record.startedAt)), style = MiuixTheme.textStyles.body2)
                        Text(stringResource(R.string.metrics_detail_duration, NumberFormat.getNumberInstance().format(record.durationMs / 1000.0)), style = MiuixTheme.textStyles.body2)
                    }
                    ArrowPreference(title = if (showTechnical) "收起用量明细" else "用量明细", onClick = { showTechnical = !showTechnical })
                    if (showTechnical) {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.metrics_detail_rounds, record.metrics.rounds), style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.metrics_outcomes, record.metrics.succeededTools, record.metrics.failedTools, record.metrics.unresolvedTools), style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.metrics_reported_tokens, metricNumber(record.metrics.inputTokens), metricNumber(record.metrics.outputTokens)), style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.metrics_detail_coverage, record.metrics.inputReportedRounds, record.metrics.outputReportedRounds, record.metrics.rounds),
                                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(stringResource(R.string.metrics_detail_images, metricNumber(record.metrics.imageBytes)), style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.metrics_detail_requests, record.metrics.requestAttempts, record.metrics.retries, record.metrics.completedCompactions), style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.metrics_detail_extra_tokens, metricNumber(record.metrics.reasoningTokens), metricNumber(record.metrics.cachedTokens)), style = MiuixTheme.textStyles.body2)
                        }
                    }
                    AgentMetricSourceLinks(record, onOpenConversation, onOpenMeeting, onOpenTasks)
                }
                val billable = loaded.models + loaded.children.map { it.asModel() }
                AgentMetricsOverview(listOf(record), billable, billable.map { it.metrics }.ifEmpty { listOf(record.metrics) }, costAvailable = !failed)
            }
            item(key = "models_title") { SmallTitle(stringResource(R.string.metrics_detail_models)) }
            items(loaded.models.sortedBy { it.index }, key = { "model_${it.index}" }) { model ->
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    DetailListRow(title = model.name.ifBlank { model.modelId }, icon = Icons.Rounded.Memory,
                        preview = "${metricNumber(model.metrics.inputTokens)} 输入 · ${metricNumber(model.metrics.outputTokens)} 输出",
                        subtitle = "${model.metrics.rounds} 轮 · ${model.metrics.requestAttempts} 次请求 · ${model.metrics.retries} 次重试",
                        value = metricCostLabel(AgentTokenCostEstimator.estimate(model.metrics, model.price)), onClick = { inspectedModel = model })
                }
            }
            if (loaded.children.isNotEmpty()) item(key = "children_title") { SmallTitle(stringResource(R.string.metrics_children_title)) }
            items(loaded.children.sortedBy { it.index }, key = { "child_${it.scopeId}" }) { child ->
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    DetailListRow(title = child.name.ifBlank { child.modelId },
                        icon = if (child.kind == "compaction") Icons.Rounded.Compress else Icons.Rounded.AccountTree,
                        preview = stringResource(if (child.kind == "compaction") R.string.metrics_child_compaction else R.string.metrics_child_subagent) + " · " + metricStatusLabel(child.status),
                        subtitle = "${metricNumber(child.metrics.reportedTotalTokens())} Token · ${child.metrics.requestAttempts} 次请求\n" +
                            stringResource(R.string.metrics_child_relation, child.index + 1,
                                loaded.children.firstOrNull { it.scopeId == child.parentScopeId }?.let { (it.index + 1).toString() }
                                    ?: stringResource(R.string.metrics_child_parent_root)),
                        value = metricCostLabel(AgentTokenCostEstimator.estimate(child.metrics, child.price)), onClick = { inspectedModel = child.asModel() })
                }
            }
            item(key = "requests_title") { SmallTitle(stringResource(R.string.metrics_requests_title)) }
            val requests = loaded.requests.sortedBy { it.startedElapsedMs }
            if (requests.isNotEmpty()) {
                item(key = "requests_summary") {
                    Text(
                        stringResource(
                            R.string.metrics_requests_summary,
                            requests.size,
                            requests.count { it.status == io.github.mangi.eta.agent.runtime.AgentMetricStatus.SUCCEEDED },
                            requests.count { it.status == io.github.mangi.eta.agent.runtime.AgentMetricStatus.FAILED },
                        ),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                items(requests, key = { "request_${it.requestId}" }) { request ->
                    AgentRequestRow(request, onClick = { inspectedRequest = request })
                }
            } else {
                item(key = "requests_missing") {
                    Text(
                        stringResource(if (loaded.trace.any { it.kind == "request" }) R.string.metrics_requests_legacy else R.string.metrics_requests_empty),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item(key = "trace_title") { SmallTitle(stringResource(R.string.metrics_trace_title)) }
            item(key = "trace_filters") {
                TabRow(tabs = listOf("全部", "工具", "异常"), selectedTabIndex = traceFilter, onTabSelected = { traceFilter = it }, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (record.omittedTraceSteps > 0) item(key = "trace_limit") {
                Text(stringResource(R.string.metrics_trace_limit, record.omittedTraceSteps), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.footnote1)
            }
            if (loaded.trace.isEmpty()) item(key = "trace_empty") {
                Text(stringResource(R.string.metrics_trace_empty), Modifier.padding(24.dp), style = MiuixTheme.textStyles.body2)
            }
            val visibleTrace = loaded.trace.sortedBy { it.sequence }.filter { step -> when (traceFilter) {
                1 -> step.kind.startsWith("tool_") || step.kind.startsWith("hosted_") || step.kind.startsWith("child_") || step.kind == "subagent"
                2 -> step.success == false || step.kind in setOf("failed", "interrupted", "retry", "accessibility") || step.kind == "response" && (step.label.toIntOrNull() ?: 0) >= 400
                else -> true
            } }
            if (visibleTrace.isEmpty() && loaded.trace.isNotEmpty()) item("trace_no_match") { Text("暂无此类事件", Modifier.padding(24.dp), style = MiuixTheme.textStyles.footnote1) }
            items(visibleTrace, key = { "trace_${it.sequence}" }) { step ->
                AgentTraceRow(step, metricTraceLabel(step.kind), onClick = { inspectedStep = step })
            }
            item("exports") {
                AgentMetricsExportControls(listOf(recordId), enabled = !failed && !loading)
                Text(stringResource(R.string.metrics_trace_privacy), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable private fun metricTraceLabel(kind: String): String {
    if (kind == "first_text") return "收到首段文字"
    if (kind == "assistant") return "模型回复完成"
    if (kind == "accessibility") return "无障碍状态变化"
    return stringResource(when (kind) {
    "model" -> R.string.metrics_trace_model
    "start" -> R.string.metrics_trace_start
    "round" -> R.string.metrics_trace_request
    "request" -> R.string.metrics_trace_request_sent
    "response" -> R.string.metrics_trace_response
    "tool_start", "hosted_start" -> R.string.metrics_trace_tool_start
    "tool_finish", "hosted_finish" -> R.string.metrics_trace_tool_finish
    "retry" -> R.string.metrics_trace_retry
    "compact" -> R.string.metrics_trace_compact
    "gui" -> R.string.metrics_trace_gui
    "supplement" -> R.string.metrics_trace_supplement
    "subagent" -> R.string.metrics_trace_subagent
    "child_start" -> R.string.metrics_child_start
    "child_finish" -> R.string.metrics_child_finish
    "usage" -> R.string.metrics_trace_usage
    "succeeded" -> R.string.metrics_status_succeeded
    "failed" -> R.string.metrics_status_failed
    "cancelled" -> R.string.metrics_status_cancelled
    else -> R.string.metrics_status_interrupted
})
}
