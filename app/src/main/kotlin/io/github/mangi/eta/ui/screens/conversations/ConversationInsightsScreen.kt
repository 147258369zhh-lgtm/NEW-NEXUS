package io.github.mangi.eta.ui.screens.conversations

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.AgentMetricModelEntity
import io.github.mangi.eta.agent.runtime.AgentMetricOverview
import io.github.mangi.eta.agent.runtime.AgentRequestTimingSummary
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.data.repository.SubjectFilesRepository
import io.github.mangi.eta.data.repository.SubjectInsightFile
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.metrics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.NexaCompactAction
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationInsightsScreen(
    conversationId: String,
    meetingRoomId: String,
    onBack: () -> Unit,
    onDshCommand: (String, String) -> Boolean = { _, _ -> false },
    onRunClick: (String) -> Unit = {},
    onArtifactClick: (String) -> Unit = {},
) {
    val uiContext = LocalContext.current
    val context = uiContext.applicationContext
    val scope = rememberCoroutineScope()
    val type = if (meetingRoomId.isNotBlank()) "meeting" else "conversation"
    val subjectId = if (meetingRoomId.isNotBlank()) meetingRoomId else conversationId
    var tab by rememberSaveable(subjectId, type) { mutableIntStateOf(0) }
    var refresh by remember(subjectId, type) { mutableIntStateOf(0) }
    var loading by remember(subjectId, type) { mutableStateOf(true) }
    var fileLoading by remember(subjectId, type) { mutableStateOf(false) }
    var runs by remember(subjectId, type) { mutableStateOf(emptyList<AgentMetricOverview>()) }
    var requestTimings by remember(subjectId, type) { mutableStateOf(emptyList<AgentRequestTimingSummary>()) }
    var files by remember(subjectId, type) { mutableStateOf(emptyList<SubjectInsightFile>()) }
    var error by remember(subjectId, type) { mutableStateOf<String?>(null) }
    var busy by remember(subjectId, type) { mutableStateOf(false) }
    var selectedWindow by remember(subjectId, type) { mutableStateOf<Pair<Long, Long>?>(null) }
    var exportTarget by remember { mutableStateOf<SubjectInsightFile?>(null) }
    var importTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val access = remember(context) { NexusArtifactAccess(context) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = exportTarget
        exportTarget = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && target != null) scope.launch {
            busy = true
            try { access.exportTo(target.artifact.id, uri); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "导出失败：${failure.localizedMessage ?: "无法写入文件"}" }
            finally { busy = false }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val owner = importTarget
        importTarget = null
        if (uris.isNotEmpty() && owner != null) scope.launch {
            busy = true
            try { SubjectFilesRepository.importDocuments(context, owner.first, owner.second, uris); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.localizedMessage ?: "导入失败" }
            finally { busy = false; refresh++ }
        }
    }
    LaunchedEffect(subjectId, type, refresh) {
        if (subjectId.isBlank()) { loading = false; return@LaunchedEffect }
        try {
            AgentRunMetricsStore.subjectOverview(context, type, subjectId).collectLatest { all ->
                runs = all.filter { if (type == "meeting") it.run.roomId == subjectId else it.run.conversationId == subjectId && it.run.roomId.isBlank() }
                loading = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loading = false; error = "读取失败：${failure.localizedMessage ?: "请重试"}" }
    }
    LaunchedEffect(subjectId, type, refresh) {
        if (subjectId.isBlank()) return@LaunchedEffect
        try { AgentRunMetricsStore.subjectRequestTimingSummaries(context, type, subjectId).collectLatest { requestTimings = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "请求耗时读取失败：${failure.localizedMessage ?: "请重试"}" }
    }
    LaunchedEffect(subjectId, type, tab, refresh) {
        if (subjectId.isBlank() || tab != 2) return@LaunchedEffect
        files = emptyList()
        fileLoading = true
        try {
            SubjectFilesRepository.observeFiles(context, type, subjectId).collectLatest {
                files = it
                fileLoading = false
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "文件读取失败：${failure.localizedMessage ?: "请重试"}" }
        finally { fileLoading = false }
    }
    val records = runs.map { it.run }
    val visibleRecords = records.filter { record -> selectedWindow?.let { record.startedAt >= it.first && record.startedAt < it.second } ?: true }
    val models = runs.flatMap(::metricScopedModels)
    val metricsByRun = models.groupBy(AgentMetricModelEntity::recordId).mapValues { (_, values) -> values.map { it.metrics } }
    val usages = records.flatMap { metricsByRun[it.recordId].orEmpty().ifEmpty { listOf(it.metrics) } }
    val timeline = adaptiveMetricTimeline(records, metricsByRun)
    val inputs = files.filter { it.input }
    val outputs = files.filterNot { it.input }
    fun export(file: SubjectInsightFile) {
        exportTarget = file
        exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = NexusArtifactMedia.mimeType(file.artifact.mediaType, file.artifact.label, file.artifact.path)
            putExtra(Intent.EXTRA_TITLE, file.artifact.label)
        })
    }
    fun share(file: SubjectInsightFile) {
        scope.launch {
            busy = true
            try {
                val (uri, mime) = access.shareUri(file.artifact.id)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    this.type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(file.artifact.label, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                uiContext.startActivity(Intent.createChooser(intent, "分享文件"))
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "分享失败：${failure.localizedMessage ?: "无法打开分享"}" }
            finally { busy = false }
        }
    }
    MiuixScaffoldPage(title = if (type == "meeting") "会议室详情" else "会话详情", onBack = onBack, staticTopBarBackdrop = true) {
        item("conversation_plugins") {
            io.github.mangi.eta.ui.screens.capability.DshSessionActionsEntry(
                if (type == "meeting") ConversationSubject.meeting(subjectId) else ConversationSubject.chat(subjectId.takeIf(String::isNotBlank)),
                onCommand = onDshCommand)
        }
        item("conversation_insight_tabs") {
            NexaSegmentedTabs(tabs = listOf("运行统计", "运行监测", "项目文件"), selectedTabIndex = tab, onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (loading && tab != 2) item("conversation_insight_loading") {
            Text("正在汇总当前会话…", Modifier.padding(horizontal = 24.dp, vertical = 18.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        error?.let { message -> item("conversation_insight_error") {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(message, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                NexaCompactAction("重试", onClick = { error = null; refresh++ })
            }
        } }
        if (!loading && tab == 0) {
            item("conversation_metric_overview") {
                SmallTitle(if (type == "meeting") "会议室运行统计" else "对话运行统计")
                AgentMetricsOverview(records, models, usages, costAvailable = true)
            }
            item("conversation_request_timing") { val ids = records.map { it.recordId }.toSet(); AgentRequestTimingCard(requestTimings.filter { it.recordId in ids }) }
            item("conversation_token_trend") { AgentAdaptiveTrendCard(timeline, tokenMode = true, onSelectWindow = { start, end -> selectedWindow = start to end }) }
            item("conversation_activity_heatmap") { io.github.mangi.eta.ui.screens.metrics.AgentMetricsHeatmap(timeline, tokenMode = true) { start, end -> selectedWindow = start to end } }
            item("conversation_model_distribution") {
                AgentMetricDistributionCard("模型 Token 分布", models.groupBy { Triple(it.providerId, it.modelId, it.name) }.map { (key, values) ->
                    MetricDistribution(key.third.ifBlank { key.second }, metricKnownSum(values.map { it.metrics }) { it.reportedTotalTokens() },
                        "${values.sumOf { it.metrics.requestAttempts }} 请求 · ${key.first}", incomplete = values.any { it.metrics.reportedTotalTokens() == null || !it.metrics.usageComplete })
                })
            }
            item("conversation_source_distribution") {
                AgentMetricDistributionCard("来源 Token 分布", records.groupBy { it.source }.map { (source, grouped) ->
                    val samples = grouped.flatMap { metricsByRun[it.recordId].orEmpty().ifEmpty { listOf(it.metrics) } }
                    MetricDistribution(metricSourceLabel(source), metricKnownSum(samples) { it.reportedTotalTokens() },
                        "${grouped.size} 次运行 · ${samples.sumOf { it.requestAttempts }} 请求",
                        incomplete = samples.any { it.reportedTotalTokens() == null || !it.usageComplete })
                })
            }
        }
        if (!loading && tab == 1) {
            item("conversation_monitoring_overview") { AgentMetricsMonitoring(records, usages, approvalMetrics = records.map { it.metrics } + runs.flatMap { it.children.map { child -> child.metrics } }) }
            item("conversation_monitoring_heatmap") { io.github.mangi.eta.ui.screens.metrics.AgentMetricsHeatmap(timeline) { start, end -> selectedWindow = start to end } }
            item("conversation_monitoring_trend") { AgentAdaptiveTrendCard(timeline, tokenMode = false, onSelectWindow = { start, end -> selectedWindow = start to end }) }
        }
        if (!loading && tab in 0..1) {
            item("conversation_runs") { SmallTitle("运行记录 · ${visibleRecords.size}"); if (selectedWindow != null) NexaCompactAction("显示全部记录", onClick = { selectedWindow = null }, modifier = Modifier.padding(horizontal = 12.dp)) }
            if (visibleRecords.isEmpty()) item("conversation_runs_empty") { InsightFileEmpty("暂无运行记录", "当前会话的运行统计会在执行后自动更新") }
            visibleRecords.forEach { record -> item("conversation_run_${record.recordId}") {
                AgentRunMetricsCard(record, models.filter { it.recordId == record.recordId }, providerName = "", projectName = "",
                    costLabel = "", onClick = { onRunClick(record.recordId) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            } }
        }
        if (tab == 2) {
            item("conversation_files_summary") {
                NexaSubjectFilePanel(
                    ownerKey = "$type:$subjectId", inputs = inputs, outputs = outputs, busy = busy, loading = fileLoading,
                    importEnabled = subjectId.isNotBlank(),
                    onImport = { if (!busy && subjectId.isNotBlank()) { importTarget = type to subjectId; importLauncher.launch(arrayOf("*/*")) } },
                    onRefresh = { if (!busy && !fileLoading) refresh++ },
                    onPreview = { onArtifactClick(it.artifact.id) }, onExport = { export(it) }, onShare = { share(it) },
                )
            }
        }
    }
}

@Composable
private fun InsightFileEmpty(title: String, summary: String) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ListEmptyState(title = title, summary = summary, icon = Icons.Rounded.Description)
    }
}








