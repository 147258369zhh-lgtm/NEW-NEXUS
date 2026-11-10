package io.github.mangi.eta.ui.screens.conversations

import android.content.Context
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.runtime.AgentMetricModelEntity
import io.github.mangi.eta.agent.runtime.AgentMetricOverview
import io.github.mangi.eta.agent.runtime.AgentMetricsActivity
import io.github.mangi.eta.agent.runtime.AgentMetricsTrend
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.metrics.AgentMetricDistributionCard
import io.github.mangi.eta.ui.screens.metrics.AgentMetricsOverview
import io.github.mangi.eta.ui.screens.metrics.AgentTokenUsageTrendCard
import io.github.mangi.eta.ui.screens.metrics.MetricDistribution
import io.github.mangi.eta.ui.screens.metrics.metricSourceLabel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class InsightFileDirection { Input, Output }

private data class InsightFile(
    val name: String,
    val path: String,
    val direction: InsightFileDirection,
    val source: String,
)

private data class ConversationInsightSnapshot(
    val runs: List<AgentMetricOverview> = emptyList(),
    val files: List<InsightFile> = emptyList(),
)

@Composable
internal fun ConversationInsightsScreen(
    conversationId: String,
    meetingRoomId: String,
    onBack: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = uiContext.applicationContext
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf(ConversationInsightSnapshot()) }
    var exportTarget by remember { mutableStateOf<InsightFile?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        runCatching {
            val source = File(target.path)
            require(source.isFile)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入导出文件")
        }
    }
    fun shareFile(file: InsightFile) {
        runCatching {
            val source = File(file.path)
            require(source.isFile)
            val directory = File(context.cacheDir, "conversation-share").apply { mkdirs() }
            val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
            val copy = File(directory, "${UUID.randomUUID()}-$safeName")
            source.copyTo(copy, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            uiContext.startActivity(Intent.createChooser(send, "分享文件"))
        }
    }
    LaunchedEffect(conversationId, meetingRoomId) {
        loading = true
        snapshot = withContext(Dispatchers.IO) { loadInsightSnapshot(context, conversationId, meetingRoomId) }
        loading = false
    }
    val records = snapshot.runs.map { it.run }
    val models = snapshot.runs.flatMap { it.models + it.children.map { child -> child.asModel() } }
    val metricsByRun = models.groupBy(AgentMetricModelEntity::recordId).mapValues { (_, values) -> values.map { it.metrics } }
    val usages = records.flatMap { metricsByRun[it.recordId] ?: listOf(it.metrics) }
    val modelDistribution = models.groupBy { Triple(it.providerId, it.modelId, it.name) }.map { (key, values) ->
        MetricDistribution(
            label = key.third.ifBlank { key.second },
            value = values.sumOf { it.metrics.reportedTotalTokens() ?: 0L },
            detail = "${values.sumOf { it.metrics.requestAttempts }} 请求 · ${key.first}",
        )
    }
    MiuixScaffoldPage(title = if (meetingRoomId.isNotBlank()) "会议室详情" else "会话详情", onBack = onBack) {
        item("conversation_insight_tabs") {
            TabRow(
                tabs = listOf("运行统计", "项目文件"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (loading) item("conversation_insight_loading") {
            Text("正在汇总当前会话…", Modifier.padding(horizontal = 24.dp, vertical = 18.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (!loading && tab == 0) {
            item("conversation_metric_overview") {
                SmallTitle(if (meetingRoomId.isNotBlank()) "会议室运行概况" else "对话运行概况")
                AgentMetricsOverview(records, models, usages, costAvailable = true)
            }
            item("conversation_token_trend") {
                AgentTokenUsageTrendCard(AgentMetricsTrend.lastWeek(records, usages = metricsByRun))
            }
            item("conversation_model_distribution") {
                AgentMetricDistributionCard("模型 Token 分布", modelDistribution)
            }
            item("conversation_runs") { SmallTitle("运行记录 · ${records.size}") }
            if (records.isEmpty()) item("conversation_runs_empty") {
                Card(Modifier.padding(horizontal = 12.dp)) {
                    ListEmptyState(title = "暂无运行统计", summary = "完成一次对话或会议室处理后，这里会显示 Token、速度、费用和工具执行。", icon = Icons.Rounded.Description)
                }
            }
        }
        if (!loading && tab == 1) {
            val inputs = snapshot.files.filter { it.direction == InsightFileDirection.Input }
            val outputs = snapshot.files.filter { it.direction == InsightFileDirection.Output }
            item("conversation_files_summary") {
                SmallTitle("项目文件")
                Card(Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("导入文件", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(inputs.size.toString(), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("输出文件", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(outputs.size.toString(), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item("conversation_input_files") { SmallTitle("导入文件 · ${inputs.size}") }
            if (inputs.isEmpty()) item("conversation_inputs_empty") { InsightFileEmpty("本次会话还没有导入文件") }
            inputs.forEachIndexed { index, file -> item("insight_input_$index") {
                InsightFileCard(file, onExport = { exportTarget = file; exportLauncher.launch(file.name) }, onShare = { shareFile(file) })
            } }
            item("conversation_output_files") { SmallTitle("输出文件 · ${outputs.size}") }
            if (outputs.isEmpty()) item("conversation_outputs_empty") { InsightFileEmpty("还没有识别到工具生成的正常文件") }
            outputs.forEachIndexed { index, file -> item("insight_output_$index") {
                InsightFileCard(file, onExport = { exportTarget = file; exportLauncher.launch(file.name) }, onShare = { shareFile(file) })
            } }
        }
    }
}

private suspend fun loadInsightSnapshot(context: Context, conversationId: String, meetingRoomId: String): ConversationInsightSnapshot {
    val overview = AgentRunMetricsStore.overview(context).first()
    val runs = overview.filter {
        if (meetingRoomId.isNotBlank()) it.run.roomId == meetingRoomId else it.run.conversationId == conversationId
    }
    val files = mutableListOf<InsightFile>()
    if (meetingRoomId.isNotBlank()) {
        val meeting = MeetingRepository.snapshot(context, meetingRoomId).first()
        meeting?.messageAttachments?.values?.forEach { attachment ->
            attachment.files.forEach { item ->
                files += InsightFile(
                    name = item.reference.displayName,
                    path = item.reference.absolutePath,
                    direction = InsightFileDirection.Input,
                    source = "会议室附件",
                )
            }
            if (attachment.images.isNotEmpty()) {
                files += InsightFile("输入图片 ${attachment.images.size} 张", "", InsightFileDirection.Input, "会议室附件")
            }
        }
        meeting?.timelineEvents?.forEach { event ->
            if (event.type.contains("tool") || event.type.contains("artifact")) {
                files += extractOutputFiles(event.payloadJson, "会议室工具")
            }
        }
    } else {
        val messages = EtaDatabase.get(context).conversationDao().messages().filter { it.conversationId == conversationId }
        messages.forEach { message ->
            if (message.type == "user") {
                AgentFileReferencePromptCodec.parse(message.content).references.forEach { reference ->
                    files += InsightFile(
                        name = reference.displayName,
                        path = reference.absolutePath,
                        direction = InsightFileDirection.Input,
                        source = if (reference.kind == AgentFileReferenceKind.Directory) "目录" else "用户文件",
                    )
                }
                val imageCount = runCatching { JSONArray(message.imagesJson).length() }.getOrDefault(0)
                if (imageCount > 0) files += InsightFile("输入图片 $imageCount 张", "", InsightFileDirection.Input, "用户图片")
            }
            if (message.toolName != null && message.toolStatus.equals("Success", ignoreCase = true)) {
                files += extractOutputFiles(
                    listOfNotNull(message.argumentsSummary, message.resultSummary).joinToString("\n"),
                    message.toolName,
                )
            }
        }
    }
    return ConversationInsightSnapshot(
        runs = runs,
        files = files
            .filter { it.name.isNotBlank() || it.path.isNotBlank() }
            .distinctBy { "${it.direction}:${it.path}:${it.name}" },
    )
}

private fun extractOutputFiles(raw: String, source: String): List<InsightFile> {
    if (raw.isBlank()) return emptyList()
    val structured = Regex("(?i)\"(?:path|file_path|output_path|target|destination)\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(raw)
        .map { it.groupValues[1] }
        .toList()
    val absolute = Regex("(/(?:sdcard|storage|data|home|tmp)/[^\\s\"'<>]+)")
        .findAll(raw)
        .map { it.groupValues[1] }
        .toList()
    return (structured + absolute).distinct().map { path ->
        InsightFile(
            name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path },
            path = path,
            direction = InsightFileDirection.Output,
            source = source,
        )
    }
}

@Composable
private fun InsightFileCard(
    file: InsightFile,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (file.direction == InsightFileDirection.Input) Icons.Rounded.CallReceived else Icons.Rounded.CallMade,
                contentDescription = null,
                tint = if (file.direction == InsightFileDirection.Input) Color(0xFF4C7CF3) else Color(0xFF2AA978),
                modifier = Modifier.size(19.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (file.path.isNotBlank()) Text(file.path, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (file.path.isNotBlank() && File(file.path).exists()) "可用" else file.source,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (file.path.isNotBlank() && File(file.path).isFile) {
                TextButton(text = "导出", onClick = onExport)
                TextButton(text = "分享", onClick = onShare)
            }
        }
    }
}

@Composable
private fun InsightFileEmpty(summary: String) {
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
        ListEmptyState(title = "暂无文件", summary = summary, icon = Icons.Rounded.Description)
    }
}
