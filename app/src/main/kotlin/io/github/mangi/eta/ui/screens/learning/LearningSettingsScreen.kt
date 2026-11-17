package io.github.mangi.eta.ui.screens.learning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.fork.learning.LearningCandidateView
import io.github.mangi.eta.fork.learning.LearningMutationResult
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningRuleView
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import io.github.mangi.eta.fork.learning.LearningSettingLock
import io.github.mangi.eta.fork.learning.LearningSnapshot
import io.github.mangi.eta.fork.learning.LearningStatus
import io.github.mangi.eta.fork.learning.LearningKind
import io.github.mangi.eta.agent.memory.MemoryMaintenanceScheduler
import io.github.mangi.eta.ui.screens.memory.rememberMemoryAutomationSettingsPanel
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.KnowledgeMetricsCard
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.BasicComponent
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** User-governed self-learning: capture is automatic, promotion and rollback stay explicit. */
@Composable
internal fun LearningSettingsScreen(
    onBack: () -> Unit,
    title: String = "经验库",
    compactTitle: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    moduleTabs: @Composable () -> Unit = {},
    visible: Boolean = true,
    includeMemoryMaintenance: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) { LearningRepository.get(context.applicationContext) }
    var snapshot by remember { mutableStateOf(LearningSnapshot(emptyList(), emptyList())) }
    var enabled by remember { mutableStateOf(LearningSettingsStore.isEnabled(context)) }
    var autoCapture by remember { mutableStateOf(LearningSettingsStore.automaticCaptureEnabled(context)) }
    var lockState by remember { mutableStateOf(LearningSettingLock.state(context)) }
    var lockReason by rememberSaveable { mutableStateOf("") }
    var inspectedCandidate by remember { mutableStateOf<LearningCandidateView?>(null) }
    var inspectedRule by remember { mutableStateOf<LearningRuleView?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tab by rememberSaveable(includeMemoryMaintenance) { mutableIntStateOf(0) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf("all") }
    var scopeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(context.applicationContext) {
        scopeNames = withContext(Dispatchers.IO) {
            runCatching { io.github.mangi.eta.data.repository.MemoryManagementCatalog.load(context.applicationContext).names }.getOrDefault(emptyMap())
        }
    }
    val candidateListState = listState
    val ruleListState = rememberLazyListState()
    val policyListState = rememberLazyListState()
    val maintenancePanel = if (includeMemoryMaintenance) rememberMemoryAutomationSettingsPanel() else null

    // This screen is rendered as an embedded memory-module tab. Route system back to the
    // module tab row instead of allowing the secondary scaffold to fall through to app home.
    BackHandler(enabled = visible, onBack = onBack)

    fun applySnapshot(value: LearningSnapshot) {
        snapshot = value
        inspectedCandidate = value.candidates.firstOrNull { it.id == inspectedCandidate?.id }
        inspectedRule = value.rules.firstOrNull { it.id == inspectedRule?.id }
    }
    suspend fun reload() { applySnapshot(withContext(Dispatchers.IO) { repository.snapshot() }) }
    LaunchedEffect(repository) { repository.observe().collect { applySnapshot(it) } }

    fun mutate(block: suspend () -> LearningMutationResult) {
        if (busy) return
        busy = true
        scope.launch {
            mutationError = null
            try { when (val result = withContext(Dispatchers.IO) { block() }) {
                is LearningMutationResult.Success -> {
                    applySnapshot(result.snapshot)
                }
                is LearningMutationResult.Rejected -> { mutationError = result.reason; reload() }
            } } catch (_: Exception) { mutationError = "操作未完成，请重试" } finally { busy = false }
        }
    }

    fun changeSetting(key: String, value: Boolean) {
        if (busy) return
        lockState = LearningSettingLock.state(context)
        if (lockState.locked) {
            LearningSettingLock.request(context, key, value, lockReason.ifBlank { "locked setting change requested" })
            mutationError = "设置已锁定；已记录修改请求，设置未改变"
            return
        }
        busy = true
        scope.launch {
            mutationError = null
            try {
                val after = withContext(Dispatchers.IO) {
                    io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(context).applyJson(
                        "settings.learning", org.json.JSONObject().put(key, value),
                        io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER, "learning_settings")
                }
                enabled = after.getBoolean("enabled")
                autoCapture = after.getBoolean("automaticCaptureEnabled")
                lockState = LearningSettingLock.state(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) { mutationError = "设置未保存，请重试"
            } finally { busy = false }
        }
    }

    if (!visible) return

    MiuixScaffoldPage(
        title = title,
        onBack = onBack,
        compactTitle = compactTitle,
        listState = when (tab) {
            0 -> candidateListState
            1 -> ruleListState
            2 -> policyListState
            else -> maintenancePanel?.listState ?: policyListState
        },
        actions = { IconButton(onClick = { MemoryMaintenanceScheduler.enqueue(context, manual = true); mutationError = "已安排整理" }) { Icon(Icons.Rounded.Refresh, contentDescription = "立即整理") } },
        aboveContent = {
            Column {
                moduleTabs()
                NexusSettingsTabs(
                    tabs = listOf(
                        stringResource(R.string.settings_tab_candidates),
                        stringResource(R.string.settings_tab_rules),
                        stringResource(R.string.settings_tab_learning_policy),
                    ) + if (includeMemoryMaintenance) listOf(stringResource(R.string.settings_tab_memory_maintenance)) else emptyList(),
                    selectedTab = tab,
                    onTabSelected = { tab = it },
                )
                if (tab == 3) maintenancePanel?.tabs?.invoke()
            }
        },
        overlay = {
            val candidate = inspectedCandidate
            WindowDialog(
                show = candidate != null,
                title = candidate?.title.orEmpty(),
                onDismissRequest = { if (!busy) inspectedCandidate = null },
            ) {
                if (candidate != null) {
                    CandidateDetail(
                        candidate = candidate,
                        error = mutationError,
                        onConfirm = {
                            mutate {
                                val result = repository.confirm(
                                    candidate.id,
                                    overrideConflict = candidate.conflicts.isNotEmpty(),
                                    expectedConflict = candidate.conflicts.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.version}" },
                                )
                                if (result is LearningMutationResult.Success) repository.promote(candidate.id) else result
                            }
                        },
                        onPromote = { mutate { repository.promote(candidate.id) } },
                        onReject = { mutate { repository.reject(candidate.id) } },
                        onClose = { if (!busy) inspectedCandidate = null },
                        busy = busy,
                        onEdit = { content -> mutate { repository.editCandidate(candidate.id, content) } },
                    )
                }
            }
            val rule = inspectedRule
            WindowDialog(
                show = rule != null,
                title = rule?.title.orEmpty(),
                onDismissRequest = { if (!busy) inspectedRule = null },
            ) {
                if (rule != null) {
                    RuleVersions(
                        rule = rule,
                        onRollback = { versionId ->
                            mutate { repository.rollback(rule.id, versionId) }
                        },
                        onArchive = { mutate { repository.archiveRule(rule.id) } },
                        onPin = { mutate { repository.pinRule(rule.id, !rule.pinned) } },
                        busy = busy,
                    )
                }
            }
        },
    ) {
        item(key = "learning_summary") {
            SmallTitle("经验库概况")
            KnowledgeMetricsCard(
                activeCount = snapshot.rules.count { it.active },
                pendingCount = snapshot.candidates.count {
                    it.status in setOf(
                        LearningStatus.BUTLER_APPROVED,
                        LearningStatus.PENDING,
                        LearningStatus.CONFLICT,
                    )
                },
                conflictCount = snapshot.candidates.count {
                    it.conflicts.isNotEmpty() && it.status !in setOf(
                        LearningStatus.REJECTED,
                        LearningStatus.PROMOTED,
                    )
                },
                activeLabel = "生效经验",
                pendingLabel = "待审阅",
                conflictLabel = "冲突",
            )
        }
        if (tab == 2) item(key = "switches") {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    .etaGlassSurface(accent = EtaIconTone.MEMORY.accent, cornerRadius = 18.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        EtaIconTile(Icons.Rounded.Psychology, tone = EtaIconTone.MEMORY, size = 34.dp, glyphSize = 18.dp, enabled = enabled)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("学习策略", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                            Text(if (enabled) "自动积累经验" else "已关闭", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Text("经验库学习设置锁", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    Text(if (lockState.locked) "已锁定：开关修改只会登记请求" else "临时解锁至 ${lockState.leaseUntil}", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(horizontal = 14.dp))
                    Text("登记的修改请求：${LearningSettingLock.requests(context).size}", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(horizontal = 14.dp))
                    TextField(value = lockReason, onValueChange = { lockReason = it.take(240) }, label = "操作原因", singleLine = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton("锁定", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            LearningSettingLock.lock(context, lockReason)
                            lockState = LearningSettingLock.state(context)
                        })
                        TextButton("临时解锁 15 分钟", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            runCatching { LearningSettingLock.temporaryUnlock(context, 15 * 60 * 1000L, lockReason) }
                                .onSuccess { lockState = it }
                                .onFailure { mutationError = "临时解锁未生效" }
                        })
                    }
                    SwitchPreference(
                        title = "启用自学习（经验库）",
                        summary = "检索规则并提交候选经验",
                        checked = enabled,
                        enabled = !busy,
                        onCheckedChange = { changeSetting("enabled", it) },
                    )
                    SwitchPreference(
                        title = "自动捕获候选",
                        summary = "自动脱敏、去重并审核",
                        checked = enabled && autoCapture,
                        enabled = enabled && !busy,
                        onCheckedChange = { changeSetting("automaticCaptureEnabled", it) },
                    )
                }
            }
        }
        if (tab == 3) item(key = "idle_settings") { maintenancePanel?.content?.invoke() }
        if (tab != 3) maintenancePanel?.error?.let { error ->
            item(key = "maintenance_error") {
                Text(error, color = StatusError, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        if (tab < 2) item(key = "learning_search") {
            SmallTitle("查找经验")
            TextField(value = search, onValueChange = { search = it.take(100) }, label = "搜索经验", singleLine = true,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth())
            val scopes = listOf("all") + (snapshot.candidates.map { it.scope } + snapshot.rules.map { it.scope }).distinct().sorted()
            io.github.mangi.eta.ui.components.DialogChoicePreference(
                title = "经验作用域", value = if (scopeFilter == "all") "全部作用域" else io.github.mangi.eta.ui.screens.memory.memoryScopeName(scopeFilter, scopeNames),
                options = scopes.map { if (it == "all") "全部作用域" else io.github.mangi.eta.ui.screens.memory.memoryScopeName(it, scopeNames) },
                selectedIndex = scopes.indexOf(scopeFilter).coerceAtLeast(0),
                onSelectedIndexChange = { index -> scopeFilter = scopes.getOrElse(index) { "all" } })

        }
        if (tab == 0) item(key = "history_toggle") {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchPreference(
                    title = "显示已处理候选",
                    checked = showHistory,
                    onCheckedChange = { showHistory = it },
                )
            }
        }

        val pending = snapshot.candidates.filter {
            (scopeFilter == "all" || it.scope == scopeFilter) && (showHistory || it.status in setOf(
                LearningStatus.BUTLER_APPROVED,
                LearningStatus.PENDING,
                LearningStatus.CONFLICT,
                LearningStatus.CONFIRMED,
            )) &&
                (search.isBlank() || (it.title + it.content).contains(search, ignoreCase = true))
        }
        if (tab == 0) {
        item(key = "pending_title") { SmallTitle(if (showHistory) "全部候选" else "待审阅候选") }
        if (pending.isEmpty()) {
            item(key = "pending_empty") {
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ListEmptyState(
                        title = "没有待审批经验",
                        summary = "审核通过的经验会显示在这里",
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }
        } else {
            pending.forEach { candidate ->
                item(key = "candidate:${candidate.id}") {
                    CandidateCard(candidate, scopeNames) { inspectedCandidate = candidate }
                }
            }
        }
        }
        mutationError?.takeIf { inspectedCandidate == null && inspectedRule == null }?.let { error ->
            item(key = "mutation_error") {
                Text(
                    text = error,
                    color = StatusError,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        if (tab == 1) {
        item(key = "rules_title") { SmallTitle("已启用经验") }
        if (snapshot.rules.isEmpty()) {
            item(key = "rules_empty") {
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ListEmptyState(
                        title = "尚未确认长期规则",
                        summary = "确认候选后可成为长期规则",
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }
        } else {
            snapshot.rules.filter { (scopeFilter == "all" || it.scope == scopeFilter) && (search.isBlank() || (it.title + it.content).contains(search, ignoreCase = true)) }
                .sortedByDescending { it.pinned }.forEach { rule ->
                item(key = "rule:${rule.id}") {
                    RuleCard(rule, scopeNames) { inspectedRule = rule }
                }
            }
        }
        }
    }
}

@Composable
private fun CandidateCard(candidate: LearningCandidateView, scopeNames: Map<String, String>, onClick: () -> Unit) {
    Card(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .etaGlassSurface(accent = if (candidate.conflicts.isNotEmpty() || candidate.status == LearningStatus.CONFLICT) EtaIconTone.RISK.accent else EtaIconTone.MEMORY.accent, cornerRadius = 16.dp),
        colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
    ) {
        DetailListRow(title = candidate.title, preview = candidate.content,
            subtitle = "${io.github.mangi.eta.ui.screens.memory.memoryScopeName(candidate.scope, scopeNames)} · ${candidate.status.displayName()} · ${candidate.kind.displayName()} · 置信度 ${(candidate.confidence * 100).toInt()}%",
            icon = if (candidate.conflicts.isNotEmpty() || candidate.status == LearningStatus.CONFLICT) {
                Icons.Rounded.CompareArrows
            } else Icons.Rounded.FactCheck,
            tone = if (candidate.conflicts.isNotEmpty() || candidate.status == LearningStatus.CONFLICT) {
                io.github.mangi.eta.ui.components.EtaIconTone.RISK
            } else {
                io.github.mangi.eta.ui.components.EtaIconTone.MEMORY
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun CandidateDetail(
    candidate: LearningCandidateView,
    error: String?,
    onConfirm: () -> Unit,
    onPromote: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    busy: Boolean,
    onEdit: (String) -> Unit,
) {
    var editing by remember(candidate.id) { mutableStateOf(false) }
    var draft by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.content) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) TextField(value = draft, onValueChange = { draft = it.take(4000) }, label = "经验内容", modifier = Modifier.fillMaxWidth())
        else Text(candidate.content, style = MiuixTheme.textStyles.body1)
        if (candidate.status in setOf(
                LearningStatus.BUTLER_APPROVED,
                LearningStatus.PENDING,
                LearningStatus.CONFLICT,
            )
        ) {
            TextButton(if (editing) "保存修改" else "修改内容", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (editing) onEdit(draft)
                editing = !editing
            })
        }
        candidate.conflicts.forEach { conflict ->
            Text("已有经验 · v${conflict.version}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
            Text(conflict.content, style = MiuixTheme.textStyles.body2)
        }
        Text(
            text = "${candidate.kind.displayName()} · 置信度 ${(candidate.confidence * 100).toInt()}%\n来源 ${candidate.sourceRef}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (candidate.reviewSummary.isNotBlank()) {
            Text(
                text = candidate.reviewSummary,
                style = MiuixTheme.textStyles.footnote1,
                color = if (candidate.status == LearningStatus.REJECTED) {
                    MiuixTheme.colorScheme.error
                } else MiuixTheme.colorScheme.primary,
            )
        }
        if (candidate.evidence.isNotEmpty()) {
            SmallTitle("证据")
            candidate.evidence.forEach { evidence ->
                Text(
                    text = evidence.excerpt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        error?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidate.status in setOf(
                    LearningStatus.BUTLER_APPROVED,
                    LearningStatus.PENDING,
                    LearningStatus.CONFLICT,
                )
            ) {
                TextButton(text = "拒绝", onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f))
                TextButton(
                    text = if (candidate.conflicts.isNotEmpty()) "替换并启用" else "确认并启用",
                    onClick = onConfirm,
                    enabled = !busy && !editing,
                    modifier = Modifier.weight(1f),
                )
            } else if (candidate.status == LearningStatus.CONFIRMED) {
                TextButton(
                    text = "启用此经验",
                    onClick = onPromote,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        MiuixDialogActions(confirmText = "关闭", onCancel = onClose, onConfirm = onClose)
    }
}

@Composable
private fun RuleCard(rule: LearningRuleView, scopeNames: Map<String, String>, onClick: () -> Unit) {
    Card(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .etaGlassSurface(accent = EtaIconTone.MEMORY.accent, cornerRadius = 16.dp),
        colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
    ) {
        DetailListRow(title = rule.title, preview = rule.content,
            subtitle = "${io.github.mangi.eta.ui.screens.memory.memoryScopeName(rule.scope, scopeNames)} · v${rule.currentVersion} · ${if (rule.active) "生效" else "已归档"}${if (rule.pinned) " · 已固定" else ""}",
            icon = if (rule.pinned) Icons.Rounded.PushPin else Icons.Rounded.History, onClick = onClick)
    }
}

@Composable
private fun RuleVersions(
    rule: LearningRuleView,
    onRollback: (String) -> Unit,
    onArchive: () -> Unit,
    onPin: () -> Unit,
    busy: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(rule.content, style = MiuixTheme.textStyles.body1)
        TextButton(if (rule.pinned) "取消固定" else "固定到列表前方", onClick = onPin, enabled = !busy, modifier = Modifier.fillMaxWidth())
        rule.versions.sortedByDescending { it.version }.forEach { version ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "v${version.version} · ${if (version.reason.startsWith("rollback-to-v")) "回滚到 v${version.reason.substringAfter("rollback-to-v")}" else "确认启用"}" +
                            if (version.sourceRef.isNotBlank()) " · 来源 ${version.sourceRef.take(40)}" else "",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        version.content,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (version.version != rule.currentVersion) {
                    TextButton(text = "回滚", enabled = !busy, onClick = { onRollback(version.id) })
                }
            }
        }
        if (rule.active) {
            TextButton(text = "归档此规则", enabled = !busy, onClick = onArchive, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun LearningStatus.displayName(): String = when (this) {
    LearningStatus.BUTLER_REVIEW -> "软件管家审核中"
    LearningStatus.BUTLER_APPROVED -> "管家已通过，待人工批准"
    LearningStatus.PENDING -> "待软件管家审核"
    LearningStatus.CONFLICT -> "待复核冲突"
    LearningStatus.CONFIRMED -> "人工已确认，待启用"
    LearningStatus.PROMOTED -> "已启用"
    LearningStatus.REJECTED -> "未通过，已拒绝"
}
private fun LearningKind.displayName(): String = when (this) {
    LearningKind.FACT -> "事实"; LearningKind.PREFERENCE -> "偏好"; LearningKind.PROCEDURE -> "做事步骤"; LearningKind.FAILURE_FIX -> "纠错经验"
}
