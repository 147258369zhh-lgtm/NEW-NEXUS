package io.github.mangi.eta.ui.screens.tasks

import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.mangi.eta.fork.tasks.*
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.pages.providers.TagChipTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import top.yukonga.miuix.kmp.window.WindowDialog

internal enum class AutomationTriggerGroup(val label: String) {
    MANUAL("手动"),
    SCHEDULE("时间"),
    CONNECTION("连接"),
    DEVICE("设备"),
    LOCATION("位置"),
    FILE("文件"),
    TASK_CHAIN("任务链"),
}

internal fun automationTriggerGroup(type: AutomationTrigger): AutomationTriggerGroup = when (type) {
    AutomationTrigger.MANUAL -> AutomationTriggerGroup.MANUAL
    AutomationTrigger.CHARGING,
    AutomationTrigger.CHARGING_STOPPED,
    AutomationTrigger.NETWORK,
    AutomationTrigger.NETWORK_CONNECTED,
    AutomationTrigger.NETWORK_DISCONNECTED,
    AutomationTrigger.WIFI_CONNECTED,
    AutomationTrigger.WIFI_DISCONNECTED,
    AutomationTrigger.MOBILE_CONNECTED,
    AutomationTrigger.MOBILE_DISCONNECTED,
    AutomationTrigger.BLUETOOTH_CONNECTED,
    AutomationTrigger.BLUETOOTH_DISCONNECTED,
    AutomationTrigger.HEADSET_CONNECTED,
    AutomationTrigger.HEADSET_DISCONNECTED,
    AutomationTrigger.AIRPLANE_MODE_ON,
    AutomationTrigger.AIRPLANE_MODE_OFF,
    -> AutomationTriggerGroup.CONNECTION
    AutomationTrigger.UNLOCKED,
    AutomationTrigger.SCREEN_ON,
    AutomationTrigger.SCREEN_OFF,
    AutomationTrigger.BATTERY_ABOVE,
    AutomationTrigger.BATTERY_BELOW,
    AutomationTrigger.APP_START,
    AutomationTrigger.APP_OPENED,
    AutomationTrigger.BOOT,
    AutomationTrigger.STATE,
    -> AutomationTriggerGroup.DEVICE
    AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT -> AutomationTriggerGroup.LOCATION
    AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED -> AutomationTriggerGroup.FILE
    AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED, AutomationTrigger.MEMORY_UPDATED -> AutomationTriggerGroup.TASK_CHAIN
}

private fun automationTriggerIcon(type: AutomationTrigger?): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    AutomationTrigger.CHARGING, AutomationTrigger.CHARGING_STOPPED -> Icons.Rounded.BatteryChargingFull
    AutomationTrigger.NETWORK, AutomationTrigger.NETWORK_CONNECTED, AutomationTrigger.NETWORK_DISCONNECTED,
    AutomationTrigger.WIFI_CONNECTED, AutomationTrigger.WIFI_DISCONNECTED,
    AutomationTrigger.MOBILE_CONNECTED, AutomationTrigger.MOBILE_DISCONNECTED,
    -> Icons.Rounded.Wifi
    AutomationTrigger.BLUETOOTH_CONNECTED, AutomationTrigger.BLUETOOTH_DISCONNECTED -> Icons.Rounded.Bluetooth
    AutomationTrigger.HEADSET_CONNECTED, AutomationTrigger.HEADSET_DISCONNECTED -> Icons.Rounded.Headset
    AutomationTrigger.AIRPLANE_MODE_ON, AutomationTrigger.AIRPLANE_MODE_OFF -> Icons.Rounded.AirplanemodeActive
    AutomationTrigger.UNLOCKED -> Icons.Rounded.LockOpen
    AutomationTrigger.SCREEN_ON -> Icons.Rounded.LightMode
    AutomationTrigger.SCREEN_OFF -> Icons.Rounded.DarkMode
    AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW -> Icons.Rounded.BatteryAlert
    AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT -> Icons.Rounded.LocationOn
    AutomationTrigger.APP_START, AutomationTrigger.APP_OPENED -> Icons.Rounded.Apps
    AutomationTrigger.BOOT -> Icons.Rounded.RestartAlt
    AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED -> Icons.Rounded.Folder
    AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED, AutomationTrigger.MEMORY_UPDATED -> Icons.Rounded.AccountTree
    AutomationTrigger.STATE -> Icons.Rounded.Tune
    AutomationTrigger.MANUAL, null -> Icons.Rounded.PlayCircle
}

internal data class AutomationConversationDraft(
    val prompt: String,
    val projectId: String?,
    val omittedAttachments: Boolean,
)

@Composable
internal fun AgentTaskCenterScreen(initialTaskId: String? = null, initialDraft: AutomationConversationDraft? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AgentTaskDatabase.get(context) }
    val dao = db.agentTaskDao()
    val definitions by remember(dao) { dao.observeDefinitions() }.collectAsState(emptyList())
    val runs by remember(dao) { dao.observeRecentRuns() }.collectAsState(emptyList())
    val latestRuns by remember(dao) { dao.observeLatestRuns() }.collectAsState(emptyList())
    val attentionRuns by remember(dao) { dao.observeAttentionRuns() }.collectAsState(emptyList())
    val attention by remember(dao) { dao.observeOpenAttention() }.collectAsState(emptyList())
    val reviewRuns by remember(dao) { dao.observeReviewRuns() }.collectAsState(emptyList())
    val schedules by remember(dao) { dao.observeSchedules() }.collectAsState(emptyList())
    val triggers by remember(db) { db.triggerDao().observe() }.collectAsState(emptyList())
    var editing by remember { mutableStateOf<AgentTaskDefinitionEntity?>(null) }
    var creating by remember { mutableStateOf(initialDraft != null) }
    var showCreateChoice by remember { mutableStateOf(false) }
    var quickCreate by remember { mutableStateOf(initialDraft != null) }
    var conversationDraft by remember { mutableStateOf(initialDraft) }
    var consumedConversationDraft by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf(false) }
    var selectedAttention by remember { mutableStateOf<AgentTaskAttentionEntity?>(null) }
    var attentionInput by remember { mutableStateOf("") }
    var attentionBusy by remember { mutableStateOf(false) }
    var attentionError by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<AgentTaskRunEntity?>(null) }
    var pendingInitialTask by rememberSaveable(initialTaskId) { mutableStateOf(initialTaskId) }
    var message by remember { mutableStateOf("") }
    var monitor by remember { mutableStateOf(AutomationMonitorService.enabled(context)) }
    var maxConcurrentRuns by remember { mutableStateOf(AgentTaskSchedulerSettings.maxConcurrentRuns(context)) }
    val monitorRunning by AutomationMonitorService.running.collectAsState()
    fun action(block: suspend () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { message = e.message ?: "操作失败" } } }
    var category by rememberSaveable { mutableIntStateOf(0) }
    val taskListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    LaunchedEffect(definitions, pendingInitialTask) {
        pendingInitialTask?.let { id ->
            definitions.firstOrNull { it.id == id }?.let { task ->
                editing = task
                pendingInitialTask = null
            }
        }
    }
    if (creating || editing != null) {
        BackHandler { creating = false; editing = null; quickCreate = false; consumedConversationDraft = conversationDraft != null; conversationDraft = null }
        AutomationEditorScreen(editing, duplicate, quickCreate, conversationDraft,
            onBack = { creating = false; editing = null; duplicate = false; quickCreate = false; consumedConversationDraft = conversationDraft != null; conversationDraft = null })
        return
    }
    if (consumedConversationDraft) {
        BackHandler(onBack = onBack)
        LaunchedEffect(Unit) { onBack() }
        return
    }
    history?.let { run -> BackHandler { history = null }; AutomationHistoryScreen(run) { history = null }; return }
    MiuixScaffoldPage("自动任务", onBack,
        listState = if (category == 0) taskListState else historyListState,
        aboveContent = { NexusSettingsTabs(listOf(stringResource(R.string.settings_tab_tasks),
            stringResource(R.string.settings_tab_history)), category, { category = it }) },
        actions = {
        IconButton(onClick = { showCreateChoice = true }) { Icon(Icons.Rounded.Add, "创建自动任务") }
    }) {
        item(key = "automation_overview") {
            AutomationOverviewCard(
                enabledCount = definitions.count { it.enabled },
                totalCount = definitions.size,
                recentRuns = runs.size,
                monitor = monitor,
                monitorRunning = monitorRunning,
                maxConcurrentRuns = maxConcurrentRuns,
                onMonitorChange = { value ->
                    runCatching { AutomationMonitorService.setEnabled(context, value) }
                        .onSuccess { monitor = value }
                        .onFailure { message = it.message ?: "启动监听失败" }
                },
                onMaxConcurrentRunsChange = { value ->
                    maxConcurrentRuns = AgentTaskSchedulerSettings.setMaxConcurrentRuns(context, value)
                },
            )
        }
        if (message.isNotBlank()) item { AutomationMessage(message) }
        if (category == 0 && (attention.isNotEmpty() || reviewRuns.isNotEmpty())) {
            item(key = "attention_title") { SmallTitle("需要你处理 · ${attention.size + reviewRuns.size}") }
            attention.forEach { pending -> item(key = "attention:${pending.id}") {
                val taskTitle = attentionRuns.firstOrNull { it.id == pending.runId }?.let { run ->
                    definitions.firstOrNull { it.id == run.definitionId }?.title
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(taskTitle ?: "自动任务", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                        Text(pending.title, style = MiuixTheme.textStyles.body2, color = StatusWarning)
                        Text(pending.body, style = MiuixTheme.textStyles.footnote1)
                        TextButton(text = "处理", onClick = { selectedAttention = pending; attentionInput = ""; attentionError = null })
                    }
                }
            } }
            reviewRuns.forEach { run -> item(key = "review:${run.id}") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(definitions.firstOrNull { it.id == run.definitionId }?.title ?: "自动任务", style = MiuixTheme.textStyles.body1)
                        Text("远端结果待核对", color = StatusWarning)
                        run.errorMessage?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                        TextButton(text = "查看并处理", onClick = { history = run })
                    }
                }
            } }
        }
        if (category == 0) item { SmallTitle("我的任务 · ${definitions.size}", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        if (category == 0 && definitions.isEmpty()) item { ListEmptyState(title = "创建第一个自动任务", summary = "时间、事件与多步骤流程", icon = Icons.Rounded.AccountTree) }
        if (category == 0) definitions.forEach { definition -> item(key = definition.id) {
            val trigger = triggers.firstOrNull { it.definitionId == definition.id }
            val schedule = schedules.firstOrNull { it.definitionId == definition.id }
            val spec = runCatching { AutomationSpec.parse(definition.policyJson) }.getOrNull()
            val parsedTrigger = trigger?.type?.let { runCatching { AutomationTrigger.valueOf(it) }.getOrNull() }
            val taskIcon = if (schedule != null) Icons.Rounded.Schedule else automationTriggerIcon(parsedTrigger)
            val latestRun = latestRuns.firstOrNull { it.definitionId == definition.id }
            val needsAttention = attentionRuns.any { it.definitionId == definition.id } ||
                reviewRuns.any { it.definitionId == definition.id }
            AutomationTaskCard(
                title = definition.title,
                enabled = definition.enabled,
                triggerLabel = trigger?.type?.let { runCatching { AutomationTrigger.valueOf(it).label }.getOrDefault("事件") }
                    ?: schedule?.let { scheduleLabel(it.kind) }
                    ?: "手动",
                stepCount = spec?.steps?.size ?: 0,
                prompt = definition.promptTemplate,
                detail = trigger?.detail?.takeIf(String::isNotBlank),
                nextRunAt = schedule?.takeIf { definition.enabled && !it.paused }?.nextRunAt,
                latestRun = latestRun,
                needsAttention = needsAttention,
                icon = taskIcon,
                onOpen = { editing = definition },
                onOpenRun = { latestRun?.let { history = it } },
                onToggle = { action { AutomationRepository(db).setEnabled(definition.id, !definition.enabled); AgentTaskWorkScheduler.refreshNextDeadline(context) } },
                onRun = { action { checkNotNull(AgentTaskRepository(dao).runNow(definition.id)) { "任务已暂停或已有运行" }; AgentTaskWorkScheduler.checkNow(context) } },
                onDuplicate = { duplicate = true; editing = definition },
            )
        } }
        if (category == 1) item { SmallTitle("最近运行", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        if (category == 1) runs.take(40).forEach { run -> item(key = run.id) {
            DetailListRow(definitions.firstOrNull { it.id == run.definitionId }?.title ?: "任务记录",
                "${taskStateLabel(run.state)} · ${automationRunTimeSummary(run)}", Icons.Rounded.History,
                preview = run.errorMessage, onClick = { history = run })
        } }
    }
    if (showCreateChoice) {
        WindowDialog(show = true, title = "创建自动任务", onDismissRequest = { showCreateChoice = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ArrowPreference(title = "快速创建", summary = "填写任务内容和执行时间", onClick = {
                    showCreateChoice = false; duplicate = false; quickCreate = true; creating = true
                })
                ArrowPreference(title = "完整配置", summary = "事件触发、多步骤与工具设置", onClick = {
                    showCreateChoice = false; duplicate = false; quickCreate = false; creating = true
                })
                TextButton(text = stringResource(R.string.action_cancel), onClick = { showCreateChoice = false })
            }
        }
    }
    selectedAttention?.let { pending ->
        WindowDialog(show = true, title = pending.title, onDismissRequest = { if (!attentionBusy) selectedAttention = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pending.body, style = MiuixTheme.textStyles.body2)
                attentionError?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                if (pending.kind == AgentTaskAttentionKind.MISSED_RUN_DECISION) {
                    Text("补跑会进入执行队列；跳过只关闭本次提醒。", style = MiuixTheme.textStyles.footnote1)
                    MiuixDialogActions(confirmText = "现在补跑", cancelText = "跳过", confirmEnabled = !attentionBusy,
                        onCancel = {
                            if (!attentionBusy) {
                                attentionBusy = true
                                scope.launch {
                                    try {
                                        val coordinator = taskCoordinator(context, dao)
                                        if (coordinator.cancelRun(pending.runId)) selectedAttention = null else attentionError = "任务状态已变化，请返回后重试。"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { attentionError = "无法处理，请重试。" }
                                    finally { attentionBusy = false }
                                }
                            }
                        },
                        onConfirm = {
                            if (!attentionBusy) {
                                attentionBusy = true
                                scope.launch {
                                    try {
                                        if (taskCoordinator(context, dao).queueMissedRun(pending.runId)) {
                                            AgentTaskWorkScheduler.checkNow(context)
                                            selectedAttention = null
                                        } else attentionError = "任务状态已变化，请返回后重试。"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { attentionError = "无法处理，请重试。" }
                                    finally { attentionBusy = false }
                                }
                            }
                        })
                } else {
                    TextField(value = attentionInput, onValueChange = { attentionInput = it },
                        label = if (pending.kind == AgentTaskAttentionKind.AUTH_REQUIRED)
                            "授权完成后说明如何继续" else "补充输入",
                        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    MiuixDialogActions(confirmText = "继续执行", confirmEnabled = attentionInput.isNotBlank() && !attentionBusy,
                        onCancel = { selectedAttention = null }, onConfirm = {
                            if (AgentTaskBackgroundExecutor.resumeWaiting(context, pending.runId, attentionInput)) {
                                selectedAttention = null
                                android.widget.Toast.makeText(context, "已提交继续执行，请在运行详情查看状态。", android.widget.Toast.LENGTH_SHORT).show()
                            } else attentionError = "任务正在处理或输入为空，请稍后重试。"
                        })
                }
            }
        }
    }
}

@Composable
private fun AutomationOverviewCard(
    enabledCount: Int,
    totalCount: Int,
    recentRuns: Int,
    monitor: Boolean,
    monitorRunning: Boolean,
    maxConcurrentRuns: Int,
    onMonitorChange: (Boolean) -> Unit,
    onMaxConcurrentRunsChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EtaIconTile(Icons.Rounded.Bolt, tone = EtaIconTone.AUTOMATION, size = 40.dp, glyphSize = 21.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("运行设置", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$enabledCount/$totalCount 个任务启用 · $recentRuns 条最近运行",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaStatusPill(
                    text = if (monitorRunning) "监听中" else if (monitor) "待启动" else "按周期检查",
                    tone = if (monitorRunning) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                )
            }
            if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text("实时监听", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    Text(
                        if (monitorRunning) "设备事件与应用目录正常监听" else "连接、文件与系统事件",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                Switch(
                    checked = monitor,
                    onCheckedChange = onMonitorChange,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            AutomationNumber(
                title = "同时运行上限",
                value = maxConcurrentRuns,
                range = 1..AgentTaskSchedulerSettings.MAX_CONCURRENT_RUNS,
                onChange = onMaxConcurrentRunsChange,
            )
            Text(
                "超出的任务继续排队，不会并行执行同一个运行实例。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            }
        }
    }
}

@Composable
private fun AutomationTaskCard(
    title: String,
    enabled: Boolean,
    triggerLabel: String,
    stepCount: Int,
    prompt: String,
    detail: String?,
    nextRunAt: Long?,
    latestRun: AgentTaskRunEntity?,
    needsAttention: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOpen: () -> Unit,
    onOpenRun: () -> Unit,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EtaIconTile(icon, tone = EtaIconTone.AUTOMATION)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        EtaStatusPill(
                            text = if (enabled) "已启用" else "已暂停",
                            tone = if (enabled) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                        )
                    }
                    Text(
                        prompt,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagChip(triggerLabel, TagChipTone.Emphasized)
                        TagChip("$stepCount 步")
                        detail?.takeIf(String::isNotBlank)?.let { TagChip(it.take(38)) }
                    }
                    nextRunAt?.let {
                        Text("下次执行 · ${automationTime(it)}", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    latestRun?.let {
                        Text("最近运行 · ${taskStateLabel(it.state)} · ${automationTime(it.createdAt)}",
                            modifier = Modifier.clickable(onClick = onOpenRun),
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    }
                    if (needsAttention) Text("有事项需要处理", style = MiuixTheme.textStyles.footnote1, color = StatusWarning)
                }
            }
            HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine.copy(alpha = .65f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onToggle) {
                    Icon(if (enabled) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (enabled) "暂停" else "恢复")
                }
                IconButton(onClick = onRun) { Icon(Icons.Rounded.PlayCircle, "立即运行") }
                IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, "复制") }
            }
        }
    }
}

@Composable
private fun AutomationEditorScreen(initial: AgentTaskDefinitionEntity?, duplicate: Boolean = false, quickCreate: Boolean = false,
    conversationDraft: AutomationConversationDraft? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AgentTaskDatabase.get(context) }
    val dao = db.agentTaskDao()
    var title by remember { mutableStateOf(initial?.title.orEmpty() + if (duplicate) " 副本" else "") }
    var prompt by remember { mutableStateOf(initial?.promptTemplate ?: conversationDraft?.prompt.orEmpty()) }
    var spec by remember { mutableStateOf(runCatching { AutomationSpec.parse(initial?.policyJson ?: "{}") }
        .getOrDefault(AutomationSpec()).let { if (initial == null) it.copy(projectId = conversationDraft?.projectId) else it }) }
    var timed by remember { mutableStateOf(initial == null && conversationDraft == null) }
    var scheduleKind by remember { mutableStateOf(AgentScheduleKind.DAILY) }
    var hour by remember { mutableIntStateOf(8) }; var minute by remember { mutableIntStateOf(0) }
    var interval by remember { mutableIntStateOf(60) }; var monthDay by remember { mutableIntStateOf(1) }
    var weekdays by remember { mutableIntStateOf(1) }
    var missedPolicy by remember { mutableStateOf(MissedRunPolicy.RUN_ONCE) }
    var date by remember { mutableStateOf(java.time.LocalDate.now().plusDays(1).toString()) }
    var trigger by remember { mutableStateOf(AutomationTrigger.MANUAL) }
    var triggerGroup by remember { mutableStateOf(AutomationTriggerGroup.MANUAL) }
    var source by remember { mutableStateOf("") }; var extension by remember { mutableStateOf("xlsx") }
    var dependencySourceIds by remember { mutableStateOf(emptyList<String>()) }
    var dependencyMode by remember { mutableStateOf(TaskDependencyConfig.Mode.ALL) }
    var batteryThreshold by remember { mutableIntStateOf(20) }
    var locationLatitude by remember { mutableStateOf("") }
    var locationLongitude by remember { mutableStateOf("") }
    var locationRadius by remember { mutableIntStateOf(300) }
    var cooldown by remember { mutableIntStateOf(900) }
    var installedApps by remember { mutableStateOf(emptyList<AutomationAppOption>()) }
    var usageAccess by remember { mutableStateOf(AutomationTriggers.hasUsageAccess(context)) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var backgroundLocationGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var definitions by remember { mutableStateOf(emptyList<AgentTaskDefinitionEntity>()) }
    var editorReady by remember(initial?.id) { mutableStateOf(initial == null) }
    val projects by remember(context) { io.github.mangi.eta.fork.projects.ProjectRepository.projects(context) }.collectAsState(emptyList())
    var stepEdit by remember { mutableStateOf<WorkflowStep?>(null) }
    var message by remember { mutableStateOf("") }; var saving by remember { mutableStateOf(false) }
    var checkingSetup by remember { mutableStateOf(false) }
    var setupResult by remember { mutableStateOf<String?>(null) }
    var setupCheckVersion by remember { mutableIntStateOf(0) }
    var quickMode by rememberSaveable { mutableStateOf(quickCreate && initial == null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val actual = initial?.takeUnless { duplicate }
    LaunchedEffect(title, prompt, spec, timed, scheduleKind, hour, minute, interval, monthDay, weekdays, date, trigger, source) {
        setupCheckVersion++
        setupResult = null
    }
    LaunchedEffect(initial?.id) {
        try {
        withContext(Dispatchers.IO) {
            definitions = dao.definitionsNow().filter { it.id != actual?.id }
            val schedule = initial?.let { task -> dao.schedulesNow().firstOrNull { it.definitionId == task.id } }
            val event = initial?.let { task -> db.triggerDao().all().firstOrNull { it.definitionId == task.id } }
            if (schedule != null) {
                timed = true; scheduleKind = schedule.kind; hour = schedule.localHour ?: 8; minute = schedule.localMinute ?: 0
                interval = (schedule.intervalMinutes ?: 60).toInt(); monthDay = schedule.monthDay ?: 1
                if (schedule.kind == AgentScheduleKind.ONCE) date = Instant.ofEpochMilli(schedule.startAt).atZone(ZoneId.of(schedule.timeZoneId)).toLocalDate().toString()
                weekdays = schedule.weekdaysMask ?: 1; missedPolicy = schedule.missedRunPolicy
            } else if (initial != null) timed = false
            if (event != null) {
                trigger = AutomationTrigger.valueOf(event.type); triggerGroup = automationTriggerGroup(trigger); source = event.source; extension = event.extension; cooldown = event.cooldownSeconds
                if (trigger in setOf(AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED)) {
                    runCatching {
                        if (TaskDependencyConfig.isConfig(event.source)) TaskDependencyConfig.parse(event.source)
                        else TaskDependencyConfig(listOf(event.source), TaskDependencyConfig.Mode.ALL)
                    }.getOrNull()?.let { dependency ->
                        dependencySourceIds = dependency.taskIds
                        dependencyMode = dependency.mode
                    }
                }
                when (trigger) {
                    AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW ->
                        batteryThreshold = event.source.toIntOrNull()?.coerceIn(1, 100) ?: 20
                    AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT -> runCatching {
                        LocationAutomationConfig.parse(event.source)
                    }.getOrNull()?.let { area ->
                        locationLatitude = area.latitude.toString()
                        locationLongitude = area.longitude.toString()
                        locationRadius = area.radiusMeters
                    }
                    else -> Unit
                }
            }
        }
        editorReady = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "无法读取任务设置，请返回重试。"
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "未授予附近设备权限，蓝牙触发暂不可用"
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        locationGranted = grants.values.any { it }
        if (grants.values.none { it }) message = "未授予位置权限，位置触发暂不可用"
    }
    val backgroundLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundLocationGranted = granted
        if (!granted) message = "未授予始终允许定位，锁屏后位置触发可能暂停"
    }
    val usageAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        usageAccess = AutomationTriggers.hasUsageAccess(context)
    }
    LaunchedEffect(trigger) {
        if (trigger == AutomationTrigger.APP_OPENED) {
            installedApps = withContext(Dispatchers.IO) { AutomationAppCatalog.launchableApps(context) }
            usageAccess = AutomationTriggers.hasUsageAccess(context)
        }
        if (trigger in setOf(AutomationTrigger.BLUETOOTH_CONNECTED, AutomationTrigger.BLUETOOTH_DISCONNECTED) &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (trigger in setOf(AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT) &&
            !locationGranted
        ) {
            locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
        }
        backgroundLocationGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source = uri.toString()
        }.onFailure { message = "目录授权失败：${it.javaClass.simpleName}" }
    }
    val selectedStep = stepEdit
    if (selectedStep != null) {
        BackHandler { stepEdit = null }
        WorkflowStepEditor(selectedStep, onBack = { stepEdit = null }, onSave = { step ->
            val index = spec.steps.indexOfFirst { it.id == step.id }
            spec = spec.copy(steps = if (index < 0) spec.steps + step else spec.steps.toMutableList().also { it[index] = step })
            stepEdit = null
        })
        return
    }
    fun scheduleRule(now: Long): AgentScheduleRule {
        if (scheduleKind == AgentScheduleKind.WEEKLY) require(weekdays != 0) { "每周任务至少选择一个执行日" }
        return AgentScheduleRule(
            scheduleKind,
            startAt = if (scheduleKind == AgentScheduleKind.ONCE) java.time.LocalDate.parse(date)
                .atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                .also { require(it > now) { "请选择未来的执行时间" } } else now,
            intervalMinutes = if (scheduleKind == AgentScheduleKind.INTERVAL) interval.toLong()
                .also { require(it >= 15) { "间隔至少 15 分钟" } } else null,
            localHour = hour, localMinute = minute, weekdaysMask = weekdays,
            timeZoneId = ZoneId.systemDefault().id, monthDay = monthDay,
        )
    }
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val rule = if (timed) scheduleRule(now) else null
                    if (!timed && trigger in setOf(AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED)) require(source.isNotBlank()) { "请选择监控目录" }
                    if (!timed && trigger == AutomationTrigger.APP_OPENED) require(AutomationTriggers.hasUsageAccess(context)) { "请先授予使用情况访问权限" }
                    val triggerSource = when (trigger) {
                        AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW -> batteryThreshold.toString()
                        AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED -> {
                            require(dependencySourceIds.isNotEmpty()) { "请至少选择一个来源任务" }
                            TaskDependencyConfig(dependencySourceIds, dependencyMode).encode()
                        }
                        AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT -> {
                            val latitude = locationLatitude.trim().toDoubleOrNull() ?: error("请输入有效纬度")
                            val longitude = locationLongitude.trim().toDoubleOrNull() ?: error("请输入有效经度")
                            LocationAutomationConfig(latitude, longitude, locationRadius).encode()
                        }
                        else -> source
                    }
                    if (!timed && trigger != AutomationTrigger.MANUAL) AutomationTriggerConfig.validate(trigger, triggerSource)
                    val event = if (timed || trigger == AutomationTrigger.MANUAL) null else AutomationTriggerEntity("", "", trigger.name, triggerSource, extension, cooldown)
                    AutomationRepository(db).save(actual, title, prompt, spec, event, rule, initial?.runKind ?: AgentTaskRunKind.CHAT, initial?.targetId ?: "default", missedPolicy)
                    AgentTaskWorkScheduler.refreshNextDeadline(context); AgentTaskWorkScheduler.checkNow(context)
                }
                onBack()
            } catch (e: Exception) { message = e.message ?: "保存失败" }
            finally { saving = false }
        }
    }
    var editorCategory by rememberSaveable(actual?.id) { mutableIntStateOf(0) }
    val basicsScroll = rememberLazyListState()
    val triggersScroll = rememberLazyListState()
    val stepsScroll = rememberLazyListState()
    val toolsScroll = rememberLazyListState()
    MiuixScaffoldPage(if (actual == null) "创建自动任务" else "编辑自动任务", onBack,
        listState = listOf(basicsScroll, triggersScroll, stepsScroll, toolsScroll)[editorCategory],
        aboveContent = {
            Column {
                if (!quickMode) NexusSettingsTabs(listOf(stringResource(R.string.settings_tab_basics),
                    stringResource(R.string.settings_tab_triggers), stringResource(R.string.settings_tab_steps),
                    stringResource(R.string.settings_tab_tools)), editorCategory, { editorCategory = it })
                if (message.isNotBlank()) AutomationMessage(message)
            }
        }, actions = {
        IconButton(enabled = !saving && editorReady, onClick = ::save) { if (saving) InfiniteProgressIndicator(size = 20.dp) else Icon(Icons.Rounded.Check, "保存任务") }
    }, overlay = {
        WindowDialog(
            show = deleteConfirm,
            title = "删除自动任务",
            summary = "“${title.ifBlank { "未命名任务" }}”及运行记录将被删除，此操作无法撤销。",
            onDismissRequest = { deleteConfirm = false },
        ) {
            MiuixDialogActions(confirmText = "删除", destructive = true, onCancel = { deleteConfirm = false }, onConfirm = {
                scope.launch { try { withContext(Dispatchers.IO) { AutomationRepository(AgentTaskDatabase.get(context)).delete(actual!!.id); AgentTaskWorkScheduler.refreshNextDeadline(context) }; onBack() }
                    catch (e: Exception) { deleteConfirm = false; message = e.message ?: "删除失败" } }
            })
        }
    }) {
        if (editorCategory == 0) item(key = "editor_header") {
            AutomationEditorHeader(
                title = title.ifBlank { if (actual == null) "新的自动任务" else "自动任务" },
                timed = timed,
                stepCount = spec.steps.size,
            )
        }
        if (editorCategory == 0) item(key = "editor_basics") {
            AutomationSectionCard("任务", Icons.Rounded.EditNote) {
                AutomationField(title, { title = it }, "任务名称")
                AutomationField(prompt, { prompt = it }, "任务目标与输出要求", 3)
                val selectableProjects = projects.filter { it.archivedAt == null }
                val projectIndex = selectableProjects.indexOfFirst { it.id == spec.projectId }
                val missingProject = spec.projectId != null && projectIndex < 0
                OverlayDropdownPreference(
                    title = "项目记忆",
                    items = listOf("全局") + selectableProjects.map { it.name } +
                        if (missingProject) listOf("项目加载中或不可用") else emptyList(),
                    selectedIndex = if (missingProject) selectableProjects.size + 1 else projectIndex + 1,
                    onSelectedIndexChange = { index ->
                        if (index == 0) spec = spec.copy(projectId = null)
                        else selectableProjects.getOrNull(index - 1)?.let { spec = spec.copy(projectId = it.id) }
                    },
                )
                if (conversationDraft != null) Text(
                    "任务使用自动任务的模型与执行设置，不带入原会话历史和已选技能。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (conversationDraft?.omittedAttachments == true) Text(
                    "原消息的图片或文件未加入任务，请补充任务所需资料。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusWarning,
                )
            }
        }
        if (editorCategory == 0 && quickMode) item(key = "quick_schedule") {
            AutomationSectionCard("执行时间", Icons.Rounded.Schedule) {
                SwitchPreference(title = "按时间执行", summary = if (timed) "每天执行" else "仅手动执行",
                    checked = timed, onCheckedChange = { timed = it })
                if (timed) {
                    AutomationNumber("小时", hour, 0..23) { hour = it }
                    AutomationNumber("分钟", minute, 0..59) { minute = it }
                }
                ArrowPreference(title = "更多设置", summary = "其他时间、事件、多步骤与工具", onClick = { quickMode = false })
            }
        }
        if (editorCategory == 0) item(key = "editor_preview") {
            val now = System.currentTimeMillis()
            val preview = if (timed) runCatching {
                val rule = scheduleRule(now)
                val times = mutableListOf<Long>()
                var cursor = maxOf(now - 1, rule.startAt - 1)
                repeat(3) {
                    val next = TaskScheduleCalculator.nextOccurrence(rule, cursor)
                    if (next != null) { times += next; cursor = next }
                }
                times
            } else null
            val whenText = when {
                !timed && trigger == AutomationTrigger.MANUAL -> "手动运行"
                !timed -> "${trigger.label}时运行"
                preview?.isFailure == true -> "执行时间尚未设置完整"
                else -> "按${scheduleLabel(scheduleKind)}计划运行"
            }
            AutomationSectionCard("保存前查看", Icons.Rounded.FactCheck) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$whenText · ${title.ifBlank { "未命名任务" }}", style = MiuixTheme.textStyles.body2)
                    Text(prompt.ifBlank { "请填写任务目标与输出要求" }, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("结果保存在自动任务运行详情；生成的文件可从详情打开。", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (spec.steps.isNotEmpty()) Text("当前包含 ${spec.steps.size} 个步骤；各步骤在“步骤”中配置。",
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    preview?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { times ->
                        Text("预计执行：${times.joinToString("、") { automationTime(it) }}", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (editorCategory == 0) item(key = "editor_check") {
            AutomationSectionCard("运行前检查", Icons.Rounded.ManageSearch) {
                ArrowPreference(title = if (checkingSetup) "正在检查" else "检查当前配置",
                    summary = setupResult ?: "检查任务、模型设置和项目目录授权",
                    enabled = !checkingSetup && editorReady, onClick = {
                        checkingSetup = true
                        setupResult = null
                        val checkVersion = setupCheckVersion
                        val currentTitle = title
                        val currentPrompt = prompt
                        val currentSpec = spec
                        val currentTarget = initial?.targetId ?: "default"
                        val currentKind = initial?.runKind ?: AgentTaskRunKind.CHAT
                        scope.launch {
                            val result = try {
                                withContext(Dispatchers.IO) {
                                    val issues = mutableListOf<String>()
                                    if (currentTitle.isBlank() || currentPrompt.isBlank()) issues += "请填写任务名称与目标"
                                    if (currentKind in setOf(AgentTaskRunKind.CHAT, AgentTaskRunKind.MAINTENANCE)) {
                                        val targets = if (currentSpec.steps.isEmpty()) listOf(currentTarget to currentPrompt) else {
                                            currentSpec.steps.filter { it.kind in setOf(WorkflowStepKind.AGENT, WorkflowStepKind.SKILL) }
                                                .map { (it.target.ifBlank { "default" }) to (currentPrompt + "\n" + it.prompt) }
                                        }
                                        for ((target, taskPrompt) in targets.distinct()) {
                                            try {
                                                val config = io.github.mangi.eta.agent.model.AgentModelClient.loadConfig()
                                                val tokens = io.github.mangi.eta.agent.model.ContextBudgetPolicy.estimateTextTokens(taskPrompt) +
                                                    io.github.mangi.eta.agent.model.ContextBudgetPolicy.estimateTextTokens(config.systemPrompt)
                                                val resolved = io.github.mangi.eta.data.repository.RuntimeConfigRepository.automationRuntimeConfig(
                                                    config, profileName = target, estimatedInputTokens = tokens,
                                                )
                                                with(io.github.mangi.eta.agent.model.AgentModelClient) { resolved.config.validate() }
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (_: Exception) { issues += "请检查 $target 的模型与登录配置" }
                                        }
                                    }
                                    if (currentKind == AgentTaskRunKind.A2A) {
                                        val agent = io.github.mangi.eta.fork.a2a.A2ADatabase.get(context).dao().agent(currentTarget)
                                        if (agent == null || !agent.enabled) issues += "远端 Agent 不存在或已停用"
                                    }
                                    if (currentKind == AgentTaskRunKind.MEETING) {
                                        val room = io.github.mangi.eta.fork.meeting.MeetingRepository.room(context, currentTarget).first()
                                        if (room == null || room.archivedAt != null) issues += "会议室不可用"
                                    }
                                    currentSpec.projectId?.let { id ->
                                        val project = projects.firstOrNull { it.id == id }
                                        if (project == null || project.archivedAt != null) issues += "项目不可用，请重新选择"
                                        else if (project.source == "SAF" && context.contentResolver.persistedUriPermissions.none {
                                                it.uri.toString() == project.rootIdentityKey && it.isReadPermission && it.isWritePermission
                                            }) issues += "项目目录授权已失效"
                                    }
                                    if (issues.isEmpty()) "基础配置已检查；实际连接、任务条件和授权以运行时为准。" else issues.joinToString("；")
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { "检查暂不可用，请稍后重试。" }
                            if (checkVersion == setupCheckVersion) setupResult = result
                            checkingSetup = false
                        }
                    })
            }
        }
        if (editorCategory == 1) item(key = "editor_trigger") {
            AutomationSectionCard("触发条件", if (timed) Icons.Rounded.Schedule else Icons.Rounded.Bolt) {
                SwitchPreference(title = "时间计划", checked = timed, onCheckedChange = { timed = it })
                if (timed) {
                    OverlayDropdownPreference(
                        title = "周期",
                        items = AgentScheduleKind.entries.map(::scheduleLabel),
                        selectedIndex = scheduleKind.ordinal,
                        onSelectedIndexChange = { scheduleKind = AgentScheduleKind.entries[it] },
                    )
                    if (scheduleKind == AgentScheduleKind.INTERVAL) {
                        AutomationNumber("间隔分钟", interval, 15..10080) { interval = it }
                    } else {
                        if (scheduleKind == AgentScheduleKind.ONCE) AutomationField(date, { date = it }, "执行日期（YYYY-MM-DD）")
                        AutomationNumber("小时", hour, 0..23) { hour = it }
                        AutomationNumber("分钟", minute, 0..59) { minute = it }
                        if (scheduleKind == AgentScheduleKind.WEEKLY) {
                            Text(
                                "执行日",
                                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, day ->
                                    EtaChoiceChip(
                                        text = day,
                                        selected = weekdays and (1 shl index) != 0,
                                        onClick = {
                                            weekdays = if (weekdays and (1 shl index) != 0) weekdays and (1 shl index).inv() else weekdays or (1 shl index)
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (scheduleKind == AgentScheduleKind.MONTHLY) {
                            AutomationNumber("每月日期", monthDay, 1..31) { monthDay = it }
                        }
                    }
                    OverlayDropdownPreference(
                        title = "错过执行时",
                        items = listOf("跳过", "补执行一次", "询问我"),
                        selectedIndex = missedPolicy.ordinal,
                        onSelectedIndexChange = { missedPolicy = MissedRunPolicy.entries[it] },
                    )
                } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutomationTriggerGroup.entries.filterNot { it == AutomationTriggerGroup.SCHEDULE }.forEach { group ->
                        EtaChoiceChip(
                            text = group.label,
                            selected = triggerGroup == group,
                            onClick = {
                                triggerGroup = group
                                AutomationTrigger.entries.firstOrNull { automationTriggerGroup(it) == group }?.let {
                                    trigger = it
                                    source = ""
                                }
                            },
                        )
                    }
                }
                val choices = AutomationTrigger.entries.filter { automationTriggerGroup(it) == triggerGroup }
                OverlayDropdownPreference(
                    title = triggerGroup.label,
                    items = choices.map { it.label },
                    selectedIndex = choices.indexOf(trigger).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        choices.getOrNull(index)?.let { selected -> trigger = selected; source = "" }
                    },
                )
                if (trigger in setOf(AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED)) {
                    DetailListRow(
                        "监控目录",
                        if (source.isBlank()) "尚未指定" else android.net.Uri.parse(source).lastPathSegment.orEmpty(),
                        Icons.Rounded.FolderOpen,
                        onClick = { folderPicker.launch(null) },
                    )
                    AutomationField(extension, { extension = it.trim().removePrefix(".").lowercase() }, "扩展名，留空匹配全部")
                }
                if (trigger in setOf(AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW)) {
                    AutomationNumber("电量阈值 %", batteryThreshold, 1..100, 5) { batteryThreshold = it }
                }
                if (trigger in setOf(AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT)) {
                    AutomationField(locationLatitude, { locationLatitude = it }, "纬度", 1)
                    AutomationField(locationLongitude, { locationLongitude = it }, "经度", 1)
                    AutomationNumber("区域半径（米）", locationRadius, 50..50000, 50) { locationRadius = it }
                    if (locationGranted && !backgroundLocationGranted) {
                    DetailListRow(
                        "后台定位",
                        "允许始终使用位置",
                        Icons.Rounded.LocationOn,
                        onClick = { backgroundLocationPermission.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    )
                    }
                }
                if (trigger in setOf(AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED)) {
                    TaskDependencyPicker(
                        definitions = definitions,
                        selectedIds = dependencySourceIds,
                        mode = dependencyMode,
                        onSelectedIdsChange = { dependencySourceIds = it },
                        onModeChange = { dependencyMode = it },
                    )
                }
                if (trigger == AutomationTrigger.APP_OPENED) {
                    val appItems = installedApps.map { "${it.label} · ${it.packageName}" }
                    OverlayDropdownPreference(
                        title = "目标应用",
                        items = if (appItems.isEmpty()) listOf("正在读取应用") else appItems,
                        selectedIndex = installedApps.indexOfFirst { it.packageName == source }.coerceAtLeast(0),
                        onSelectedIndexChange = { installedApps.getOrNull(it)?.let { app -> source = app.packageName } },
                    )
                    if (!usageAccess) {
                    DetailListRow(
                        "使用情况访问",
                        "授予后才能监听应用打开",
                        Icons.Rounded.Security,
                        onClick = {
                            val target = Intent(
                                android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { usageAccessLauncher.launch(target) }.onFailure {
                                usageAccessLauncher.launch(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        },
                    )
                    }
                }
                if (trigger == AutomationTrigger.BLUETOOTH_CONNECTED || trigger == AutomationTrigger.BLUETOOTH_DISCONNECTED) {
                    AutomationField(source, { source = it.take(248) }, "设备地址或名称（留空匹配任意设备）")
                }
                if (trigger != AutomationTrigger.MANUAL) {
                    AutomationNumber("冷却秒数", cooldown, 60..86400, 60) { cooldown = it }
                    AutomationNumber("每日事件运行上限", spec.maxEventRunsPerDay, 1..200) { spec = spec.copy(maxEventRunsPerDay = it) }
                }
            }
        }
        }
        if (editorCategory == 1) item(key = "editor_conditions") {
            AutomationSectionCard("运行条件", Icons.Rounded.Tune) {
                AutomationConditionEditor(spec.condition) { spec = spec.copy(condition = it) }
            }
        }
        if (editorCategory == 2) item(key = "editor_flow") {
            AutomationSectionCard("执行流程", Icons.Rounded.AccountTree, trailing = "${spec.steps.size} 步") {
                if (spec.steps.isEmpty()) {
                    Text(
                        "还没有步骤",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                spec.steps.forEachIndexed { index, step ->
                    AutomationWorkflowStepRow(
                        index = index,
                        step = step,
                        canMoveUp = index > 0,
                        canMoveDown = index < spec.steps.lastIndex,
                        onOpen = { stepEdit = step },
                        onMoveUp = {
                            spec = spec.copy(steps = spec.steps.toMutableList().also { java.util.Collections.swap(it, index, index - 1) })
                        },
                        onMoveDown = {
                            spec = spec.copy(steps = spec.steps.toMutableList().also { java.util.Collections.swap(it, index, index + 1) })
                        },
                        onDelete = { spec = spec.copy(steps = spec.steps.filterNot { it.id == step.id }) },
                    )
                }
                DetailListRow(
                    "添加步骤",
                    "Agent、Skill、条件、等待或通知",
                    Icons.Rounded.Add,
                    onClick = {
                        if (spec.steps.size < 20) {
                            stepEdit = WorkflowStep(UUID.randomUUID().toString(), WorkflowStepKind.AGENT, "执行任务")
                        } else {
                            message = "最多 20 步"
                        }
                    },
                )
                AutomationNumber("流程总超时（秒）", spec.timeoutSeconds, 10..540, 30) { spec = spec.copy(timeoutSeconds = it) }
            }
        }
        if (editorCategory == 3) item(key = "editor_tools") {
            AutomationSectionCard("工具范围", Icons.Rounded.Extension) {
                io.github.mangi.eta.agent.runtime.AgentBoundaryStore.groups.forEach { (name, patterns) ->
                    val policy = io.github.mangi.eta.agent.runtime.AgentToolPolicy(spec.allowedTools, spec.disallowedTools)
                    SwitchPreference(
                        title = name,
                        checked = patterns.all(policy::isAllowed),
                        onCheckedChange = { allowed ->
                            spec = spec.copy(disallowedTools = if (allowed) spec.disallowedTools - patterns.toSet() else (spec.disallowedTools + patterns).distinct())
                        },
                    )
                }
            }
        }
        if (editorCategory == 0) item(key = "editor_actions") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton(
                        text = if (saving) "正在保存" else "保存任务",
                        enabled = !saving,
                        onClick = ::save,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (actual != null) {
                        TextButton(
                            text = "删除任务",
                            enabled = !saving,
                            onClick = { deleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationEditorHeader(title: String, timed: Boolean, stepCount: Int) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaIconTile(
                icon = if (timed) Icons.Rounded.Schedule else Icons.Rounded.Bolt,
                tone = EtaIconTone.AUTOMATION,
                size = 40.dp,
                glyphSize = 21.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (timed) "按时间自动执行" else "按设备状态或事件执行",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            EtaStatusPill(if (stepCount > 0) "$stepCount 步" else "未编排")
        }
    }
}

@Composable
private fun AutomationSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EtaIconTile(icon, tone = EtaIconTone.AUTOMATION)
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                trailing?.let { EtaStatusPill(it) }
            }
            HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine.copy(alpha = .55f))
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), content = content)
        }
    }
}

@Composable
private fun AutomationWorkflowStepRow(
    index: Int,
    step: WorkflowStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp).squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = .10f), 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    step.title.ifBlank { step.kind.label },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${step.kind.label} · ${step.repeats} 次 · ${step.failure.label}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "编辑步骤", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
        }
        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine.copy(alpha = .45f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AutomationCompactIconButton(Icons.Rounded.ArrowUpward, "上移步骤", canMoveUp, onMoveUp)
            AutomationCompactIconButton(Icons.Rounded.ArrowDownward, "下移步骤", canMoveDown, onMoveDown)
            AutomationCompactIconButton(Icons.Rounded.DeleteOutline, "删除步骤", true, onDelete, danger = true)
        }
    }
}

@Composable
private fun AutomationCompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        danger -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(36.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 11.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WorkflowStepEditor(initial: WorkflowStep, onBack: () -> Unit, onSave: (WorkflowStep) -> Unit) {
    val context = LocalContext.current
    var skills by remember { mutableStateOf(emptyList<io.github.mangi.eta.agent.skill.SkillIndexEntry>()) }
    LaunchedEffect(Unit) { skills = withContext(Dispatchers.IO) { io.github.mangi.eta.agent.skill.SkillRuntime.createIndexService(context).listInstalledSkills().filter { it.enabled } } }
    var step by remember { mutableStateOf(initial) }; var message by remember { mutableStateOf("") }
    MiuixScaffoldPage("编辑步骤", onBack, actions = {
        IconButton(onClick = { runCatching { AutomationSpec(listOf(step)).validate() }.onSuccess { onSave(step) }.onFailure { message = it.message.orEmpty() } }) { Icon(Icons.Rounded.Check, "保存步骤") }
    }) {
        item(key = "step_action") {
            AutomationSectionCard("步骤动作", automationStepIcon(step.kind), trailing = step.kind.label) {
                AutomationField(step.title, { step = step.copy(title = it) }, "步骤名称")
                OverlayDropdownPreference(
                    title = "动作",
                    items = WorkflowStepKind.entries.map { it.label },
                    selectedIndex = step.kind.ordinal,
                    onSelectedIndexChange = { step = step.copy(kind = WorkflowStepKind.entries[it]) },
                )
                if (step.kind in setOf(WorkflowStepKind.AGENT, WorkflowStepKind.SKILL, WorkflowStepKind.NOTIFY)) {
                    AutomationField(
                        step.prompt,
                        { step = step.copy(prompt = it) },
                        if (step.kind == WorkflowStepKind.NOTIFY) "通知正文，留空使用前一步结果" else "执行要求",
                        4,
                    )
                }
                if (step.kind in setOf(WorkflowStepKind.AGENT, WorkflowStepKind.SKILL)) {
                    AutomationField(step.target, { step = step.copy(target = it) }, "Agent 配置名称")
                }
                if (step.kind == WorkflowStepKind.SKILL) {
                    OverlayDropdownPreference(
                        title = "选择 Skill",
                        items = listOf("请选择") + skills.map { it.name },
                        selectedIndex = skills.indexOfFirst { it.id == step.skillId } + 1,
                        onSelectedIndexChange = { step = step.copy(skillId = skills.getOrNull(it - 1)?.id.orEmpty()) },
                    )
                }
                if (step.kind == WorkflowStepKind.WAIT) {
                    AutomationNumber("等待秒数", step.waitSeconds, 0..300, 5) { step = step.copy(waitSeconds = it) }
                }
            }
        }
        if (step.kind == WorkflowStepKind.CONDITION) {
            item(key = "step_condition") {
                AutomationSectionCard("判断条件", Icons.AutoMirrored.Rounded.CallSplit) {
                    AutomationConditionEditor(step.conditions) { step = step.copy(conditions = it) }
                    AutomationNumber("不满足时跳过后续步骤数", step.skipOnFalse, 0..20) { step = step.copy(skipOnFalse = it) }
                }
            }
        }
        item(key = "step_control") {
            AutomationSectionCard("执行控制", Icons.Rounded.Tune) {
                if (step.kind != WorkflowStepKind.CONDITION) {
                    OverlayDropdownPreference(
                        title = "循环方式",
                        items = WorkflowLoopMode.entries.map { it.label },
                        selectedIndex = step.loopMode.ordinal,
                        onSelectedIndexChange = { step = step.copy(loopMode = WorkflowLoopMode.entries[it]) },
                    )
                    AutomationNumber("最多执行次数", step.repeats, 1..5) { step = step.copy(repeats = it) }
                    if (step.loopMode != WorkflowLoopMode.COUNT) {
                        AutomationConditionEditor(step.conditions) { step = step.copy(conditions = it) }
                    }
                }
                AutomationNumber("可安全重试次数", step.retries, 0..2) { step = step.copy(retries = it) }
                AutomationNumber("步骤超时（秒）", step.timeoutSeconds, 10..300, 10) { step = step.copy(timeoutSeconds = it) }
                OverlayDropdownPreference(
                    title = "失败后",
                    items = WorkflowFailure.entries.map { it.label },
                    selectedIndex = step.failure.ordinal,
                    onSelectedIndexChange = { step = step.copy(failure = WorkflowFailure.entries[it]) },
                )
            }
        }
        if (message.isNotBlank()) item { AutomationMessage(message) }
    }
}

@Composable
internal fun AutomationConditionEditor(value: AutomationCondition, onChange: (AutomationCondition) -> Unit) {
    val options = listOf(null, true, false)
    OverlayDropdownPreference(title = "充电状态", items = listOf("不限", "正在充电", "未充电"), selectedIndex = options.indexOf(value.charging), onSelectedIndexChange = { onChange(value.copy(charging = options[it])) })
    OverlayDropdownPreference(title = "网络状态", items = listOf("不限", "网络可用", "网络不可用"), selectedIndex = options.indexOf(value.network), onSelectedIndexChange = { onChange(value.copy(network = options[it])) })
    OverlayDropdownPreference(title = "锁屏状态", items = listOf("不限", "已解锁", "已锁定"), selectedIndex = options.indexOf(value.unlocked), onSelectedIndexChange = { onChange(value.copy(unlocked = options[it])) })
    OverlayDropdownPreference(title = "耳机状态", items = listOf("不限", "已连接", "未连接"), selectedIndex = options.indexOf(value.headset), onSelectedIndexChange = { onChange(value.copy(headset = options[it])) })
    OverlayDropdownPreference(title = "飞行模式", items = listOf("不限", "已开启", "已关闭"), selectedIndex = options.indexOf(value.airplaneMode), onSelectedIndexChange = { onChange(value.copy(airplaneMode = options[it])) })
    AutomationNumber("最低电量 %", value.minimumBattery, 0..100, 5) { onChange(value.copy(minimumBattery = it)) }
    AutomationNumber("最高电量 %（0 为不限）", value.maximumBattery, 0..100, 5) { onChange(value.copy(maximumBattery = it)) }
    SwitchPreference(title = "任一条件满足", checked = value.any, onCheckedChange = { onChange(value.copy(any = it)) })
    if (value.groups.isNotEmpty()) {
        Text(
            "组合子条件按「${if (value.any) "任一" else "全部"}满足」连接",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        value.groups.forEachIndexed { index, child ->
            AutomationGroupedConditionEditor(
                index = index,
                value = child,
                depth = 1,
                onChange = { updated ->
                    onChange(value.copy(groups = value.groups.toMutableList().also { it[index] = updated }))
                },
                onDelete = { onChange(value.copy(groups = value.groups.filterIndexed { childIndex, _ -> childIndex != index })) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            text = "添加组合子条件",
            enabled = value.groups.size < 15,
            onClick = { onChange(value.copy(groups = value.groups + AutomationCondition(any = !value.any))) },
        )
    }
}

@Composable
private fun AutomationGroupedConditionEditor(
    index: Int,
    value: AutomationCondition,
    depth: Int,
    onChange: (AutomationCondition) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp)
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("子条件 ${index + 1} · 第 ${depth + 1} 层", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            AutomationCompactIconButton(Icons.Rounded.DeleteOutline, "删除子条件", true, onDelete, danger = true)
        }
        AutomationConditionNode(value, depth) { onChange(it) }
    }
}

@Composable
private fun AutomationConditionNode(value: AutomationCondition, depth: Int, onChange: (AutomationCondition) -> Unit) {
    val options = listOf(null, true, false)
    OverlayDropdownPreference(title = "充电状态", items = listOf("不限", "正在充电", "未充电"), selectedIndex = options.indexOf(value.charging), onSelectedIndexChange = { onChange(value.copy(charging = options[it])) })
    OverlayDropdownPreference(title = "网络状态", items = listOf("不限", "网络可用", "网络不可用"), selectedIndex = options.indexOf(value.network), onSelectedIndexChange = { onChange(value.copy(network = options[it])) })
    OverlayDropdownPreference(title = "锁屏状态", items = listOf("不限", "已解锁", "已锁定"), selectedIndex = options.indexOf(value.unlocked), onSelectedIndexChange = { onChange(value.copy(unlocked = options[it])) })
    OverlayDropdownPreference(title = "耳机状态", items = listOf("不限", "已连接", "未连接"), selectedIndex = options.indexOf(value.headset), onSelectedIndexChange = { onChange(value.copy(headset = options[it])) })
    OverlayDropdownPreference(title = "飞行模式", items = listOf("不限", "已开启", "已关闭"), selectedIndex = options.indexOf(value.airplaneMode), onSelectedIndexChange = { onChange(value.copy(airplaneMode = options[it])) })
    AutomationNumber("最低电量 %", value.minimumBattery, 0..100, 5) { onChange(value.copy(minimumBattery = it)) }
    AutomationNumber("最高电量 %（0 为不限）", value.maximumBattery, 0..100, 5) { onChange(value.copy(maximumBattery = it)) }
    SwitchPreference(title = "组内任一条件满足", checked = value.any, onCheckedChange = { onChange(value.copy(any = it)) })
    value.groups.forEachIndexed { index, child ->
        AutomationGroupedConditionEditor(
            index = index,
            value = child,
            depth = depth + 1,
            onChange = { updated -> onChange(value.copy(groups = value.groups.toMutableList().also { it[index] = updated })) },
            onDelete = { onChange(value.copy(groups = value.groups.filterIndexed { childIndex, _ -> childIndex != index })) },
        )
    }
    if (depth < 3) Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            text = "添加子条件",
            enabled = value.groups.size < 15,
            onClick = { onChange(value.copy(groups = value.groups + AutomationCondition(any = !value.any))) },
        )
    }
}

@Composable
private fun TaskDependencyPicker(
    definitions: List<AgentTaskDefinitionEntity>,
    selectedIds: List<String>,
    mode: TaskDependencyConfig.Mode,
    onSelectedIdsChange: (List<String>) -> Unit,
    onModeChange: (TaskDependencyConfig.Mode) -> Unit,
) {
    Text(
        "来源任务（已选 ${selectedIds.size}/${definitions.size.coerceAtLeast(1)}）",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    if (definitions.isEmpty()) {
        Text(
            "暂无可依赖的任务",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else definitions.forEach { task ->
        val selected = task.id in selectedIds
        DetailListRow(
            title = task.title,
            subtitle = if (selected) "完成后参与触发" else "未选择",
            icon = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            tone = if (selected) EtaIconTone.SYSTEM else EtaIconTone.MODEL,
            onClick = {
                onSelectedIdsChange(
                    if (selected) selectedIds - task.id
                    else (selectedIds + task.id).take(20),
                )
            },
        )
    }
    OverlayDropdownPreference(
        title = "触发方式",
        items = listOf("所选任务全部完成后触发", "任一任务完成后触发"),
        selectedIndex = mode.ordinal,
        onSelectedIndexChange = { index -> onModeChange(TaskDependencyConfig.Mode.entries[index]) },
    )
}

@Composable
internal fun AutomationNumber(title: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
        AutomationCompactIconButton(Icons.Rounded.Remove, "减少$title", value > range.first, onClick = { onChange((value - step).coerceIn(range)) })
        TextField(text, { input -> if (input.length <= 5 && input.all(Char::isDigit)) { text = input; input.toIntOrNull()?.takeIf { it in range }?.let(onChange) } },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(76.dp).padding(horizontal = 5.dp))
        AutomationCompactIconButton(Icons.Rounded.Add, "增加$title", value < range.last, onClick = { onChange((value + step).coerceIn(range)) })
    }
}
@Composable internal fun AutomationField(value: String, onChange: (String) -> Unit, label: String, lines: Int = 1) {
    TextField(value, onChange, label = label, minLines = lines, maxLines = if (lines == 1) 2 else 8, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}
@Composable private fun AutomationMessage(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .squircleSurface(MiuixTheme.colorScheme.error.copy(alpha = .09f), 12.dp)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(19.dp))
        Text(message, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
    }
}
internal fun scheduleLabel(kind: AgentScheduleKind): String = when (kind) { AgentScheduleKind.ONCE -> "一次"; AgentScheduleKind.INTERVAL -> "间隔"; AgentScheduleKind.DAILY -> "每天"; AgentScheduleKind.WEEKLY -> "每周"; AgentScheduleKind.MONTHLY -> "每月" }
private fun taskStateLabel(state: AgentTaskState) = when (state) {
    AgentTaskState.SUCCEEDED -> "已完成"
    AgentTaskState.FAILED -> "失败"
    AgentTaskState.CANCELED -> "已取消"
    AgentTaskState.RUNNING -> "运行中"
    AgentTaskState.WAITING_AUTH -> "等待授权"
    AgentTaskState.WAITING_INPUT -> "等待输入"
    AgentTaskState.WAITING_REMOTE -> "等待远端任务"
    AgentTaskState.A2A_REVIEW -> "远端结果待核对"
    AgentTaskState.REVIEW_DECIDED -> "已记录核对决定"
    AgentTaskState.QUEUED, AgentTaskState.LEASED -> "排队中"
}

internal fun automationRunTimeSummary(run: AgentTaskRunEntity, now: Long = System.currentTimeMillis()): String =
    when {
        run.state in setOf(AgentTaskState.QUEUED, AgentTaskState.LEASED) && run.scheduledFor > now ->
            "计划 ${automationTime(run.scheduledFor)}"
        run.state == AgentTaskState.WAITING_REMOTE && run.scheduledFor > now ->
            "检查 ${automationTime(run.scheduledFor)}"
        run.state in setOf(AgentTaskState.QUEUED, AgentTaskState.LEASED) ->
            "等待执行 · ${automationTime(run.createdAt)}"
        else -> "${automationTime(run.createdAt)}开始"
    }

private fun taskCoordinator(context: android.content.Context, dao: AgentTaskDao): AgentTaskCoordinator =
    AgentTaskCoordinator(
        dao, DefaultAgentTaskRunner(context),
        runtimeCanceller = AgentTaskRuntimeCanceller(AgentRuntimeClient(context, AndroidAgentLogger)::cancelRun),
        remoteCanceller = AgentTaskRemoteCanceller { agentId, remoteTaskId -> cancelRemoteA2ATask(context, agentId, remoteTaskId) },
        resultStore = AgentTaskResultStore(context),
    )
private fun automationTime(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
private fun automationStepIcon(kind: WorkflowStepKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    WorkflowStepKind.AGENT -> Icons.Rounded.SupportAgent
    WorkflowStepKind.SKILL -> Icons.Rounded.Extension
    WorkflowStepKind.WAIT -> Icons.Rounded.HourglassTop
    WorkflowStepKind.CONDITION -> Icons.AutoMirrored.Rounded.CallSplit
    WorkflowStepKind.NOTIFY -> Icons.Rounded.Notifications
}

private data class AutomationHistoryEvent(
    val event: AgentTaskEventEntity,
    val title: String,
    val code: String,
    val stepId: String,
    val success: Boolean,
)

private data class AutomationHistoryProjection(
    val events: List<AutomationHistoryEvent> = emptyList(),
    val recordedStepTitles: Map<String, String> = emptyMap(),
    val fallbackOutput: String? = null,
)

private suspend fun projectAutomationHistory(events: List<AgentTaskEventEntity>): AutomationHistoryProjection {
    val titles = mutableMapOf<String, String>()
    val visible = ArrayList<AutomationHistoryEvent>(minOf(events.size, 200))
    var fallbackOutput: String? = null
    val firstVisible = (events.size - 200).coerceAtLeast(0)
    events.forEachIndexed { index, event ->
        if (index % 64 == 0) currentCoroutineContext().ensureActive()
        val data = runCatching { org.json.JSONObject(event.payloadJson) }.getOrNull()
        val step = data?.optString("step").orEmpty()
        val title = data?.optString("title").orEmpty()
        if (step.isNotBlank() && title.isNotBlank()) titles[step] = title
        if (index >= firstVisible) {
            visible.add(AutomationHistoryEvent(event, title, data?.optString("code").orEmpty(), step, data?.optBoolean("success") == true))
            if (event.eventType == "SUCCEEDED") fallbackOutput = data?.optString("output")?.takeIf(String::isNotBlank)
        }
    }
    return AutomationHistoryProjection(visible, titles, fallbackOutput)
}

private fun observeArtifactIndex(context: android.content.Context) = callbackFlow {
    val observer = object : android.os.FileObserver(
        context.applicationContext.filesDir,
        CLOSE_WRITE or MOVED_TO or CREATE or DELETE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == "workspace-artifacts.json") trySend(Unit)
        }
    }
    observer.startWatching()
    trySend(Unit)
    awaitClose { observer.stopWatching() }
}.conflate()

@Composable private fun AutomationHistoryScreen(run: AgentTaskRunEntity, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { AgentTaskDatabase.get(context).agentTaskDao() }
    val current by remember(dao, run.id) { dao.runFlow(run.id) }.collectAsState(run)
    val history by produceState(AutomationHistoryProjection(), dao, run.id) {
        dao.observeEvents(run.id).conflate().collect { events ->
            value = withContext(Dispatchers.Default) { projectAutomationHistory(events) }
        }
    }
    var showDetails by rememberSaveable(run.id) { mutableStateOf(false) }
    var showFiles by rememberSaveable(run.id) { mutableStateOf(false) }
    var actionError by remember(run.id) { mutableStateOf<String?>(null) }
    var artifactRefresh by remember(run.id) { mutableIntStateOf(0) }
    val artifactResult by produceState<Result<List<io.github.zhh_lgtm.nexus.application.api.ArtifactRecord>>?>(
        null, current?.localRunId, current?.resultRef, current?.state?.terminal, artifactRefresh,
    ) {
        value = null
        val runIds = setOfNotNull(current?.localRunId, current?.resultRef)
        observeArtifactIndex(context).collect {
            value = try {
                Result.success(withContext(Dispatchers.IO) {
                    io.github.mangi.eta.data.repository.NexusArtifactLedger(context).list(10_000)
                        .filter { it.runId in runIds || it.runId.startsWith("${run.id}:") }
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }
    val artifacts = artifactResult?.getOrNull().orEmpty()
    var outputRefresh by remember(run.id) { mutableIntStateOf(0) }
    val savedOutputResult by produceState<Result<String?>?>(null, current?.resultRef, current?.state, outputRefresh) {
        value = null
        value = try {
            Result.success(withContext(Dispatchers.IO) {
                AutomationRepository(AgentTaskDatabase.get(context)).savedOutput(context, run.id)
            })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    val savedOutput = savedOutputResult?.getOrNull()
    val events = history.events
    val output = savedOutput?.takeIf(String::isNotBlank) ?: history.fallbackOutput
    val stepEvents = events.filter { it.event.eventType in setOf("STEP_STARTED", "STEP_FINISHED", "STEP_WAIT_SCHEDULED", "STEP_UNCONFIRMED") }
    if (showFiles) {
        NexusArtifactBrowser(records = artifacts, title = "本次文件", onDismiss = { showFiles = false })
    }
    MiuixScaffoldPage("运行详情", onBack, actions = {
        if (current?.state?.terminal == false) IconButton(onClick = { scope.launch {
            taskCoordinator(context, dao).cancelRun(run.id)
        } }) { Icon(Icons.Rounded.Stop, "停止任务") }
    }) {
        item { SmallTitle("执行结果 · ${taskStateLabel(current?.state ?: run.state)}") }
        current?.errorMessage?.let { item { AutomationMessage(it) } }
        actionError?.let { item { AutomationMessage(it) } }
        if (output != null) item(key = "output") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(output, style = MiuixTheme.textStyles.body2)
                    if (savedOutput.isNullOrBlank() && output.length >= 12_000) {
                        Text("这里只保留了结果摘要，完整结果可能不可用。", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (savedOutputResult?.isFailure == true) item(key = "output_error") {
            ArrowPreference(title = "无法读取完整结果", summary = "点击重试", onClick = { outputRefresh++ })
        }
        if (output == null && savedOutputResult?.isSuccess == true && (current?.state ?: run.state) == AgentTaskState.SUCCEEDED) item(key = "no_output") {
            Text("本次运行没有可显示的文字结果。", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (current?.state == AgentTaskState.A2A_REVIEW && current?.a2aReviewDecision == null) item(key = "review_actions") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("请先核对远端任务。重新发起只记录你的决定，不会自动发送新任务。", style = MiuixTheme.textStyles.footnote1)
                listOf("CHECKED" to "已核对", "RESTART_REQUESTED" to "记录重新发起", "CANCELED" to "取消任务").forEach { (decision, label) ->
                    TextButton(text = label, onClick = { scope.launch {
                        try {
                            if (!taskCoordinator(context, dao).recordA2AReviewDecision(run.id, decision)) actionError = "任务状态已变化，请返回后重试。"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { actionError = "无法保存决定，请重试。" }
                    } })
                }
            }
        }
        if (artifactResult?.isFailure == true) item(key = "artifact_error") {
            ArrowPreference(title = "无法读取本次文件", summary = "点击重试", onClick = { artifactRefresh++ })
        }
        if (artifacts.isNotEmpty()) {
            item { SmallTitle("本次文件") }
            artifacts.forEach { artifact -> item(key = "artifact:${artifact.id}") {
                DetailListRow(artifact.label, if (artifact.status == io.github.zhh_lgtm.nexus.application.api.ArtifactStatus.VERIFIED) "打开文件" else "文件暂不可用",
                    Icons.Rounded.InsertDriveFile, onClick = { showFiles = true })
            } }
        }
        if (stepEvents.isNotEmpty()) {
            item { SmallTitle("执行步骤") }
            stepEvents.forEach { row -> item(key = "step:${row.event.id}") {
                val event = row.event
                val label = when (event.eventType) {
                    "STEP_STARTED" -> "开始"
                    "STEP_WAIT_SCHEDULED" -> "等待中"
                    "STEP_UNCONFIRMED" -> "结果待核对"
                    else -> if (row.success) "完成" else "未完成"
                }
                val title = row.title.takeIf(String::isNotBlank)
                    ?: history.recordedStepTitles[row.stepId]
                    ?: "步骤（历史名称未记录）"
                Text("${automationTime(event.createdAt)} · $title · $label", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MiuixTheme.textStyles.footnote1)
            } }
        }
        item(key = "event_toggle") {
            ArrowPreference(title = if (showDetails) "收起运行记录" else "查看运行记录", onClick = { showDetails = !showDetails })
        }
        if (showDetails) events.forEach { row -> item(key = "event:${row.event.id}") {
            val event = row.event
            val label = when (event.eventType) {
                "STEP_STARTED" -> "步骤开始"
                "STEP_FINISHED" -> "步骤结束"
                "STEP_WAIT_SCHEDULED" -> "等待已排期"
                "STEP_UNCONFIRMED" -> "步骤结果待核对"
                "BRANCH" -> "条件分支"
                "LOOP_FINISHED" -> "循环结束"
                "STEP_RETRY" -> "安全重试"
                "TRIGGER" -> "事件触发"
                "WAITING_CONDITIONS" -> "等待设备条件"
                "CLAIMED" -> "取得任务"
                "REMOTE_POLL_CLAIMED" -> "核对远端任务"
                "STARTED" -> "开始执行"
                "CANCELED" -> "已取消"
                "RETRY_QUEUED" -> "重试已排队"
                "SCHEDULE_PAUSED_AFTER_RETRIES" -> "重试耗尽，任务已暂停"
                "LEASE_EXPIRED_REQUEUED" -> "执行租约过期，重新排队"
                "LEASE_EXPIRED_FAILED" -> "执行结果未确认，已停止"
                "WORKFLOW_RESUMED_AFTER_LEASE_EXPIRY" -> "从持久断点恢复"
                "WORKFLOW_STEP_UNCONFIRMED" -> "未确认步骤已阻止重放"
                "WORKFLOW_DEFINITION_CHANGED" -> "任务定义已变化，旧断点已停止"
                "A2A_WAITING_REMOTE" -> "等待远端任务"
                "A2A_REMOTE_RESUMED_AFTER_LEASE_EXPIRY" -> "继续核对原远端任务"
                "A2A_WAIT_EXPIRED" -> "远端等待到期，待核对"
                "RUN_TIMEOUT" -> "运行超时"
                "SUCCEEDED" -> "任务完成"
                "FAILED" -> "任务失败"
                else -> automationEventFallbackLabel(event.eventType)
            }
            AutomationMessage("${automationTime(event.createdAt)} · $label\n${row.title} ${row.code}")
        } }
    }
}

internal fun automationEventFallbackLabel(eventType: String): String = when (eventType) {
    "STEP_STARTED" -> "步骤开始"
    "STEP_FINISHED", "STEP_UNCONFIRMED" -> "步骤结束，结果待核对"
    "STEP_WAIT_SCHEDULED" -> "等待已排期"
    "BRANCH" -> "条件分支"
    "LOOP_FINISHED" -> "循环结束"
    "STEP_RETRY" -> "安全重试"
    "WAITING_CONDITIONS" -> "等待设备条件"
    "CLAIMED", "REMOTE_POLL_CLAIMED" -> "取得任务"
    "STARTED" -> "开始执行"
    "WORKFLOW_RESUMED_AFTER_LEASE_EXPIRY" -> "从持久断点恢复"
    "WORKFLOW_STEP_UNCONFIRMED" -> "未确认步骤已阻止重放"
    "WORKFLOW_DEFINITION_CHANGED" -> "任务定义已变化，旧断点已停止"
    "LEASE_EXPIRED_FAILED" -> "执行结果未确认，已停止"
    "LEASE_EXPIRED_REQUEUED" -> "执行租约过期，重新排队"
    "A2A_WAIT_EXPIRED" -> "远端等待到期，待核对"
    "RUN_TIMEOUT" -> "运行超时"
    "SUCCEEDED" -> "任务完成"
    "FAILED" -> "任务失败"
    else -> eventType.takeIf(String::isNotBlank) ?: "任务状态更新"
}
