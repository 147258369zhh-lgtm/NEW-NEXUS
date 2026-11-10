package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
    val records = snapshots.map { it.run }
    val modelRecords = snapshots.flatMap { it.models + it.children.map { child -> child.asModel() } }
    val projects by remember(context) { ProjectRepository.projects(context).catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val providerSettings by remember(context) { flow { emitAll(ProviderRepository.providersFlow()) }.catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    fun providerName(id: String) = providerSettings.firstOrNull { it.id == id }?.name ?: id
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableIntStateOf(1) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.metrics_filter_all)
    val sources = records.map { it.source }.distinct().sorted()
    val models = modelRecords.distinctBy { "${it.providerId}:${it.modelId}" }
    val providers = modelRecords.map { it.providerId }.filter { it.isNotBlank() }.distinct().sorted()
    val projectIds = records.map { it.projectId }.filter(String::isNotBlank).distinct()
    val statuses = listOf(AgentMetricStatus.RUNNING, AgentMetricStatus.SUCCEEDED, AgentMetricStatus.FAILED, AgentMetricStatus.CANCELLED, AgentMetricStatus.INTERRUPTED, AgentMetricStatus.LEGACY)
    val today = LocalDate.now()
    val cutoff = when (period) { 0 -> today; 1 -> today.minusDays(6); 2 -> today.minusDays(29); else -> null }
    val matchingModels = modelRecords.filter { (provider == null || provider == it.providerId) && (model == null || model == "${it.providerId}:${it.modelId}") }
    val matchingIds = matchingModels.map { it.recordId }.toSet()
    val filtered = records.filter { (source == null || source == it.source) &&
        (model == null && provider == null || it.recordId in matchingIds) && (project == null || project == it.projectId) &&
        (status == null || status == it.status) && (cutoff == null || !AgentMetricsActivity.date(it.startedAt).isBefore(cutoff)) }
    val ids = filtered.map { it.recordId }.toSet()
    val selectedModels = matchingModels.filter { it.recordId in ids }
    val metricsByRun = selectedModels.groupBy { it.recordId }.mapValues { it.value.map { model -> model.metrics } }
    val usages = filtered.flatMap { metricsByRun[it.recordId] ?: listOf(it.metrics) }
    val modelDistribution = selectedModels.groupBy { Triple(it.providerId, it.modelId, it.name) }
        .map { (key, values) ->
            MetricDistribution(
                label = key.third.ifBlank { key.second },
                value = values.sumOf { it.metrics.reportedTotalTokens() ?: 0L },
                detail = "${providerName(key.first)} · ${values.sumOf { it.metrics.requestAttempts }} 请求",
            )
        }
    val sourceDistribution = filtered.groupBy { it.source }
        .map { (source, records) ->
            MetricDistribution(
                label = metricSourceLabel(source),
                value = records.sumOf { it.metrics.reportedTotalTokens() ?: 0L },
                detail = "${records.size} 次运行 · ${records.sumOf { it.metrics.requestAttempts }} 请求",
            )
        }
    val earliestDay = filtered.minOfOrNull { AgentMetricsActivity.date(it.startedAt) }
    val windowDays = when (period) {
        0 -> 1
        1 -> 7
        2 -> 30
        else -> earliestDay?.let { (today.toEpochDay() - it.toEpochDay() + 1).toInt().coerceAtLeast(1) } ?: 1
    }
    val activity = AgentMetricsActivity.days(filtered, metricsByRun, daysBack = windowDays)
    val day = selectedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val shown = filtered.filter { day == null || AgentMetricsActivity.date(it.startedAt) == day }
    val activeFilters = listOf(source, model, provider, project, status).count { it != null }

    MiuixScaffoldPage(title = stringResource(R.string.route_agent_metrics), onBack = onBack,
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
                }
                MiuixDialogActions(confirmText = "完成", cancelText = "重置", onCancel = { source = null; model = null; provider = null; project = null; status = null; period = 1; selectedDay = null }, onConfirm = { filtersOpen = false })
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
        item("overview") {
            AgentMetricsOverview(filtered, selectedModels, usages, costAvailable = !failed && !loading)
            if (loading) BasicComponent(title = "正在加载运行记录")
            if (failed) ArrowPreference(title = "记录加载失败，点击重试", onClick = { loading = true; reload++ })
            if (activeFilters > 0) ArrowPreference(title = "已启用 $activeFilters 项筛选", onClick = { filtersOpen = true })
        }
        item("range") {
            MetricsRangeSelector(period) { period = it; selectedDay = null }
        }
        item("activity") {
            SmallTitle("活跃轨迹")
            AgentActivityCalendar(activity, day) { selectedDay = it?.toString() }
        }
        item("distribution") {
            SmallTitle("使用分布")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentMetricDistributionCard("模型 Token 分布", modelDistribution)
                AgentMetricDistributionCard("来源 Token 分布", sourceDistribution)
            }
        }
        item("trends") {
            SmallTitle("使用趋势")
            AgentMetricsTrendCard(AgentMetricsTrend.window(filtered, daysBack = windowDays, usages = metricsByRun))
        }
        item("history") {
            SmallTitle(day?.let { "$it · ${shown.size} 次运行" } ?: "运行记录 · ${shown.size}")
            if (day != null) ArrowPreference(title = "查看全部日期", onClick = { selectedDay = null })
        }
        if (shown.isEmpty() && !loading && !failed) item("empty") {
            Text("暂无符合条件的记录", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MiuixTheme.textStyles.body2)
        }
        items(shown, key = { it.recordId }) { record ->
            val runModels = selectedModels.filter { it.recordId == record.recordId }
            val costs = AgentTokenCostEstimator.summarize(runModels)
            val money = costs.currencies.joinToString(" · ") { "${it.currency} ${metricMoney(it.amount)}" }
            AgentRunMetricsCard(
                record = record,
                models = runModels,
                providerName = runModels.firstOrNull()?.providerId?.let(::providerName).orEmpty(),
                projectName = projects.firstOrNull { it.id == record.projectId }?.name.orEmpty(),
                costLabel = money.takeIf { it.isNotBlank() }?.let {
                    "$it${if (costs.completeModels < costs.totalModels) " · 部分" else ""}"
                }.orEmpty(),
                onClick = { onOpenRun(record.recordId) },
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
        item("management") {
            SmallTitle("统计管理")
            Card(Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(title = "模型计价", summary = "设置单价后用于新运行的费用估算", startAction = { PreferenceIcon(Icons.Rounded.Payments) }, onClick = onOpenPrices)
                AgentMetricsExportControls(shown.map { it.recordId }, enabled = !loading && !failed && !clearing)
                ArrowPreference(title = "清除已完成记录", enabled = !clearing && records.any { it.status != AgentMetricStatus.RUNNING }, onClick = { confirmClear = true })
            }
            Text(stringResource(R.string.metrics_retention_note), Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun MetricsRangeSelector(selected: Int, onSelected: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("统计周期", Modifier.weight(1f), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                Text(
                    when (selected) { 0 -> "今天"; 1 -> "最近一周"; 2 -> "最近一月"; else -> "全部记录" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            TabRow(
                tabs = listOf("今天", "7 天", "30 天", "全部"),
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
