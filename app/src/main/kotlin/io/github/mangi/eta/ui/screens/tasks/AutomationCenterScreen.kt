package io.github.mangi.eta.ui.screens.tasks

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Composable
internal fun AgentTaskCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AgentTaskDatabase.get(context) }
    val dao = db.agentTaskDao()
    val definitions by dao.observeDefinitions().collectAsState(emptyList())
    val runs by dao.observeRecentRuns().collectAsState(emptyList())
    val schedules by dao.observeSchedules().collectAsState(emptyList())
    val triggers by db.triggerDao().observe().collectAsState(emptyList())
    var editing by remember { mutableStateOf<AgentTaskDefinitionEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<AgentTaskRunEntity?>(null) }
    var message by remember { mutableStateOf("") }
    var monitor by remember { mutableStateOf(AutomationMonitorService.enabled(context)) }
    val monitorRunning by AutomationMonitorService.running.collectAsState()
    fun action(block: suspend () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { message = e.message ?: "操作失败" } } }
    if (creating || editing != null) {
        BackHandler { creating = false; editing = null }
        AutomationEditorScreen(editing, duplicate, onBack = { creating = false; editing = null; duplicate = false })
        return
    }
    if (legacy) { BackHandler { legacy = false }; ScheduledTaskLegacyScreen { legacy = false }; return }
    history?.let { run -> BackHandler { history = null }; AutomationHistoryScreen(run) { history = null }; return }
    MiuixScaffoldPage("自动任务", onBack, actions = {
        IconButton(onClick = { duplicate = false; creating = true }) { Icon(Icons.Rounded.Add, "创建自动任务") }
    }) {
        item(key = "automation_overview") {
            AutomationOverviewCard(
                enabledCount = definitions.count { it.enabled },
                totalCount = definitions.size,
                recentRuns = runs.size,
                monitor = monitor,
                monitorRunning = monitorRunning,
                onMonitorChange = { value ->
                    runCatching { AutomationMonitorService.setEnabled(context, value) }
                        .onSuccess { monitor = value }
                        .onFailure { message = it.message ?: "启动监听失败" }
                },
                onOpenInbox = { legacy = true },
            )
        }
        if (message.isNotBlank()) item { AutomationMessage(message) }
        item { SmallTitle("我的任务 · ${definitions.size}", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        if (definitions.isEmpty()) item { ListEmptyState(title = "创建第一个自动任务", summary = "时间、事件与多步骤流程", icon = Icons.Rounded.AccountTree) }
        definitions.forEach { definition -> item(key = definition.id) {
            val trigger = triggers.firstOrNull { it.definitionId == definition.id }
            val schedule = schedules.firstOrNull { it.definitionId == definition.id }
            val spec = runCatching { AutomationSpec.parse(definition.policyJson) }.getOrNull()
            val parsedTrigger = trigger?.type?.let { runCatching { AutomationTrigger.valueOf(it) }.getOrNull() }
            val taskIcon = if (schedule != null) Icons.Rounded.Schedule else automationTriggerIcon(parsedTrigger)
            AutomationTaskCard(
                title = definition.title,
                enabled = definition.enabled,
                triggerLabel = trigger?.type?.let { runCatching { AutomationTrigger.valueOf(it).label }.getOrDefault("事件") }
                    ?: schedule?.let { scheduleLabel(it.kind) }
                    ?: "手动",
                stepCount = spec?.steps?.size ?: 0,
                prompt = definition.promptTemplate,
                detail = trigger?.detail?.takeIf(String::isNotBlank),
                icon = taskIcon,
                onOpen = { editing = definition },
                onToggle = { action { AutomationRepository(db).setEnabled(definition.id, !definition.enabled); AgentTaskWorkScheduler.refreshNextDeadline(context) } },
                onRun = { action { checkNotNull(AgentTaskRepository(dao).runNow(definition.id)) { "任务已暂停或已有运行" }; AgentTaskWorkScheduler.checkNow(context) } },
                onDuplicate = { duplicate = true; editing = definition },
            )
        } }
        item { SmallTitle("最近运行", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        runs.take(40).forEach { run -> item(key = run.id) {
            DetailListRow(definitions.firstOrNull { it.id == run.definitionId }?.title ?: "任务记录",
                "${taskStateLabel(run.state)} · ${automationTime(run.createdAt)}", Icons.Rounded.History,
                preview = run.errorMessage, onClick = { history = run })
        } }
    }
}

@Composable
private fun AutomationOverviewCard(
    enabledCount: Int,
    totalCount: Int,
    recentRuns: Int,
    monitor: Boolean,
    monitorRunning: Boolean,
    onMonitorChange: (Boolean) -> Unit,
    onOpenInbox: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EtaIconTile(Icons.Rounded.Bolt, tone = EtaIconTone.AUTOMATION, size = 40.dp, glyphSize = 21.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("自动任务", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$enabledCount 个启用 · $recentRuns 条最近运行",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaStatusPill(
                    text = if (monitorRunning) "监听中" else if (monitor) "待启动" else "按周期检查",
                    tone = if (monitorRunning) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                )
            }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AutomationMetric("任务", totalCount.toString(), Modifier.weight(1f))
                AutomationMetric("启用", enabledCount.toString(), Modifier.weight(1f), EtaIconTone.SYSTEM.accent)
                AutomationMetric("最近运行", recentRuns.toString(), Modifier.weight(1f), EtaIconTone.MODEL.accent)
            }
            ArrowPreference(
                title = "待处理与运行记录",
                summary = "授权、输入与完整执行历史",
                onClick = onOpenInbox,
            )
        }
    }
}

@Composable
private fun AutomationMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = EtaIconTone.AUTOMATION.accent,
) {
    Column(
        modifier.squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .64f), 11.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = accent)
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
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
private fun AutomationEditorScreen(initial: AgentTaskDefinitionEntity?, duplicate: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AgentTaskDatabase.get(context) }
    val dao = db.agentTaskDao()
    var title by remember { mutableStateOf(initial?.title.orEmpty() + if (duplicate) " 副本" else "") }
    var prompt by remember { mutableStateOf(initial?.promptTemplate.orEmpty()) }
    var spec by remember { mutableStateOf(runCatching { AutomationSpec.parse(initial?.policyJson ?: "{}") }.getOrDefault(AutomationSpec())) }
    var timed by remember { mutableStateOf(initial == null) }
    var scheduleKind by remember { mutableStateOf(AgentScheduleKind.DAILY) }
    var hour by remember { mutableIntStateOf(8) }; var minute by remember { mutableIntStateOf(0) }
    var interval by remember { mutableIntStateOf(60) }; var monthDay by remember { mutableIntStateOf(1) }
    var weekdays by remember { mutableIntStateOf(1) }
    var missedPolicy by remember { mutableStateOf(MissedRunPolicy.RUN_ONCE) }
    var date by remember { mutableStateOf(java.time.LocalDate.now().plusDays(1).toString()) }
    var trigger by remember { mutableStateOf(AutomationTrigger.MANUAL) }
    var triggerGroup by remember { mutableStateOf(AutomationTriggerGroup.MANUAL) }
    var source by remember { mutableStateOf("") }; var extension by remember { mutableStateOf("xlsx") }
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
    val projects by io.github.mangi.eta.fork.projects.ProjectRepository.projects(context).collectAsState(emptyList())
    var stepEdit by remember { mutableStateOf<WorkflowStep?>(null) }
    var message by remember { mutableStateOf("") }; var saving by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val actual = initial?.takeUnless { duplicate }
    LaunchedEffect(initial?.id) {
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
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    if (timed && scheduleKind == AgentScheduleKind.WEEKLY) {
                        require(weekdays != 0) { "每周任务至少选择一个执行日" }
                    }
                    val rule = if (!timed) null else AgentScheduleRule(scheduleKind,
                        startAt = if (scheduleKind == AgentScheduleKind.ONCE) java.time.LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().also { require(it > now) { "请选择未来的执行时间" } } else now,
                        intervalMinutes = if (scheduleKind == AgentScheduleKind.INTERVAL) interval.toLong().also { require(it >= 15) { "间隔至少 15 分钟" } } else null,
                        localHour = hour, localMinute = minute, weekdaysMask = weekdays, timeZoneId = ZoneId.systemDefault().id, monthDay = monthDay)
                    if (!timed && trigger in setOf(AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED)) require(source.isNotBlank()) { "请选择监控目录" }
                    if (!timed && trigger == AutomationTrigger.APP_OPENED) require(AutomationTriggers.hasUsageAccess(context)) { "请先授予使用情况访问权限" }
                    val triggerSource = when (trigger) {
                        AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW -> batteryThreshold.toString()
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
    MiuixScaffoldPage(if (actual == null) "创建自动任务" else "编辑自动任务", onBack, actions = {
        IconButton(enabled = !saving, onClick = ::save) { if (saving) InfiniteProgressIndicator(size = 20.dp) else Icon(Icons.Rounded.Check, "保存任务") }
    }, overlay = {
        WindowDialog(
            show = deleteConfirm,
            title = "删除自动任务",
            summary = "“${title.ifBlank { "未命名任务" }}”及运行记录将被删除，此操作无法撤销。",
            onDismissRequest = { deleteConfirm = false },
        ) {
            MiuixDialogActions(confirmText = "删除", destructive = true, onCancel = { deleteConfirm = false }, onConfirm = {
                scope.launch { try { withContext(Dispatchers.IO) { require(dao.activeCount(actual!!.id) == 0) { "请先结束运行" }; dao.deleteDefinition(actual.id); AgentTaskWorkScheduler.refreshNextDeadline(context) }; onBack() }
                    catch (e: Exception) { deleteConfirm = false; message = e.message ?: "删除失败" } }
            })
        }
    }) {
        item(key = "editor_header") {
            AutomationEditorHeader(
                title = title.ifBlank { if (actual == null) "新的自动任务" else "自动任务" },
                timed = timed,
                stepCount = spec.steps.size,
            )
        }
        item(key = "editor_basics") {
            AutomationSectionCard("任务", Icons.Rounded.EditNote) {
                AutomationField(title, { title = it }, "任务名称")
                AutomationField(prompt, { prompt = it }, "任务目标与输出要求", 3)
                OverlayDropdownPreference(
                    title = "项目记忆",
                    items = listOf("全局") + projects.map { it.name },
                    selectedIndex = (projects.indexOfFirst { it.id == spec.projectId } + 1).coerceAtLeast(0),
                    onSelectedIndexChange = { spec = spec.copy(projectId = projects.getOrNull(it - 1)?.id) },
                )
            }
        }
        if (message.isNotBlank()) item { AutomationMessage(message) }
        item(key = "editor_trigger") {
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
                    OverlayDropdownPreference(
                        title = "来源任务",
                        items = if (definitions.isEmpty()) listOf("暂无任务") else definitions.map { it.title },
                        selectedIndex = definitions.indexOfFirst { it.id == source }.coerceAtLeast(0),
                        onSelectedIndexChange = { definitions.getOrNull(it)?.let { selected -> source = selected.id } },
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
                if (trigger != AutomationTrigger.MANUAL) {
                    AutomationNumber("冷却秒数", cooldown, 60..86400, 60) { cooldown = it }
                    AutomationNumber("每日事件运行上限", spec.maxEventRunsPerDay, 1..200) { spec = spec.copy(maxEventRunsPerDay = it) }
                }
            }
        }
        }
        item(key = "editor_conditions") {
            AutomationSectionCard("运行条件", Icons.Rounded.Tune) {
                AutomationConditionEditor(spec.condition) { spec = spec.copy(condition = it) }
            }
        }
        item(key = "editor_flow") {
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
        item(key = "editor_tools") {
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
        item(key = "editor_actions") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
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
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 11.dp),
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
                AutomationNumber("重复次数", step.repeats, 1..5) { step = step.copy(repeats = it) }
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
    AutomationNumber("最低电量 %", value.minimumBattery, 0..100, 5) { onChange(value.copy(minimumBattery = it)) }
    SwitchPreference(title = "任一条件满足", checked = value.any, onCheckedChange = { onChange(value.copy(any = it)) })
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
private fun taskStateLabel(state: AgentTaskState) = when (state) { AgentTaskState.SUCCEEDED -> "已完成"; AgentTaskState.FAILED -> "失败"; AgentTaskState.CANCELED -> "已取消"; AgentTaskState.RUNNING -> "运行中"; AgentTaskState.WAITING_AUTH -> "等待授权"; AgentTaskState.WAITING_INPUT -> "等待输入"; else -> "排队中" }
private fun automationTime(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
private fun automationStepIcon(kind: WorkflowStepKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    WorkflowStepKind.AGENT -> Icons.Rounded.SupportAgent
    WorkflowStepKind.SKILL -> Icons.Rounded.Extension
    WorkflowStepKind.WAIT -> Icons.Rounded.HourglassTop
    WorkflowStepKind.CONDITION -> Icons.AutoMirrored.Rounded.CallSplit
    WorkflowStepKind.NOTIFY -> Icons.Rounded.Notifications
}

@Composable private fun AutomationHistoryScreen(run: AgentTaskRunEntity, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val dao = remember { AgentTaskDatabase.get(context).agentTaskDao() }
    val current by dao.runFlow(run.id).collectAsState(run)
    val allEvents by dao.observeEvents(run.id).collectAsState(emptyList())
    val events = allEvents.takeLast(200)
    MiuixScaffoldPage("运行详情", onBack, actions = {
        if (current?.state?.terminal == false) IconButton(onClick = { scope.launch {
            AgentTaskCoordinator(dao, DefaultAgentTaskRunner(context), runtimeCanceller = AgentTaskRuntimeCanceller(AgentRuntimeClient(context, AndroidAgentLogger)::cancelRun)).cancelRun(run.id)
        } }) { Icon(Icons.Rounded.Stop, "停止任务") }
    }) {
        item { SmallTitle(taskStateLabel(current?.state ?: run.state)) }
        current?.errorMessage?.let { item { AutomationMessage(it) } }
        events.forEach { event -> item(key = event.id) {
            val data = runCatching { org.json.JSONObject(event.payloadJson) }.getOrNull()
            val label = when (event.eventType) { "STEP_STARTED" -> "步骤开始"; "STEP_FINISHED" -> "步骤结束"; "BRANCH" -> "条件分支"; "STEP_RETRY" -> "安全重试"; "TRIGGER" -> "事件触发"; "WAITING_CONDITIONS" -> "等待设备条件"; "CLAIMED" -> "取得任务"; "STARTED" -> "开始执行"; "CANCELED" -> "已取消"; "RETRY_QUEUED" -> "重试已排队"; "SUCCEEDED" -> "任务完成"; "FAILED" -> "任务失败"; else -> "任务状态更新" }
            AutomationMessage("${automationTime(event.createdAt)} · $label\n${data?.optString("title").orEmpty()} ${data?.optString("code").orEmpty()}")
        } }
    }
}
