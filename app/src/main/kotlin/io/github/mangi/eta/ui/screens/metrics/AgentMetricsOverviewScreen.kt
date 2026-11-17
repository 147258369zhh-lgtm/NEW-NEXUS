package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.*
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AgentMetricsScreen(onBack: () -> Unit, onOpenRun: (String) -> Unit, onOpenPrices: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    val snapshots by remember(context, reload) {
        AgentRunMetricsStore.overview(context).onEach { loading = false; failed = false }
            .catch { failed = true; loading = false }
    }.collectAsState(initial = emptyList())
    val timingSummaries by remember(context) { AgentRunMetricsStore.requestTimingSummaries(context).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val records = snapshots.map { it.run }
    val modelRecords = snapshots.flatMap(::metricScopedModels)
    val projects by remember(context) { ProjectRepository.projects(context).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val providerSettings by remember(context) { flow { emitAll(ProviderRepository.providersFlow()) }.catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    fun providerName(id: String) = providerSettings.firstOrNull { it.id == id }?.name ?: id
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableIntStateOf(4) }
    var customStartText by rememberSaveable { mutableStateOf("") }
    var customEndText by rememberSaveable { mutableStateOf("") }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEndDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWindowStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedWindowEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val overviewListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val managementListState = rememberLazyListState()
    val allLabel = stringResource(R.string.metrics_filter_all)
    val sources = records.map { it.source }.distinct().sorted()
    val models = modelRecords.distinctBy { "${it.providerId}:${it.modelId}" }
    val providers = modelRecords.map { it.providerId }.filter { it.isNotBlank() }.distinct().sorted()
    val projectIds = records.map { it.projectId }.filter(String::isNotBlank).distinct()
    val statuses = listOf(AgentMetricStatus.RUNNING, AgentMetricStatus.SUCCEEDED, AgentMetricStatus.FAILED, AgentMetricStatus.CANCELLED, AgentMetricStatus.INTERRUPTED, AgentMetricStatus.LEGACY)
    val today = LocalDate.now()
    val customStart = customStartText.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val customEnd = customEndText.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val matchingModels = modelRecords.filter { (provider == null || provider == it.providerId) && (model == null || model == "${it.providerId}:${it.modelId}") }
    val matchingIds = matchingModels.map { it.recordId }.toSet()
    val matchingRecords = records.filter { (source == null || source == it.source) &&
        (model == null && provider == null || it.recordId in matchingIds) && (project == null || project == it.projectId) &&
        (status == null || status == it.status) }
    val dates = matchingRecords.mapNotNull { AgentMetricsActivity.knownDate(it.startedAt) }
    val actualStart = dates.minOrNull()
    val actualEnd = dates.maxOrNull()
    val anchor = today
    val requestedStart = when(period) { 0 -> anchor; 1 -> anchor.minusDays(6); 2 -> anchor.minusDays(29); 3 -> customStart; else -> null }
    val requestedEnd = if(period == 3) customEnd else if(period == 4) null else today
    val rangeStart = listOfNotNull(requestedStart, actualStart).maxOrNull()
    val rangeEnd = listOfNotNull(requestedEnd, actualEnd).minOrNull()
    val invalidCustom = period == 3 && ((customStartText.isNotBlank() && customStart == null) ||
        (customEndText.isNotBlank() && customEnd == null) || customStart != null && customEnd != null && customStart.isAfter(customEnd))
    val filtered = matchingRecords.filter { run ->
        val date = AgentMetricsActivity.knownDate(run.startedAt)
        !invalidCustom && if(date == null) period == 4 else
            (rangeStart == null || !date.isBefore(rangeStart)) && (rangeEnd == null || !date.isAfter(rangeEnd))
    }
    val ids = filtered.map { it.recordId }.toSet()
    val selectedModels = matchingModels.filter { it.recordId in ids }
    val metricsByRun = selectedModels.groupBy { it.recordId }.mapValues { it.value.map { model -> model.metrics } }
    val usages = filtered.flatMap { metricsByRun[it.recordId] ?: listOf(it.metrics) }
    // Root approval phases are repeated on parent model snapshots: count the root once, then its child scopes.
    val approvalUsages = if(provider != null || model != null) null else filtered.map { it.metrics } + snapshots.filter { it.run.recordId in ids }.flatMap { overview ->
        overview.children.filter { (provider == null || provider == it.providerId) && (model == null || model == "${it.providerId}:${it.modelId}") }.map { it.metrics }
    }
    val modelDistribution = selectedModels.groupBy { Triple(it.providerId, it.modelId, it.name) }.map { (key, values) ->
        MetricDistribution(key.third.ifBlank { key.second }, metricKnownSum(values.map { it.metrics }) { it.reportedTotalTokens() },
            "${providerName(key.first)} · ${values.sumOf { it.metrics.requestAttempts }} 请求", values.any { !it.metrics.usageComplete })
    }
    val sourceDistribution = filtered.groupBy { it.source }.map { (source, runs) ->
        val samples = runs.flatMap { metricsByRun[it.recordId] ?: listOf(it.metrics) }
        MetricDistribution(metricSourceLabel(source), metricKnownSum(samples) { it.reportedTotalTokens() },
            "${runs.size} 次运行 · ${samples.sumOf { it.requestAttempts }} 请求", samples.any { !it.usageComplete })
    }
    val timeline = adaptiveMetricTimeline(filtered, metricsByRun)
    val day = selectedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val endDay = selectedEndDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: day
    val shown = filtered.filter { run -> if(selectedWindowStart != null && selectedWindowEnd != null) metricRunStartedInWindow(run, selectedWindowStart!!, selectedWindowEnd!!) else day == null || AgentMetricsActivity.knownDate(run.startedAt)?.let {
        !it.isBefore(day) && (endDay == null || !it.isAfter(endDay))
    } == true }
    LaunchedEffect(source, model, provider, project, status) { selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null }
    val activeFilters = listOf(source, model, provider, project, status).count { it != null }

    MiuixScaffoldPage(title = stringResource(R.string.route_agent_metrics), onBack = onBack,
        listState = when (selectedTab) {
            1 -> historyListState
            2 -> managementListState
            else -> overviewListState
        },
        aboveContent = {
            NexaSegmentedTabs(
                tabs = listOf(
                    "运行统计", "运行监测", "管理",
                ),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
        actions = { IconButton(onClick = { filtersOpen = true }) { Icon(Icons.Rounded.Tune, contentDescription = "筛选运行记录") } },
        overlay = {
            WindowDialog(show = filtersOpen, title = "筛选", onDismissRequest = { filtersOpen = false }) {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    val sourceLabels = listOf(allLabel) + sources.map { metricSourceLabel(it) }
                    val sourceIndex = sources.indexOf(source) + 1
                    DialogChoicePreference("来源", sourceLabels[sourceIndex], sourceLabels, sourceIndex) { source = sources.getOrNull(it - 1) }
                    val providerLabels = listOf(allLabel) + providers.map(::providerName)
                    val providerIndex = providers.indexOf(provider) + 1
                    DialogChoicePreference("服务商", providerLabels[providerIndex], providerLabels, providerIndex) {
                        provider = providers.getOrNull(it - 1)
                        model = null
                    }
                    val choices = models.filter { provider == null || it.providerId == provider }
                    val modelLabels = listOf(allLabel) + choices.map { it.name.ifBlank { it.modelId } }
                    val modelIndex = choices.indexOfFirst { "${it.providerId}:${it.modelId}" == model } + 1
                    DialogChoicePreference("模型", modelLabels[modelIndex], modelLabels, modelIndex) {
                        model = choices.getOrNull(it - 1)?.let { value -> "${value.providerId}:${value.modelId}" }
                    }
                    if (projectIds.isNotEmpty()) {
                        val projectLabels = listOf(allLabel) + projectIds.map { id -> projects.firstOrNull { it.id == id }?.name ?: id }
                        val projectIndex = projectIds.indexOf(project) + 1
                        DialogChoicePreference("项目", projectLabels[projectIndex], projectLabels, projectIndex) {
                            project = projectIds.getOrNull(it - 1)
                        }
                    }
                    val statusLabels = listOf(allLabel) + statuses.map { metricStatusLabel(it) }
                    val statusIndex = statuses.indexOf(status) + 1
                    DialogChoicePreference("结果", statusLabels[statusIndex], statusLabels, statusIndex) {
                        status = statuses.getOrNull(it - 1)
                    }
                    if (period == 3) {
                        TextField(
                            value = customStartText,
                            onValueChange = { customStartText = it.take(10); selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null },
                            label = "开始日期（YYYY-MM-DD）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = customEndText,
                            onValueChange = { customEndText = it.take(10); selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null },
                            label = "结束日期（YYYY-MM-DD）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if ((customStartText.isNotBlank() && customStart == null) ||
                            (customEndText.isNotBlank() && customEnd == null) ||
                            (customStart != null && customEnd != null && customStart.isAfter(customEnd))
                        ) {
                            Text("请输入有效日期，并确保开始日期不晚于结束日期。", color = MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
                MiuixDialogActions(confirmText = "完成", cancelText = "重置", onCancel = {
                    source = null; model = null; provider = null; project = null; status = null
                    period = 4; customStartText = ""; customEndText = ""; selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null
                }, onConfirm = { filtersOpen = false })
            }
            WindowDialog(show = confirmClear, title = "清除已完成记录", summary = stringResource(R.string.metrics_clear_note), onDismissRequest = { if (!clearing) confirmClear = false }) {
                MiuixDialogActions(confirmText = "清除", destructive = true, confirmEnabled = !clearing, cancelEnabled = !clearing, onCancel = { confirmClear = false }, onConfirm = {
                    clearing = true
                    scope.launch {
                        try { AgentRunMetricsStore.clear(context); confirmClear = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true; confirmClear = false }
                        finally { clearing = false }
                    }
                })
            }
        }) {
        if (selectedTab == 0) item("overview") {
            AgentMetricsOverview(filtered, selectedModels, usages, costAvailable = !failed && !loading)
        }
        if (selectedTab == 0) item("request_timing") { AgentRequestTimingCard(timingSummaries.filter { it.recordId in ids && (provider == null || it.providerId == provider) && (model == null || "${it.providerId}:${it.modelId}" == model) }) }
        if (selectedTab == 1) item("monitoring") { AgentMetricsMonitoring(filtered, usages, approvalUsages) }
        item("load_status") {
            if (loading) BasicComponent(title = "正在加载运行记录")
            if (failed) ArrowPreference(title = "记录加载失败，点击重试", onClick = { loading = true; reload++ })
            if (activeFilters > 0) ArrowPreference(title = "已启用 $activeFilters 项筛选", onClick = { filtersOpen = true })
        }
        if (selectedTab != 2) {
            item("range") {
                MetricsRangeSelector(period) { period = it; selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null }
                if (period == 3) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            customStart?.toString() ?: "未设置开始日期",
                            Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                            color = if (customStartText.isNotBlank() && customStart == null) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            customEnd?.toString() ?: "未设置结束日期",
                            Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                            color = if (customEndText.isNotBlank() && customEnd == null) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (selectedTab == 0) {
            item("activity_heatmap") { AgentMetricsHeatmap(timeline, tokenMode = true) { start, end ->
                selectedWindowStart = start; selectedWindowEnd = end; selectedTab = 1
            } }
            item("distribution") {
                SmallTitle("使用分布")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentMetricDistributionCard("模型 Token 分布", modelDistribution)
                    AgentMetricDistributionCard("来源 Token 分布", sourceDistribution)
                }
            }
            item("trends") {
                SmallTitle("使用趋势")
                AgentAdaptiveTrendCard(timeline, tokenMode = true, onSelectWindow = { start, end ->
                    selectedWindowStart = start; selectedWindowEnd = end; selectedTab = 1
                })
            }
        }
        if (selectedTab == 1) {
            item("monitoring_heatmap") { AgentMetricsHeatmap(timeline) { start, end ->
                selectedWindowStart = start; selectedWindowEnd = end
            } }
            item("run_timeline") {
                AgentAdaptiveTrendCard(timeline, tokenMode = false, onSelectWindow = { start, end -> selectedWindowStart = start; selectedWindowEnd = end })
            }
            item("history") {
                SmallTitle(if(selectedWindowStart != null && selectedWindowEnd != null) {
                    val format = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
                    val zone = java.time.ZoneId.systemDefault()
                    "${java.time.Instant.ofEpochMilli(selectedWindowStart!!).atZone(zone).format(format)} — ${java.time.Instant.ofEpochMilli(selectedWindowEnd!!).atZone(zone).format(format)} · ${shown.size} 次运行"
                } else day?.let { "${if (endDay == day) it.toString() else "$it — $endDay"} · ${shown.size} 次运行" } ?: "运行记录 · ${shown.size}")
                if (day != null || selectedWindowStart != null) ArrowPreference(title = "查看全部日期", onClick = { selectedDay = null; selectedEndDay = null; selectedWindowStart = null; selectedWindowEnd = null })
            }
            if (shown.isEmpty() && !loading && !failed) item("empty") {
                Text("暂无符合条件的记录", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MiuixTheme.textStyles.body2)
            }
            items(shown, key = { it.recordId }) { record ->
                val runModels = selectedModels.filter { it.recordId == record.recordId }
                AgentRunMetricsCard(
                    record = record,
                    models = runModels,
                    providerName = runModels.firstOrNull()?.providerId?.let(::providerName).orEmpty(),
                    projectName = projects.firstOrNull { it.id == record.projectId }?.name.orEmpty(),
                    costLabel = "",
                    onClick = { onOpenRun(record.recordId) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
        }
        if (selectedTab == 2) {
            item("management") {
                SmallTitle("统计管理")
                NexusSettingsCard(Modifier.padding(horizontal = 16.dp)) {
                    ArrowPreference(title = "模型计价", summary = "设置单价后用于新运行的费用估算", startAction = { PreferenceIcon(Icons.Rounded.Payments) }, onClick = onOpenPrices)
                    AgentCnyRateControls()
                    AgentMetricsExportControls(filtered.map { it.recordId }, enabled = !loading && !failed && !clearing)
                    ArrowPreference(title = "清除已完成记录", enabled = !clearing && records.any { it.status != AgentMetricStatus.RUNNING }, onClick = { confirmClear = true })
                }
                Text(stringResource(R.string.metrics_retention_note), Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

internal fun metricDateInPeriod(
    date: LocalDate?,
    period: Int,
    customStart: LocalDate?,
    customEnd: LocalDate?,
    today: LocalDate,
): Boolean {
    // A run with an unknown start date cannot be proven to fall in a bounded window; it stays
    // visible only under the unfiltered period.
    if (date == null) return period == 4
    val start = when (period) {
        0 -> today
        1 -> today.minusDays(6)
        2 -> today.minusDays(29)
        3 -> customStart
        else -> null
    }
    val end = if (period == 3) customEnd else today
    return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
}

internal fun metricWindowDays(
    period: Int,
    earliestDay: LocalDate?,
    today: LocalDate,
    customStart: LocalDate?,
    customEnd: LocalDate?,
): Int = when (period) {
    0 -> 1
    1 -> 7
    2 -> 30
    3 -> {
        val start = customStart ?: earliestDay
        val end = customEnd ?: today
        start?.let { (end.toEpochDay() - it.toEpochDay() + 1).toInt().coerceAtLeast(1) } ?: 1
    }
    else -> earliestDay?.let { (today.toEpochDay() - it.toEpochDay() + 1).toInt().coerceAtLeast(1) } ?: 1
}

@Composable
private fun MetricsRangeSelector(selected: Int, onSelected: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    NexusSettingsCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            ,
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("统计周期", Modifier.weight(1f), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                Text(
                    when (selected) { 0 -> "今天"; 1 -> "最近 7 天"; 2 -> "最近 30 天"; 3 -> "自定义范围"; else -> "全部留存记录" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            NexaSegmentedTabs(
                tabs = listOf("今天", "7 天", "30 天", "自定义", "全部"),
                selectedTabIndex = selected,
                onTabSelected = onSelected,
            )
        }
    }
}

@Composable internal fun metricNumber(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: stringResource(R.string.metrics_unknown)
@Composable internal fun metricSourceLabel(source: String): String = stringResource(when (source) {
    AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE -> R.string.metrics_source_chat
    AgentRuntimeWire.MEETING_HANDOFF_SOURCE -> R.string.metrics_source_meeting
    AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE -> R.string.metrics_source_voice
    "scheduled_task" -> R.string.metrics_source_schedule
    "legacy" -> R.string.metrics_source_legacy
    else -> R.string.metrics_source_external
})
@Composable internal fun metricStatusLabel(status: String): String = stringResource(when (status) {
    AgentMetricStatus.RUNNING -> R.string.metrics_status_running
    AgentMetricStatus.SUCCEEDED -> R.string.metrics_status_succeeded
    AgentMetricStatus.FAILED -> R.string.metrics_status_failed
    AgentMetricStatus.CANCELLED -> R.string.metrics_status_cancelled
    AgentMetricStatus.INTERRUPTED -> R.string.metrics_status_interrupted
    else -> R.string.metrics_status_legacy
})






