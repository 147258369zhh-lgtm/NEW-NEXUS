package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.fork.tasks.AgentScheduleKind
import io.github.mangi.eta.fork.tasks.AgentScheduleRule
import io.github.mangi.eta.fork.tasks.AgentTaskAttentionEntity
import io.github.mangi.eta.fork.tasks.AgentTaskAttentionKind
import io.github.mangi.eta.fork.tasks.AgentTaskBackgroundExecutor
import io.github.mangi.eta.fork.tasks.AgentTaskCoordinator
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import io.github.mangi.eta.fork.tasks.AgentTaskDefinitionEntity
import io.github.mangi.eta.fork.tasks.AgentTaskDraft
import io.github.mangi.eta.fork.tasks.AgentTaskRepository
import io.github.mangi.eta.fork.tasks.AgentTaskRunEntity
import io.github.mangi.eta.fork.tasks.AgentTaskRunKind
import io.github.mangi.eta.fork.tasks.AgentTaskRuntimeCanceller
import io.github.mangi.eta.fork.tasks.AgentTaskScheduleEntity
import io.github.mangi.eta.fork.tasks.AgentTaskState
import io.github.mangi.eta.fork.tasks.AgentTaskWorkScheduler
import io.github.mangi.eta.fork.tasks.DefaultAgentTaskRunner
import io.github.mangi.eta.fork.tasks.MissedRunPolicy
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Durable task definitions, leases, attention prompts and recent runs in one place. */
@Composable
internal fun ScheduledTaskLegacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember(context.applicationContext) { AgentTaskDatabase.get(context.applicationContext) }
    val dao = database.agentTaskDao()
    val definitions by dao.observeDefinitions().collectAsState(initial = emptyList())
    val schedules by dao.observeSchedules().collectAsState(initial = emptyList())
    val runs by dao.observeRecentRuns().collectAsState(initial = emptyList())
    val attention by dao.observeOpenAttention().collectAsState(initial = emptyList())
    val repository = remember(dao) { AgentTaskRepository(dao) }
    val coordinator = remember(dao, context.applicationContext) {
        val client = AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
        AgentTaskCoordinator(
            dao = dao,
            runner = DefaultAgentTaskRunner(context.applicationContext),
            runtimeCanceller = AgentTaskRuntimeCanceller(client::cancelRun),
        )
    }
    var showCreate by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("default") }
    var runKind by remember { mutableStateOf(AgentTaskRunKind.CHAT) }
    var scheduleKind by remember { mutableStateOf(AgentScheduleKind.DAILY) }
    var intervalHours by remember { mutableStateOf("6") }
    var localTime by remember { mutableStateOf("09:00") }
    var delayMinutes by remember { mutableStateOf("5") }
    var weekdayMask by remember { mutableStateOf(AgentScheduleRule.WEEKDAY_ALL) }
    var missedRunPolicy by remember { mutableStateOf(MissedRunPolicy.RUN_ONCE) }
    var createError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var resumeAttention by remember { mutableStateOf<AgentTaskAttentionEntity?>(null) }
    var resumeText by remember { mutableStateOf("") }

    MiuixScaffoldPage(
        title = "定时任务",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    createError = null
                    showCreate = true
                },
            ) { Icon(Icons.Rounded.Add, contentDescription = "新建定时任务") }
        },
        overlay = {
            WindowDialog(
                show = showCreate,
                title = "新建定时任务",
                onDismissRequest = { if (!saving) showCreate = false },
            ) {
                TaskCreateDialogContent(
                    title = title,
                    onTitleChange = { title = it },
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    target = target,
                    onTargetChange = { target = it },
                    runKind = runKind,
                    onRunKindChange = { runKind = it },
                    scheduleKind = scheduleKind,
                    onScheduleKindChange = { scheduleKind = it },
                    intervalHours = intervalHours,
                    onIntervalHoursChange = { intervalHours = it.replace("[^0-9.]".toRegex(), "") },
                    localTime = localTime,
                    onLocalTimeChange = { localTime = it },
                    weekdayMask = weekdayMask,
                    onWeekdayMaskChange = { weekdayMask = it },
                    delayMinutes = delayMinutes,
                    onDelayMinutesChange = { delayMinutes = it.filter(Char::isDigit) },
                    missedRunPolicy = missedRunPolicy,
                    onMissedRunPolicyChange = { missedRunPolicy = it },
                    error = createError,
                    saving = saving,
                    onCancel = { if (!saving) showCreate = false },
                    onSave = {
                        if (saving) return@TaskCreateDialogContent
                        val parsedSchedule = runCatching {
                            createSchedule(scheduleKind, intervalHours, localTime, delayMinutes, weekdayMask)
                        }.getOrElse {
                            createError = it.message ?: "时间格式无效"
                            return@TaskCreateDialogContent
                        }
                        if (title.isBlank() || prompt.isBlank() || target.isBlank()) {
                            createError = "请填写标题、提示词和执行目标"
                            return@TaskCreateDialogContent
                        }
                        saving = true
                        createError = null
                        scope.launch {
                            runCatching {
                                repository.create(
                                    AgentTaskDraft(
                                        title = title.trim(),
                                        runKind = runKind,
                                        targetId = target.trim(),
                                        promptTemplate = prompt.trim(),
                                        schedule = parsedSchedule,
                                        missedRunPolicy = missedRunPolicy,
                                    ),
                                )
                            }.onSuccess {
                                showCreate = false
                                title = ""
                                prompt = ""
                                AgentTaskWorkScheduler.refreshNextDeadline(context.applicationContext)
                                AgentTaskWorkScheduler.checkNow(context.applicationContext)
                            }.onFailure {
                                createError = it.message ?: "保存失败"
                            }
                            saving = false
                        }
                    },
                )
            }
            WindowDialog(
                show = resumeAttention != null,
                title = if (resumeAttention?.kind == AgentTaskAttentionKind.MISSED_RUN_DECISION) {
                    "补跑错过的任务？"
                } else {
                    "处理任务提示"
                },
                onDismissRequest = { resumeAttention = null },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        resumeAttention?.body.orEmpty(),
                        style = MiuixTheme.textStyles.body2,
                    )
                    val attention = resumeAttention ?: return@Column
                    if (attention.kind == AgentTaskAttentionKind.MISSED_RUN_DECISION) {
                        Text(
                            "补跑会立即进入执行队列；跳过只关闭本次提醒，不会改变后续计划。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        MiuixDialogActions(
                            confirmText = "现在补跑",
                            cancelText = "跳过",
                            onCancel = {
                                scope.launch {
                                    coordinator.cancelRun(attention.runId)
                                    resumeAttention = null
                                }
                            },
                            onConfirm = {
                                scope.launch {
                                    if (coordinator.queueMissedRun(attention.runId)) {
                                        AgentTaskWorkScheduler.checkNow(context.applicationContext)
                                    }
                                    resumeAttention = null
                                }
                            },
                        )
                    } else {
                        TextField(
                            value = resumeText,
                            onValueChange = { resumeText = it },
                            label = "补充输入或授权说明",
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MiuixDialogActions(
                            confirmText = "继续执行",
                            onCancel = { resumeAttention = null },
                            onConfirm = {
                                if (resumeText.isBlank()) return@MiuixDialogActions
                                if (AgentTaskBackgroundExecutor.resumeWaiting(
                                        context.applicationContext,
                                        attention.runId,
                                        resumeText,
                                    )
                                ) {
                                    resumeAttention = null
                                    resumeText = ""
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        item(key = "intro") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "任务定义和运行记录保存在本地数据库。WorkManager 每 15 分钟检查到期计划；进程重启后会按租约和补跑策略恢复。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (attention.isNotEmpty()) {
            item(key = "attention_title") { SmallTitle("需要你处理") }
            attention.forEach { item ->
                item(key = "attention:${item.id}") {
                    AttentionCard(
                        item = item,
                        onOpen = {
                            resumeText = ""
                            resumeAttention = item
                        },
                    )
                }
            }
        }
        item(key = "schedules_title") { SmallTitle("计划") }
        if (definitions.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ListEmptyState(
                        title = "还没有定时任务",
                        summary = "按时运行普通会话、会议室或 A2A 远端 Agent。",
                        icon = Icons.Rounded.Schedule,
                        action = {
                            TextButton(text = "新建任务", onClick = { showCreate = true })
                        },
                    )
                }
            }
        } else {
            definitions.forEach { definition ->
                val schedule = schedules.firstOrNull { it.definitionId == definition.id }
                item(key = "definition:${definition.id}") {
                    TaskDefinitionCard(
                        definition = definition,
                        schedule = schedule,
                        onToggle = {
                            if (schedule != null) {
                                scope.launch {
                                    repository.setPaused(schedule.id, !schedule.paused)
                                    AgentTaskWorkScheduler.refreshNextDeadline(context.applicationContext)
                                }
                            }
                        },
                        onRunNow = {
                            scope.launch {
                                if (repository.runNow(definition.id) != null) {
                                    AgentTaskWorkScheduler.checkNow(context.applicationContext)
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                repository.delete(definition.id)
                                AgentTaskWorkScheduler.refreshNextDeadline(context.applicationContext)
                            }
                        },
                    )
                }
            }
        }
        item(key = "runs_title") { SmallTitle("最近运行") }
        if (runs.isEmpty()) {
            item(key = "runs_empty") {
                Text(
                    text = "暂无运行记录。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            runs.take(30).forEach { run ->
                item(key = "run:${run.id}") {
                    TaskRunCard(
                        run = run,
                        title = definitions.firstOrNull { it.id == run.definitionId }?.title ?: run.definitionId,
                        onCancel = { scope.launch { coordinator.cancelRun(run.id) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCreateDialogContent(
    title: String,
    onTitleChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    target: String,
    onTargetChange: (String) -> Unit,
    runKind: AgentTaskRunKind,
    onRunKindChange: (AgentTaskRunKind) -> Unit,
    scheduleKind: AgentScheduleKind,
    onScheduleKindChange: (AgentScheduleKind) -> Unit,
    intervalHours: String,
    onIntervalHoursChange: (String) -> Unit,
    localTime: String,
    onLocalTimeChange: (String) -> Unit,
    weekdayMask: Int,
    onWeekdayMaskChange: (Int) -> Unit,
    delayMinutes: String,
    onDelayMinutesChange: (String) -> Unit,
    missedRunPolicy: MissedRunPolicy,
    onMissedRunPolicyChange: (MissedRunPolicy) -> Unit,
    error: String?,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextField(
            value = title,
            onValueChange = onTitleChange,
            label = "任务名称",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = "每次运行发送给 Agent 的提示词",
            minLines = 3,
            maxLines = 7,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = target,
            onValueChange = onTargetChange,
            label = "执行目标（Profile 名 / 会议室 ID / A2A Agent ID）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("执行类型", style = MiuixTheme.textStyles.footnote1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentTaskRunKind.entries.forEach { kind ->
                EtaChoiceChip(
                    text = when (kind) {
                        AgentTaskRunKind.CHAT -> "会话"
                        AgentTaskRunKind.MEETING -> "会议室"
                        AgentTaskRunKind.A2A -> "A2A"
                        AgentTaskRunKind.MAINTENANCE -> "维护"
                    },
                    selected = runKind == kind,
                    onClick = { onRunKindChange(kind) },
                )
            }
        }
        Text("计划", style = MiuixTheme.textStyles.footnote1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AgentScheduleKind.ONCE,
                AgentScheduleKind.INTERVAL,
                AgentScheduleKind.DAILY,
                AgentScheduleKind.WEEKLY,
            ).forEach { kind ->
                EtaChoiceChip(
                    text = when (kind) {
                        AgentScheduleKind.ONCE -> "一次"
                        AgentScheduleKind.INTERVAL -> "间隔"
                        AgentScheduleKind.DAILY -> "每天"
                        AgentScheduleKind.WEEKLY -> "每周"
                        AgentScheduleKind.MONTHLY -> "每月"
                    },
                    selected = scheduleKind == kind,
                    onClick = { onScheduleKindChange(kind) },
                )
            }
        }
        when (scheduleKind) {
            AgentScheduleKind.ONCE -> TextField(
                value = delayMinutes,
                onValueChange = onDelayMinutesChange,
                label = "多少分钟后运行",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AgentScheduleKind.INTERVAL -> TextField(
                value = intervalHours,
                onValueChange = onIntervalHoursChange,
                label = "间隔小时（支持 0.25）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AgentScheduleKind.DAILY, AgentScheduleKind.WEEKLY, AgentScheduleKind.MONTHLY -> TextField(
                value = localTime,
                onValueChange = onLocalTimeChange,
                label = if (scheduleKind == AgentScheduleKind.WEEKLY) "每周一 09:00（周一到周日）" else "每天运行时间 HH:mm",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (scheduleKind == AgentScheduleKind.WEEKLY) {
            Text("执行日", style = MiuixTheme.textStyles.footnote1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                    val bit = 1 shl index
                    EtaChoiceChip(
                        text = label,
                        selected = weekdayMask and bit != 0,
                        onClick = { onWeekdayMaskChange(weekdayMask xor bit) },
                    )
                }
            }
        }
        Text("错过计划时", style = MiuixTheme.textStyles.footnote1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MissedRunPolicy.entries.forEach { policy ->
                EtaChoiceChip(
                    text = when (policy) {
                        MissedRunPolicy.RUN_ONCE -> "补跑一次"
                        MissedRunPolicy.SKIP -> "跳过"
                        MissedRunPolicy.ASK_USER -> "询问我"
                    },
                    selected = missedRunPolicy == policy,
                    onClick = { onMissedRunPolicyChange(policy) },
                )
            }
        }
        error?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
        MiuixDialogActions(
            confirmText = if (saving) "保存中…" else "保存",
            onCancel = onCancel,
            onConfirm = onSave,
        )
    }
}

private fun createSchedule(
    kind: AgentScheduleKind,
    intervalHours: String,
    localTime: String,
    delayMinutes: String,
    weekdayMask: Int,
): AgentScheduleRule {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val time = localTime.split(":").map(String::toInt)
    require(time.size == 2 && time[0] in 0..23 && time[1] in 0..59) { "时间必须为 HH:mm" }
    return when (kind) {
        AgentScheduleKind.ONCE -> {
            val minutes = delayMinutes.toLongOrNull() ?: error("请输入分钟数")
            require(minutes > 0) { "分钟数必须大于 0" }
            AgentScheduleRule(
                kind = kind,
                startAt = now + minutes * 60_000L,
                timeZoneId = zone.id,
            )
        }
        AgentScheduleKind.INTERVAL -> {
            val hours = intervalHours.toDoubleOrNull() ?: error("请输入间隔小时")
            require(hours > 0) { "间隔必须大于 0" }
            AgentScheduleRule(
                kind = kind,
                startAt = now,
                intervalMinutes = (hours * 60).toLong().coerceAtLeast(1L),
                timeZoneId = zone.id,
            )
        }
        AgentScheduleKind.MONTHLY -> AgentScheduleRule(kind = kind, startAt = now, localHour = time[0], localMinute = time[1], monthDay = 1, timeZoneId = zone.id)
        AgentScheduleKind.DAILY -> AgentScheduleRule(
            kind = kind,
            startAt = now - 60_000L,
            localHour = time[0],
            localMinute = time[1],
            timeZoneId = zone.id,
        )
        AgentScheduleKind.WEEKLY -> AgentScheduleRule(
            kind = kind,
            startAt = now - 60_000L,
            localHour = time[0],
            localMinute = time[1],
            weekdaysMask = weekdayMask.also {
                require(it in 1..AgentScheduleRule.WEEKDAY_ALL) { "请至少选择一天" }
            },
            timeZoneId = zone.id,
        )
    }
}

@Composable
private fun TaskDefinitionCard(
    definition: AgentTaskDefinitionEntity,
    schedule: AgentTaskScheduleEntity?,
    onToggle: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    val paused = schedule?.paused != false
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = definition.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${runKindLabel(definition.runKind)} · ${definition.targetId}" +
                        "\n${scheduleSummary(schedule)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (paused) MiuixTheme.colorScheme.onSurfaceVariantSummary else StatusSuccess,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            IconButton(onClick = onRunNow) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "立即运行")
            }
            IconButton(onClick = onToggle, enabled = schedule != null) {
                Icon(
                    imageVector = if (paused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle,
                    contentDescription = if (paused) "启用" else "暂停",
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除", tint = StatusError)
            }
        }
    }
}

@Composable
private fun TaskRunCard(run: AgentTaskRunEntity, title: String, onCancel: () -> Unit) {
    val active = run.state in setOf(
        AgentTaskState.QUEUED,
        AgentTaskState.LEASED,
        AgentTaskState.RUNNING,
        AgentTaskState.WAITING_INPUT,
        AgentTaskState.WAITING_AUTH,
    )
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${run.state.wireValue} · ${formatDate(run.scheduledFor)}" +
                        (run.errorMessage?.let { "\n$it" } ?: ""),
                    style = MiuixTheme.textStyles.footnote2,
                    color = when (run.state) {
                        AgentTaskState.SUCCEEDED -> StatusSuccess
                        AgentTaskState.WAITING_AUTH, AgentTaskState.WAITING_INPUT -> StatusWarning
                        AgentTaskState.FAILED -> StatusError
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) TextButton(text = "取消", onClick = onCancel)
        }
    }
}

@Composable
private fun AttentionCard(item: AgentTaskAttentionEntity, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MiuixTheme.textStyles.body1, color = StatusWarning)
            Text(
                item.body,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(text = "处理", onClick = onOpen)
        }
    }
}

private fun runKindLabel(kind: AgentTaskRunKind): String = when (kind) {
    AgentTaskRunKind.CHAT -> "会话"
    AgentTaskRunKind.MEETING -> "会议室"
    AgentTaskRunKind.A2A -> "A2A"
    AgentTaskRunKind.MAINTENANCE -> "维护"
}

private fun scheduleSummary(schedule: AgentTaskScheduleEntity?): String {
    if (schedule == null) return "计划缺失"
    val status = if (schedule.paused) "已暂停" else "已启用"
    val cadence = when (schedule.kind) {
        AgentScheduleKind.ONCE -> "一次"
        AgentScheduleKind.INTERVAL -> "每 ${schedule.intervalMinutes?.let { "${it} 分钟" }.orEmpty()}"
        AgentScheduleKind.DAILY -> "每天 %02d:%02d".format(schedule.localHour, schedule.localMinute)
        AgentScheduleKind.WEEKLY -> "每周 %02d:%02d".format(schedule.localHour, schedule.localMinute)
        AgentScheduleKind.MONTHLY -> "每月 ${schedule.monthDay} 日 %02d:%02d".format(schedule.localHour, schedule.localMinute)
    }
    val next = schedule.nextRunAt?.let { " · 下次 ${formatDate(it)}" }.orEmpty()
    return "$status · $cadence$next"
}

private val taskDateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(taskDateFormatter)
