package io.github.mangi.eta.ui.screens.memory

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.memory.*
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemoryScopeType
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.screens.learning.LearningSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AgentMemoryScreen(state: AgentMemoryUiState, onAction: (AgentMemoryAction) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val useCompactTitle = LocalDensity.current.fontScale <= 1.2f
    if (tab == 1) {
        MemoryDocumentScreen(
            state = state,
            onAction = onAction,
            onOpenLibrary = { tab = 0 },
            moduleTabs = { MemoryModuleTabBar(selectedTab = tab, onTabSelected = { tab = it }) },
        )
        return
    }
    if (tab == 2) {
        LearningSettingsScreen(
            onBack = { tab = 0 },
            title = "记忆与自学习",
            compactTitle = useCompactTitle,
            moduleTabs = { MemoryModuleTabBar(selectedTab = tab, onTabSelected = { tab = it }) },
        )
        return
    }
    val context = LocalContext.current.applicationContext
    val coroutine = rememberCoroutineScope()
    val db = remember { LearningDatabase.get(context) }
    val repository = remember { MemoryReviewRepository(db) }
    val entries by remember { db.memoryDao().observeEntries() }.collectAsState(initial = emptyList())
    val jobs by remember { db.memoryDao().observeJobs() }.collectAsState(initial = emptyList())
    val maintenanceStatus by remember { MemoryAutomationSettingsStore.observeStatus(context) }
        .collectAsState(initial = MemoryAutomationSettingsStore.status(context))
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var scopeFilter by rememberSaveable { mutableStateOf("all") }
    var layerFilter by rememberSaveable { mutableStateOf("all") }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var inspected by remember { mutableStateOf<MemoryEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var showJobs by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        names = withContext(Dispatchers.IO) {
            buildMap {
                ProjectRepository.projects(context).first().forEach { put("project:${it.id}", it.name) }
                EtaDatabase.get(context).conversationDao().conversationIdentities().forEach { put("conversation:${it.id}", it.title) }
            }
        }
    }
    LaunchedEffect(entries) { inspected = entries.firstOrNull { it.id == inspected?.id } }
    fun mutate(action: String) {
        val entry = inspected ?: return
        if (busy) return
        busy = true
        coroutine.launch {
            try {
                message = withContext(Dispatchers.IO) { repository.decide(entry.id, entry.updatedAt, action, entry.conflictIds) }
                if (message == null) inspected = null
            } catch (e: Exception) { message = "操作未完成，请重试" }
            finally { busy = false }
        }
    }
    MiuixScaffoldPage(title = "记忆与自学习", onBack = { onAction(AgentMemoryAction.NavigateBack) }, compactTitle = useCompactTitle, actions = {
        IconButton(onClick = { MemoryMaintenanceScheduler.enqueue(context, manual = true); message = "已安排整理，可在运行记录查看进度" }) {
            Icon(Icons.Rounded.Refresh, contentDescription = "立即整理")
        }
    }, aboveContent = {
        MemoryModuleTabBar(selectedTab = tab, onTabSelected = { tab = it })
    }, overlay = {
        WindowDialog(show = showFilters, title = "筛选记忆", onDismissRequest = { showFilters = false }) {
            val keys = listOf("all") + entries.map { it.scope }.distinct()
            val layers = listOf("all", "core", "long_term", "project", "task", "working", "other")
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val statusLabels = listOf("待审阅", "已生效", "冲突", "已归档 / 已拒绝", "已过期")
                DialogChoicePreference("状态", statusLabels[filter], statusLabels, filter) { filter = it }
                val scopeLabels = keys.map { if (it == "all") "全部作用域" else memoryScopeName(it, names) }
                val scopeIndex = keys.indexOf(scopeFilter).coerceAtLeast(0)
                DialogChoicePreference("作用域", scopeLabels[scopeIndex], scopeLabels, scopeIndex) { scopeFilter = keys[it] }
                val layerLabels = layers.map { if (it == "all") "全部层级" else memoryLayerName(it) }
                val layerIndex = layers.indexOf(layerFilter).coerceAtLeast(0)
                DialogChoicePreference("记忆层级", layerLabels[layerIndex], layerLabels, layerIndex) { layerFilter = layers[it] }
            }
            MiuixDialogActions(confirmText = "完成", cancelText = "重置", onCancel = { filter = 0; scopeFilter = "all"; layerFilter = "all" }, onConfirm = { showFilters = false })
        }
        inspected?.let { entry ->
            WindowDialog(show = true, title = entry.topic, onDismissRequest = { if (!busy) inspected = null }) {
                var draft by remember(entry.id, entry.updatedAt) { mutableStateOf(entry.content) }
                var editing by remember(entry.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(memoryScopeName(entry.scope, names) + " · " + memoryLayerName(entry.layer) + " · " + memoryStatus(entry), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (editing) TextField(value = draft, onValueChange = { draft = it.take(1200) }, label = "记忆内容", modifier = Modifier.fillMaxWidth())
                    else Text(entry.content, style = MiuixTheme.textStyles.body2)
                    val conflicts = repository.conflictEntries(entry, entries)
                    if (conflicts.isNotEmpty()) {
                        Text("已有内容", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.error)
                        conflicts.forEach { Text(it.content, style = MiuixTheme.textStyles.body2) }
                    }
                    Text("来源：${entry.source}\n提取：${memoryDate(entry.createdAt)}" + (entry.expiresAt?.let { "\n到期：${memoryDate(it)}" } ?: ""),
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (entry.evidence.isNotBlank()) {
                        Text("原话证据", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                        Text(entry.evidence, style = MiuixTheme.textStyles.body2)
                    }
                    if (entry.status in setOf("butler_approved", "pending", "conflict")) {
                        if (editing) TextButton("保存修改", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            busy = true
                            coroutine.launch {
                                try { message = withContext(Dispatchers.IO) { repository.edit(entry.id, draft) }; editing = false }
                                catch (_: Exception) { message = "保存失败，请重试" }
                                finally { busy = false }
                            }
                        }) else TextButton("修改内容", enabled = !busy, onClick = { editing = true }, modifier = Modifier.fillMaxWidth())
                        if (entry.status == "conflict" || entry.conflictIds.isNotBlank() || conflicts.isNotEmpty()) {
                            TextButton("采用新内容，替代旧内容", enabled = !busy && !editing, onClick = { mutate("replace") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                            TextButton("两条均保留", enabled = !busy && !editing, onClick = { mutate("keep_both") }, modifier = Modifier.fillMaxWidth())
                        } else TextButton("确认并启用", enabled = !busy && !editing, onClick = { mutate("confirm") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                        TextButton("拒绝此条", enabled = !busy, onClick = { mutate("reject") }, modifier = Modifier.fillMaxWidth())
                    } else if (entry.status == "active") TextButton("归档，停止使用", enabled = !busy, onClick = { mutate("archive") }, modifier = Modifier.fillMaxWidth())
                    TextButton("关闭", enabled = !busy, onClick = { inspected = null }, modifier = Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
                }
            }
        }
        if (inspected == null && state.notice != null) WindowDialog(show = true, title = "记忆", summary = state.notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) }) {
            TextButton("知道了", onClick = { onAction(AgentMemoryAction.DismissNotice) }, modifier = Modifier.fillMaxWidth())
        }
    }) {
        if (tab == 3) {
            item("memory_master_settings") {
                SmallTitle("记忆使用")
                MemorySettingsGroup(state = state, onAction = onAction)
            }
            item("automation_settings") { MemoryAutomationSettingsPanel() }
        } else {
            val now = System.currentTimeMillis()
            val activeCount = entries.count { it.status == "active" && (it.expiresAt == null || it.expiresAt > now) }
            val pendingCount = entries.count {
                it.status in setOf("butler_approved", "pending", "conflict") &&
                    (it.expiresAt == null || it.expiresAt > now)
            }
            val conflictCount = entries.count {
                (it.status == "conflict" || it.conflictIds.isNotBlank()) &&
                    it.status in setOf("butler_approved", "pending", "conflict") &&
                    (it.expiresAt == null || it.expiresAt > now)
            }
            item("memory_overview_title") { SmallTitle("记忆概况") }
            item("memory_overview") {
                KnowledgeMetricsCard(
                    activeCount = activeCount,
                    pendingCount = pendingCount,
                    conflictCount = conflictCount,
                    activeLabel = "生效记忆",
                    pendingLabel = "待审阅",
                    conflictLabel = "冲突",
                )
            }
            item("memory_search") {
                SmallTitle("查找记忆")
                TextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    label = "搜索主题、内容或项目",
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth(),
                )
            }
            item("memory_filters") {
                ArrowPreference(title = listOf("待审阅", "已生效", "冲突", "已归档 / 已拒绝", "已过期")[filter],
                    summary = listOf(if (scopeFilter == "all") "全部作用域" else memoryScopeName(scopeFilter, names), if (layerFilter == "all") "全部层级" else memoryLayerName(layerFilter)).joinToString(" · "),
                    startAction = { PreferenceIcon(Icons.Rounded.Tune, EtaIconTone.TOOL) }, onClick = { showFilters = true })
            }
            val shown = entries.filter { entry ->
                val expired = entry.expiresAt != null && entry.expiresAt <= now
                (scopeFilter == "all" || entry.scope == scopeFilter) &&
                    (layerFilter == "all" || entry.layer == layerFilter) &&
                    (query.isBlank() || (entry.topic + entry.content).contains(query, ignoreCase = true)) &&
                    when (filter) {
                        0 -> entry.status in setOf("butler_approved", "pending", "conflict") && !expired
                        1 -> entry.status == "active" && !expired
                        2 -> (entry.status == "conflict" || entry.conflictIds.isNotBlank()) &&
                            entry.status in setOf("butler_approved", "pending", "conflict") && !expired
                        3 -> entry.status in setOf("archived", "rejected", "superseded")
                        else -> expired
                    }
            }
            if (shown.isEmpty()) item("memory_empty") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ListEmptyState(
                        title = if (filter == 0) "暂无待审阅记忆" else "没有符合条件的记忆",
                        summary = if (filter == 0) "新对话整理后，候选会先显示在这里，确认后才会进入长期记忆。" else "调整状态、作用域或层级后再试。",
                        icon = Icons.Rounded.PsychologyAlt,
                    )
                }
            }
            shown.forEach { entry -> item("memory:${entry.id}") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    DetailListRow(title = entry.topic, subtitle = "${memoryScopeName(entry.scope, names)} · ${memoryLayerName(entry.layer)} · ${memoryStatus(entry)}", preview = entry.content,
                        icon = if (entry.status == "conflict" || entry.conflictIds.isNotBlank()) Icons.Rounded.CompareArrows else when (entry.layer) { "core" -> Icons.Rounded.BookmarkBorder; "project" -> Icons.Rounded.FolderOpen; "task" -> Icons.Rounded.TaskAlt; else -> Icons.Rounded.Notes },
                        tone = if (entry.status == "conflict" || entry.conflictIds.isNotBlank()) EtaIconTone.RISK else EtaIconTone.MEMORY,
                        onClick = { message = null; inspected = entry })
                }
            } }
            item("memory_jobs") {
                SmallTitle("自动整理")
                Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    DetailListRow(
                        title = maintenanceStatus,
                        subtitle = "${jobs.count { it.status in setOf("queued", "running") }} 个来源排队或正在整理",
                        icon = Icons.Rounded.AutoMode,
                        value = if (showJobs) "收起" else "查看记录",
                        onClick = { showJobs = !showJobs },
                    )
                    if (showJobs) {
                        if (jobs.isEmpty()) {
                            ListEmptyState(
                                title = "暂无整理记录",
                                summary = "完成一次手动或空闲整理后，这里会保留运行结果。",
                                icon = Icons.Rounded.History,
                            )
                        } else {
                            jobs.take(8).forEach { job ->
                                BasicComponent(
                                    title = when (job.status) {
                                        "queued" -> "等待整理"
                                        "running" -> "正在整理"
                                        "done" -> "整理完成"
                                        "failed" -> "整理失败"
                                        else -> "已过期"
                                    },
                                    summary = "${memoryDate(job.updatedAt)} · ${job.detail.ifBlank { "新对话" }}",
                                )
                            }
                        }
                    }
                }
            }
            if (message != null && inspected == null) item("memory_message") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        message.orEmpty(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryModuleTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val labels = listOf("记忆库", "文档", "自学习", "策略")
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .squircleSurface(color = colors.surfaceContainer, cornerRadius = 14.dp)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedTab
            val background by animateColorAsState(
                targetValue = if (selected) colors.surface else colors.surfaceContainer,
                animationSpec = tween(EtaMotion.state),
                label = "memory-tab-background",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .squircleSurface(
                        color = background,
                        cornerRadius = 11.dp,
                    )
                    .semantics { this.selected = selected }
                    .clickable(role = Role.Tab) { onTabSelected(index) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun memoryDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
internal fun memoryLayerName(layer: String): String = when (layer) {
    "core" -> "核心"; "long_term" -> "长期"; "project" -> "项目"; "task" -> "任务"; "working" -> "临时"; else -> "其他"
}
internal fun memoryStatus(entry: MemoryEntry): String = if (entry.expiresAt != null && entry.expiresAt <= System.currentTimeMillis()) "已过期" else if (
    entry.conflictIds.isNotBlank() && entry.status in setOf("butler_approved", "pending", "conflict")
) "管家已通过 · 存在冲突" else when (entry.status) {
    "active" -> "已生效"
    "butler_approved" -> "管家已通过，待人工批准"
    "conflict" -> "待复核冲突"
    "pending" -> "待软件管家审核"
    "rejected" -> "未通过，已拒绝"
    "superseded" -> "已被替代"
    else -> "已归档"
}
internal fun memoryScopeName(key: String, names: Map<String, String> = emptyMap()): String {
    val type = when (key.substringBefore(':')) { "global" -> "全局"; "project" -> "项目"; "conversation" -> "会话"; "meeting" -> "会议"; "task" -> "任务"; else -> "局部" }
    return if (key == "global") "全局记忆" else "$type · ${names[key]?.take(24) ?: key.substringAfter(':').takeLast(8)}"
}
