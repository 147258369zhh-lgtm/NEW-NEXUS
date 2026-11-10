package io.github.mangi.eta.ui.screens.capability

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.capability.SkillMarketDetails
import io.github.mangi.eta.agent.capability.SkillMarketInstallOutcome
import io.github.mangi.eta.agent.capability.SkillMarketItem
import io.github.mangi.eta.agent.capability.SkillMarketService
import io.github.mangi.eta.agent.capability.SkillMarketSnapshot
import io.github.mangi.eta.agent.capability.SkillSourceDefinition
import io.github.mangi.eta.agent.capability.SkillSourceTrust
import io.github.mangi.eta.agent.capability.SkillUpdatePolicy
import io.github.mangi.eta.agent.capability.SkillUpdateScheduler
import io.github.mangi.eta.agent.capability.SkillUpdateService
import io.github.mangi.eta.agent.capability.SkillUpdateSettingsStore
import io.github.mangi.eta.agent.capability.SkillUpdateState
import io.github.mangi.eta.agent.capability.SkillUpdateStore
import io.github.mangi.eta.agent.capability.SkillSourceType
import io.github.mangi.eta.agent.capability.ApiConnectorDefinition
import io.github.mangi.eta.agent.capability.ApiConnectorService
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillPrerequisiteStatus
import io.github.mangi.eta.agent.skill.SkillReadinessInspector
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaMotion
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAB_OVERVIEW = 0
private const val TAB_DISCOVER = 1
private const val TAB_CONNECTIONS = 2
private const val TAB_SOURCES = 3

private data class LocalCapabilityData(
    val sources: List<SkillSourceDefinition>,
    val skills: List<SkillIndexEntry>,
    val apis: List<ApiConnectorDefinition>,
    val updateState: SkillUpdateState,
)

@Composable
internal fun CapabilityCenterScreen(
    onBack: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenApiConnectors: () -> Unit,
    onOpenA2A: () -> Unit,
    onOpenConnectorCenter: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPromptInjection: () -> Unit,
    onOpenLocalModels: () -> Unit,
    onOpenSpeechRecognition: () -> Unit,
    onOpenSpeechSynthesis: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) {
        SkillMarketService(context.applicationContext)
    }
    val apiService = remember(context.applicationContext) {
        ApiConnectorService(context.applicationContext)
    }
    val updateService = remember(context.applicationContext) {
        SkillUpdateService(context.applicationContext)
    }
    val mcpServers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(TAB_OVERVIEW) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf<List<SkillSourceDefinition>>(emptyList()) }
    var installedSkills by remember { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var apiConnectors by remember { mutableStateOf<List<ApiConnectorDefinition>>(emptyList()) }
    var market by remember { mutableStateOf<SkillMarketSnapshot?>(null) }
    var searching by remember { mutableStateOf(false) }
    var localLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<CapabilityNotice?>(null) }
    var details by remember { mutableStateOf<SkillMarketDetails?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var pendingUnverified by remember { mutableStateOf<SkillMarketItem?>(null) }
    var pendingReplacement by remember { mutableStateOf<PendingCapabilityReplacement?>(null) }
    var showAddSource by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(MarketFilters()) }
    var updateState by remember { mutableStateOf(SkillUpdateState()) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var automaticUpdates by remember {
        mutableStateOf(SkillUpdateSettingsStore.enabled(context.applicationContext))
    }

    val visibleMarketItems = market?.items.orEmpty().filter { item ->
        (filters.sourceId == null || item.sourceId == filters.sourceId) &&
            (filters.trust == null || item.trust == filters.trust) &&
            (filters.tag == null || item.tags.any { it.equals(filters.tag, ignoreCase = true) }) &&
            when (filters.versionState) {
                VersionFilter.ALL -> true
                VersionFilter.UPDATES -> SkillUpdatePolicy.hasAvailableUpdate(item, installedSkills)
                VersionFilter.NOT_INSTALLED -> installedSkills.none { it.id == item.skillId && it.installed }
            }
    }

    fun refreshLocal() {
        scope.launch {
            localLoading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loadedSources = service.sources()
                    val loadedSkills = SkillRuntime.createIndexService(context.applicationContext)
                        .listSkillsForManagement(forceRefresh = true)
                        .filter(SkillIndexEntry::installed)
                    LocalCapabilityData(
                        sources = loadedSources,
                        skills = loadedSkills,
                        apis = apiService.connectors(),
                        updateState = SkillUpdateStore(context.applicationContext).read(),
                    )
                }
            }
            result.onSuccess { loaded ->
                sources = loaded.sources
                installedSkills = loaded.skills
                apiConnectors = loaded.apis
                updateState = loaded.updateState
            }.onFailure {
                notice = CapabilityNotice(
                    title = "能力信息读取失败",
                    message = safeMessage(it),
                    isError = true,
                )
            }
            localLoading = false
        }
    }

    fun searchMarket() {
        if (searching) return
        searching = true
        scope.launch {
            try {
                market = service.search(query)
                val failed = market?.statuses?.count { !it.succeeded }.orZero()
                if (failed > 0) {
                    notice = CapabilityNotice(
                        title = "部分来源暂时不可用",
                        message = failed.toString() + " 个来源未能更新，其余来源的结果仍可使用",
                        isError = false,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                notice = CapabilityNotice(
                    title = "Skill 搜索失败",
                    message = safeMessage(failure),
                    isError = true,
                )
            } finally {
                searching = false
            }
        }
    }

    fun openDetails(item: SkillMarketItem) {
        if (detailLoading) return
        details = SkillMarketDetails(item = item)
        detailLoading = true
        scope.launch {
            try {
                details = service.loadDetails(item)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                details = SkillMarketDetails(item = item, detailWarning = safeMessage(failure))
            } finally {
                detailLoading = false
            }
        }
    }

    fun checkUpdates() {
        if (checkingUpdates) return
        checkingUpdates = true
        scope.launch {
            try {
                updateState = updateService.check(notify = false)
                updateState.error?.let { message ->
                    notice = CapabilityNotice("更新检查未完成", message, true)
                }
            } finally {
                checkingUpdates = false
            }
        }
    }

    fun openAvailableUpdates() {
        filters = filters.copy(versionState = VersionFilter.UPDATES)
        tab = TAB_DISCOVER
    }

    fun install(
        item: SkillMarketItem,
        replaceSkillId: String? = null,
        allowUnverified: Boolean = false,
    ) {
        if (installing) return
        installing = true
        scope.launch {
            val outcome = service.install(
                item = item,
                replaceSkillId = replaceSkillId,
                allowUnverified = allowUnverified,
            )
            when (outcome) {
                is SkillMarketInstallOutcome.Success -> {
                    pendingUnverified = null
                    pendingReplacement = null
                    details = null
                    val setupHint = if (outcome.setupRequiredSkillIds.isEmpty()) {
                        "已启用，将在下一轮对话中可用"
                    } else {
                        "已安装；" + outcome.setupRequiredSkillIds.size + " 个因依赖未满足暂未启用"
                    }
                    val warningHint = outcome.prerequisiteWarnings.firstOrNull()?.let { "\n" + it }.orEmpty()
                    notice = CapabilityNotice(
                        title = "Skill 已安装",
                        message = outcome.skills.joinToString { it.name } + " " + setupHint + warningHint,
                        isError = outcome.setupRequiredSkillIds.isNotEmpty(),
                    )
                    updateState = updateState.copy(
                        updates = updateState.updates.filterNot { it.key == item.key },
                    )
                    refreshLocal()
                    market = market?.copy(
                        items = market?.items.orEmpty().filterNot { it.key == item.key },
                    )
                }
                is SkillMarketInstallOutcome.Conflict -> {
                    val conflict = outcome.skills.firstOrNull()
                    if (conflict == null) {
                        notice = CapabilityNotice("无法替换", "安装器未返回可替换目标", true)
                    } else if (!conflict.replaceAllowed) {
                        notice = CapabilityNotice(
                            "同名 Skill 受保护",
                            "现有 " + conflict.name + " 不是可替换的用户 Skill",
                            true,
                        )
                    } else {
                        pendingReplacement = PendingCapabilityReplacement(
                            item = item,
                            skillId = conflict.id,
                            displayName = conflict.name,
                        )
                    }
                }
                is SkillMarketInstallOutcome.ConfirmationRequired -> {
                    pendingUnverified = item
                    notice = CapabilityNotice("需要确认来源", outcome.reason, false)
                }
                is SkillMarketInstallOutcome.Failure -> {
                    notice = CapabilityNotice(
                        title = if (outcome.recoveryRequired) "安装需要恢复" else "安装失败",
                        message = outcome.message,
                        isError = true,
                    )
                }
            }
            installing = false
        }
    }

    LaunchedEffect(refreshGeneration) {
        refreshLocal()
    }
    LaunchedEffect(tab) {
        if (tab == TAB_DISCOVER && market == null) searchMarket()
    }
    DisposableEffect(service) {
        onDispose { service.close() }
    }

    MiuixScaffoldPage(
        title = "能力中心",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !localLoading && !searching,
                onClick = {
                    refreshGeneration++
                    if (tab == TAB_DISCOVER) searchMarket()
                },
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新能力中心")
            }
        },
    ) {
        item(key = "capability_tabs") {
            TabRow(
                tabs = listOf("概览", "发现", "连接", "来源"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        when (tab) {
            TAB_OVERVIEW -> {
                item(key = "overview_metrics") {
                    SmallTitle("能力概况")
                    CapabilityMetricsCard(
                        skillCount = installedSkills.count(SkillIndexEntry::enabled),
                        connectionCount = mcpServers.count { it.enabled } + apiConnectors.count { it.enabled },
                        sourceCount = sources.count(SkillSourceDefinition::enabled),
                    )
                }
                item(key = "overview_entries") {
                    SmallTitle("能力管理")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).etaGlassSurface()) {
                        DetailListRow(
                            title = "已安装 Skills",
                            subtitle = "${installedSkills.size} 个已安装",
                            icon = Icons.Rounded.AutoAwesome,
                            tone = EtaIconTone.MEMORY,
                            value = installedSkills.count { it.enabled }.toString() + " 已启用",
                            onClick = onOpenSkills,
                        )
                        DetailListRow(
                            title = "MCP 与外部工具",
                            subtitle = "${mcpServers.count { it.activeTools.isNotEmpty() }} 个连接可用",
                            icon = Icons.Rounded.Hub,
                            tone = EtaIconTone.TOOL,
                            onClick = onOpenMcp,
                        )
                        DetailListRow(
                            title = "提示词与工具边界",
                            subtitle = "按模型、会话、会议室与子 Agent 设置注入范围",
                            icon = Icons.Rounded.Description,
                            tone = EtaIconTone.AUTOMATION,
                            onClick = onOpenPromptInjection,
                        )
                        DetailListRow(
                            title = "REST API 连接",
                            subtitle = "${apiConnectors.count { it.activeActions.isNotEmpty() }} 个连接可用",
                            icon = Icons.Rounded.Link,
                            tone = EtaIconTone.DATA,
                            value = apiConnectors.size.toString() + " 个已导入",
                            onClick = onOpenApiConnectors,
                        )
                        DetailListRow(
                            title = "本地模型与视觉",
                            subtitle = "文本、语音、视觉与向量能力",
                            icon = Icons.Rounded.Memory,
                            tone = EtaIconTone.MODEL,
                            onClick = onOpenLocalModels,
                        )
                        DetailListRow(
                            title = "语音识别",
                            subtitle = "本地与在线 ASR",
                            icon = Icons.Rounded.Mic,
                            tone = EtaIconTone.TOOL,
                            onClick = onOpenSpeechRecognition,
                        )
                        DetailListRow(
                            title = "语音合成",
                            subtitle = "阅读播报与流式 TTS",
                            icon = Icons.Rounded.RecordVoiceOver,
                            tone = EtaIconTone.TOOL,
                            onClick = onOpenSpeechSynthesis,
                        )
                        DetailListRow(
                            title = "工具边界与审批",
                            subtitle = "Skill、MCP、设备与文件权限",
                            icon = Icons.Rounded.Security,
                            tone = EtaIconTone.RISK,
                            onClick = onOpenPermissions,
                        )
                    }
                }
                item(key = "overview_skill_updates") {
                    SmallTitle("Skill 更新")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        SwitchPreference(
                            title = "自动检查更新",
                            summary = "联网时每天检查已安装 Skill 的来源，只在发现更新时通知",
                            checked = automaticUpdates,
                            onCheckedChange = { enabled ->
                                automaticUpdates = enabled
                                SkillUpdateSettingsStore.setEnabled(context.applicationContext, enabled)
                            },
                        )
                        DetailListRow(
                            title = if (checkingUpdates) "正在检查更新" else "立即检查",
                            subtitle = skillUpdateStatus(updateState),
                            icon = Icons.Rounded.Refresh,
                            value = if (updateState.failedSourceCount > 0) {
                                "${updateState.failedSourceCount} 个来源失败"
                            } else null,
                            enabled = !checkingUpdates,
                            onClick = { checkUpdates() },
                        )
                        if (updateState.updates.isNotEmpty()) {
                            DetailListRow(
                                title = "${updateState.updates.size} 个 Skill 可更新",
                                subtitle = updateState.updates.take(2).joinToString(" · ") { it.displayName } +
                                    if (updateState.updates.size > 2) " 等" else "",
                                icon = Icons.Rounded.VerifiedUser,
                                value = "查看",
                                onClick = { openAvailableUpdates() },
                            )
                        }
                    }
                }
                item(key = "overview_recent") {
                    SmallTitle("近期安装")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        val recent = installedSkills
                            .filter { it.provenance != null }
                            .sortedByDescending { it.provenance?.installedAt ?: 0L }
                            .take(4)
                        if (recent.isEmpty()) {
                            CapabilityEmptyRow(
                                icon = Icons.Rounded.Inventory2,
                                title = "还没有市场安装记录",
                                subtitle = "发现页可从 GitHub 或 HTTPS Skill 目录安装",
                            )
                        } else {
                            recent.forEach { skill ->
                                DetailListRow(
                                    title = skill.name.ifBlank { skill.id },
                                    subtitle = buildString {
                                        append(skill.provenance?.sourceName.orEmpty())
                                        append(" · ")
                                        append(formatCapabilityTime(skill.provenance?.installedAt))
                                    },
                                    icon = Icons.Rounded.VerifiedUser,
                                    onClick = onOpenSkills,
                                )
                            }
                        }
                    }
                }
            }

            TAB_DISCOVER -> {
                item(key = "market_search") {
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).etaGlassSurface()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "发现可复用能力",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "并发搜索全部可用来源",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(120) },
                                label = "名称、用途、作者或标签",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    text = if (filters.activeCount == 0) "筛选" else "筛选 ${filters.activeCount}",
                                    enabled = market != null,
                                    onClick = { showFilters = true },
                                )
                                if (searching) {
                                    InfiniteProgressIndicator(size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                TextButton(
                                    text = if (searching) "搜索中" else "搜索 Skill",
                                    enabled = !searching,
                                    onClick = { searchMarket() },
                                )
                            }
                        }
                    }
                }
                market?.let { snapshot ->
                    if (snapshot.statuses.isNotEmpty()) {
                        item(key = "market_sources_status") {
                            SmallTitle("来源状态")
                            SourceHealthCard(snapshot)
                        }
                    }
                    item(key = "market_result_title") {
                        SmallTitle("结果 " + visibleMarketItems.size)
                    }
                    if (visibleMarketItems.isEmpty()) {
                        item(key = "market_empty") {
                            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                                CapabilityEmptyRow(
                                    icon = Icons.Rounded.Search,
                                    title = if (snapshot.items.isEmpty()) {
                                        if (query.isBlank()) "来源中没有可用 Skill" else "没有匹配的 Skill"
                                    } else {
                                        "当前筛选没有结果"
                                    },
                                    subtitle = if (filters.activeCount > 0) {
                                        "调整筛选条件，或清除筛选查看全部结果"
                                    } else {
                                        "可以更换关键词，或在来源页添加自定义目录"
                                    },
                                )
                            }
                        }
                    }
                    visibleMarketItems.take(MAX_VISIBLE_MARKET_ITEMS).forEach { item ->
                        val installedSkill = installedSkills.firstOrNull {
                            it.id == item.skillId && it.installed
                        }
                        item(key = "market_" + item.key) {
                            MarketItemCard(
                                item = item,
                                installed = installedSkill != null,
                                updateAvailable = SkillUpdatePolicy.hasAvailableUpdate(item, installedSkills),
                                onClick = { openDetails(item) },
                            )
                        }
                    }
                    if (visibleMarketItems.size > MAX_VISIBLE_MARKET_ITEMS) {
                        item(key = "market_limit") {
                            CapabilityNote(
                                "结果较多，当前显示前 " + MAX_VISIBLE_MARKET_ITEMS +
                                    " 个。请缩小搜索范围以查看更精确的结果。",
                            )
                        }
                    }
                }
            }

            TAB_CONNECTIONS -> {
                item(key = "connections_intro") {
                    SmallTitle("外部连接")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        DetailListRow(
                            title = "MCP 服务",
                            subtitle = "工具、资源与提示词",
                            icon = Icons.Rounded.Hub,
                            tone = EtaIconTone.TOOL,
                            value = mcpServers.size.toString() + " 个已配置",
                            onClick = onOpenMcp,
                        )
                        DetailListRow(
                            title = "REST API",
                            subtitle = "OpenAPI 与逐操作授权",
                            icon = Icons.Rounded.Link,
                            tone = EtaIconTone.DATA,
                            value = apiConnectors.size.toString() + " 个已配置",
                            onClick = onOpenApiConnectors,
                        )
                        DetailListRow(
                            title = "A2A Agent 协作",
                            subtitle = "Agent Card、双向任务与流式事件",
                            icon = Icons.Rounded.Groups,
                            tone = EtaIconTone.AUTOMATION,
                            value = "Agent 网络",
                            onClick = onOpenA2A,
                        )
                        DetailListRow(
                            title = "统一连接中心",
                            subtitle = "MCP、API、Intent 与本机服务",
                            icon = Icons.Rounded.Hub,
                            tone = EtaIconTone.TOOL,
                            value = "Connector SDK",
                            onClick = onOpenConnectorCenter,
                        )
                    }
                }
                item(key = "connections_health") {
                    SmallTitle("连接健康")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        if (mcpServers.isEmpty() && apiConnectors.isEmpty()) {
                            CapabilityEmptyRow(
                                icon = Icons.Rounded.Link,
                                title = "尚未连接外部工具",
                                subtitle = "先添加 MCP 服务，再按需启用具体工具",
                            )
                        } else {
                            mcpServers.forEach { server ->
                                McpCapabilityRow(server)
                            }
                            apiConnectors.forEach { connector ->
                                ApiCapabilityRow(connector)
                            }
                        }
                    }
                }
                item(key = "connections_security") {
                    SmallTitle("安全边界")
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        DetailListRow(
                            title = "连接凭据按设备加密保存",
                            subtitle = "令牌不会进入对话",
                            icon = Icons.Rounded.Security,
                            tone = EtaIconTone.RISK,
                            onClick = onOpenMcp,
                        )
                    }
                }
            }

            TAB_SOURCES -> {
                item(key = "sources_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Skill 来源", style = MiuixTheme.textStyles.body1)
                            Text(
                                "${sources.count(SkillSourceDefinition::enabled)} 个来源已启用",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = "添加",
                            onClick = { showAddSource = true },
                        )
                    }
                }
                item(key = "sources_list") {
                    SmallTitle("来源列表 " + sources.size)
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        if (sources.isEmpty()) {
                            CapabilityEmptyRow(
                                icon = Icons.Rounded.Source,
                                title = "没有 Skill 来源",
                                subtitle = "添加 GitHub 仓库或 Nexus Skill 目录",
                            )
                        } else {
                            sources.forEach { source ->
                                SourceRow(
                                    source = source,
                                    status = market?.statuses?.firstOrNull { it.sourceId == source.id },
                                    onToggle = { enabled ->
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    service.setSourceEnabled(source.id, enabled)
                                                }
                                            }.onSuccess {
                                                sources = it
                                                market = null
                                            }.onFailure {
                                                notice = CapabilityNotice("来源更新失败", safeMessage(it), true)
                                            }
                                        }
                                    },
                                    onDelete = {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    service.deleteSource(source.id)
                                                }
                                            }.onSuccess {
                                                sources = it
                                                market = null
                                            }.onFailure {
                                                notice = CapabilityNotice("无法删除来源", safeMessage(it), true)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "sources_reset") {
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        ArrowPreference(
                            title = "恢复内置来源",
                            summary = "保留自定义来源，重新启用 OpenAI 精选 Skill 来源",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { service.resetBuiltinSources() }
                                    }.onSuccess {
                                        sources = it
                                        market = null
                                    }.onFailure {
                                        notice = CapabilityNotice("无法恢复内置来源", safeMessage(it), true)
                                    }
                                }
                            },
                        )
                    }
                }
                item(key = "sources_note") {
                    CapabilityNote(
                        "GitHub 来源会先解析到固定 commit，再下载安装。HTTPS 目录必须使用 " +
                            "eta-skill-catalog/v1；未提供 SHA-256 的包每次安装都会要求确认。",
                    )
                }
            }
        }

        if (localLoading) {
            item(key = "local_loading") {
                LoadingCapabilityRow()
            }
        }
    }

    details?.let { current ->
        MarketDetailDialog(
            details = current,
            loading = detailLoading,
            installing = installing,
            onDismiss = {
                if (!installing) {
                    details = null
                    detailLoading = false
                }
            },
            onInstall = { install(current.item) },
        )
    }

    pendingUnverified?.let { item ->
        WindowDialog(
            show = true,
            title = "安装未校验的 Skill？",
            summary = "该来源没有提供 SHA-256。Nexus 仍会检查路径、大小和元数据，但无法证明下载内容与发布者一致。",
            onDismissRequest = { pendingUnverified = null },
        ) {
            MiuixDialogActions(
                confirmText = "确认安装",
                destructive = true,
                confirmEnabled = !installing,
                onCancel = { pendingUnverified = null },
                onConfirm = {
                    pendingUnverified = null
                    install(item, allowUnverified = true)
                },
            )
        }
    }

    pendingReplacement?.let { replacement ->
        WindowDialog(
            show = true,
            title = "替换现有 Skill？",
            summary = replacement.displayName + " 已由用户安装。替换会先备份旧版本；内置 Skill 不会被覆盖。",
            onDismissRequest = { pendingReplacement = null },
        ) {
            MiuixDialogActions(
                confirmText = "替换",
                destructive = true,
                confirmEnabled = !installing,
                onCancel = { pendingReplacement = null },
                onConfirm = {
                    pendingReplacement = null
                    install(
                        item = replacement.item,
                        replaceSkillId = replacement.skillId,
                        allowUnverified = !replacement.item.integrityVerified,
                    )
                },
            )
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onAdd = { type, name, location, ref, path ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            service.addSource(type, name, location, ref, path)
                        }
                    }.onSuccess {
                        sources = it
                        market = null
                        showAddSource = false
                        notice = CapabilityNotice(
                            "来源已添加",
                            name.trim() + " 已启用，可在发现页搜索",
                            false,
                        )
                    }.onFailure {
                        notice = CapabilityNotice("来源无效", safeMessage(it), true)
                    }
                }
            },
        )
    }

    if (showFilters) {
        MarketFiltersDialog(
            filters = filters,
            sources = sources,
            items = market?.items.orEmpty(),
            onDismiss = { showFilters = false },
            onApply = {
                filters = it
                showFilters = false
            },
        )
    }

    notice?.let { current ->
        WindowDialog(
            show = true,
            title = current.title,
            summary = current.message,
            onDismissRequest = { notice = null },
        ) {
            TextButton(
                text = "知道了",
                onClick = { notice = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MarketFiltersDialog(
    filters: MarketFilters,
    sources: List<SkillSourceDefinition>,
    items: List<SkillMarketItem>,
    onDismiss: () -> Unit,
    onApply: (MarketFilters) -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    val tags = remember(items) {
        items.flatMap(SkillMarketItem::tags)
            .groupingBy { it.trim() }
            .eachCount()
            .filterKeys(String::isNotBlank)
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map(Map.Entry<String, Int>::key)
            .take(MAX_FILTER_TAGS)
    }
    WindowDialog(
        show = true,
        title = "筛选 Skill",
        summary = "筛选在已获取的结果内即时生效，不会额外请求来源。",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FilterSectionLabel("来源")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EtaChoiceChip(
                        text = "全部来源",
                        selected = draft.sourceId == null,
                        onClick = { draft = draft.copy(sourceId = null) },
                    )
                    sources.forEach { source ->
                        EtaChoiceChip(
                            text = compactFilterLabel(source.name, 20),
                            selected = draft.sourceId == source.id,
                            onClick = { draft = draft.copy(sourceId = source.id) },
                        )
                    }
                }
                FilterSectionLabel("信任级别")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EtaChoiceChip(
                        text = "全部",
                        selected = draft.trust == null,
                        onClick = { draft = draft.copy(trust = null) },
                    )
                    listOf(
                        SkillSourceTrust.OFFICIAL,
                        SkillSourceTrust.CURATED,
                        SkillSourceTrust.COMMUNITY,
                        SkillSourceTrust.CUSTOM,
                    ).forEach { trust ->
                        EtaChoiceChip(
                            text = trustLabel(trust),
                            selected = draft.trust == trust,
                            onClick = { draft = draft.copy(trust = trust) },
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    FilterSectionLabel("分类与标签")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EtaChoiceChip(
                            text = "全部分类",
                            selected = draft.tag == null,
                            onClick = { draft = draft.copy(tag = null) },
                        )
                        tags.forEach { tag ->
                            EtaChoiceChip(
                                text = compactFilterLabel(tag, 24),
                                selected = draft.tag.equals(tag, ignoreCase = true),
                                onClick = { draft = draft.copy(tag = tag) },
                            )
                        }
                    }
                }
                FilterSectionLabel("版本状态")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        VersionFilter.ALL to "全部版本",
                        VersionFilter.UPDATES to "可更新",
                        VersionFilter.NOT_INSTALLED to "未安装",
                    ).forEach { (state, label) ->
                        EtaChoiceChip(
                            text = label,
                            selected = draft.versionState == state,
                            onClick = { draft = draft.copy(versionState = state) },
                        )
                    }
                }
                if (tags.isEmpty()) {
                    CapabilityInlineWarning("当前结果没有分类标签，可先刷新来源或更换关键词。")
                }
                TextButton(
                    text = "清除全部筛选",
                    enabled = draft.activeCount > 0,
                    onClick = { draft = MarketFilters() },
                )
            }
            MiuixDialogActions(
                confirmText = if (draft.activeCount == 0) "应用" else "应用 ${draft.activeCount} 项筛选",
                onCancel = onDismiss,
                onConfirm = { onApply(draft) },
            )
        }
    }
}

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun CapabilityMetricsCard(
    skillCount: Int,
    connectionCount: Int,
    sourceCount: Int,
) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CapabilityMetric(skillCount, "启用 Skill", Icons.Rounded.AutoAwesome, Modifier.weight(1f))
            CapabilityMetric(connectionCount, "外部连接", Icons.Rounded.Hub, Modifier.weight(1f))
            CapabilityMetric(sourceCount, "可用来源", Icons.Rounded.Source, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CapabilityMetric(
    value: Int,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        EtaIconTile(icon, tone = EtaIconTone.TOOL, size = 34.dp, glyphSize = 18.dp)
        Crossfade(
            targetState = value,
            animationSpec = EtaMotion.standard(),
            label = "capability-metric-value",
        ) { current ->
            Text(
                text = current.toString(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceHealthCard(snapshot: SkillMarketSnapshot) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            snapshot.statuses.forEachIndexed { index, status ->
                if (index > 0) {
                    Spacer(Modifier.height(1.dp).fillMaxWidth())
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (status.succeeded) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.Security
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (status.succeeded) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = status.sourceName,
                            style = MiuixTheme.textStyles.body2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (status.succeeded) {
                                status.itemCount.toString() + " 个 Skill"
                            } else {
                                status.message ?: "暂时不可用"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketItemCard(
    item: SkillMarketItem,
    installed: Boolean,
    updateAvailable: Boolean,
    onClick: () -> Unit,
) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PreferenceIcon(
                if (item.integrityVerified) Icons.Rounded.VerifiedUser else Icons.Rounded.AutoAwesome,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.displayName,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (updateAvailable) {
                        CapabilityBadge("可更新", MiuixTheme.colorScheme.primary)
                    } else if (installed) {
                        CapabilityBadge("已安装", MiuixTheme.colorScheme.primary)
                    }
                }
                Text(
                    text = item.description.ifBlank { item.skillId },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(trustLabel(item.trust))
                        append("  ·  ")
                        append(item.sourceName)
                        if (item.version != null) {
                            append("  ·  v")
                            append(item.version)
                        }
                        append("  ·  ")
                        append(if (item.integrityVerified) "完整性已固定" else "安装前需确认")
                    },
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CapabilityBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = color,
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun MarketDetailDialog(
    details: SkillMarketDetails,
    loading: Boolean,
    installing: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val item = details.item
    val context = LocalContext.current.applicationContext
    val prerequisiteSummary = remember(details.dependencies) {
        if (details.dependencies.isEmpty()) {
            null
        } else {
            runCatching {
                val reports = SkillReadinessInspector.inspect(
                    details.dependencies,
                    SkillReadinessInspector.capture(context),
                )
                val blocked = reports.filter { it.status == SkillPrerequisiteStatus.BLOCKED }
                val unverified = reports.filter { it.status == SkillPrerequisiteStatus.UNVERIFIED }
                when {
                    blocked.isNotEmpty() -> "未满足：" + blocked.joinToString("、") { it.requirement }
                    unverified.isNotEmpty() -> "需要在运行前确认：" + unverified.joinToString("、") { it.requirement }
                    else -> "当前设备检查通过"
                }
            }.getOrElse { "暂时无法检查设备依赖" }
        }
    }
    WindowDialog(
        show = true,
        title = item.displayName,
        summary = trustLabel(item.trust) + " · " + item.sourceName,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = item.description.ifBlank { "暂无简介" },
                    style = MiuixTheme.textStyles.body2,
                )
                DetailFactRow("来源", item.sourceName)
                DetailFactRow("发布者", item.publisher ?: "未提供")
                DetailFactRow("版本", item.version ?: "未声明")
                DetailFactRow(
                    "完整性",
                    if (item.integrityVerified) {
                        if (item.archiveSha256 != null) "SHA-256 校验" else "GitHub commit 固定"
                    } else {
                        "未提供 SHA-256，安装前需确认"
                    },
                )
                item.path?.let { DetailFactRow("路径", it) }
                if (item.tags.isNotEmpty()) {
                    DetailFactRow("标签", item.tags.joinToString(" · "))
                }
                if (details.dependencies.isNotEmpty()) {
                    DetailFactRow("依赖", details.dependencies.joinToString(" · "))
                    prerequisiteSummary?.let { DetailFactRow("依赖状态", it) }
                }
                if (details.permissions.isNotEmpty()) {
                    DetailFactRow("所需权限", details.permissions.joinToString(" · "))
                }
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        InfiniteProgressIndicator(size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取详细说明", style = MiuixTheme.textStyles.footnote1)
                    }
                }
                details.detailWarning?.let {
                    CapabilityInlineWarning(it)
                }
                details.readmeExcerpt?.takeIf(String::isNotBlank)?.let { content ->
                    Text(
                        text = "使用说明",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 20.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                details.releaseNotes?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = "更新记录",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(it, style = MiuixTheme.textStyles.footnote1)
                }
                CapabilityInlineWarning(
                    "安装会检查 ZIP 路径、解压大小、文件数量与 SKILL.md 元数据；" +
                        "不会自动运行包内脚本。",
                )
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = if (installing) "安装中" else "安装 Skill",
                confirmEnabled = !loading && !installing,
                cancelEnabled = !installing,
                onCancel = onDismiss,
                onConfirm = onInstall,
            )
        }
    }
}

@Composable
private fun DetailFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ApiCapabilityRow(connector: ApiConnectorDefinition) {
    val status = when {
        !connector.enabled -> "已停用"
        connector.actions.none { it.enabled } -> "待配置操作"
        else -> connector.activeActions.size.toString() + " 个操作已启用"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreferenceIcon(Icons.Rounded.Link)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = connector.name,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status + " · " + connector.baseUrl,
                style = MiuixTheme.textStyles.footnote1,
                color = if (connector.enabled && connector.actions.any { it.enabled }) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun McpCapabilityRow(server: McpServerSetting) {
    val stale = server.toolsExpireAt?.let { it < System.currentTimeMillis() } == true
    val status = when {
        !server.enabled -> "已停用"
        stale -> "工具目录待刷新"
        server.activeTools.isNotEmpty() -> server.activeTools.size.toString() + " 个工具已启用"
        else -> "尚未启用工具"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreferenceIcon(Icons.Rounded.Hub)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = server.name,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status + if (server.lastProtocolVersion != null) {
                    " · " + server.lastProtocolVersion
                } else {
                    ""
                },
                style = MiuixTheme.textStyles.footnote1,
                color = if (server.enabled && !stale) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: SkillSourceDefinition,
    status: io.github.mangi.eta.agent.capability.SkillSourceFetchStatus?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = source.name,
            summary = buildString {
                append(if (source.type == SkillSourceType.GITHUB_REPOSITORY) "GitHub" else "HTTPS 目录")
                append(" · ")
                append(source.location)
                if (status != null) {
                    append("\n")
                    append(
                        if (status.succeeded) {
                            "最近更新：" + status.itemCount + " 个 Skill"
                        } else {
                            status.message ?: "最近更新失败"
                        },
                    )
                }
            },
            checked = source.enabled,
            onCheckedChange = onToggle,
            startAction = {
                PreferenceIcon(
                    if (source.type == SkillSourceType.GITHUB_REPOSITORY) {
                        Icons.Rounded.Public
                    } else {
                        Icons.Rounded.Source
                    },
                )
            },
            endActions = {
                if (!source.builtIn) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "删除 " + source.name,
                            tint = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String?, String?) -> Unit,
) {
    var typeIndex by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    val type = if (typeIndex == 0) {
        SkillSourceType.GITHUB_REPOSITORY
    } else {
        SkillSourceType.HTTP_CATALOG
    }
    WindowDialog(
        show = true,
        title = "添加 Skill 来源",
        summary = "来源配置保存在应用私有目录，不包含任何凭据。",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            TabRow(
                tabs = listOf("GitHub", "HTTPS 目录"),
                selectedTabIndex = typeIndex,
                onTabSelected = { typeIndex = it },
                modifier = Modifier.padding(bottom = 14.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = "显示名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = location,
                    onValueChange = { location = it.take(2_048) },
                    label = if (typeIndex == 0) "owner/repository 或 GitHub URL" else "HTTPS 目录 JSON 地址",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (typeIndex == 0) {
                    TextField(
                        value = ref,
                        onValueChange = { ref = it.take(200) },
                        label = "分支、标签或 commit（可选）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = path,
                        onValueChange = { path = it.take(1_000) },
                        label = "仓库内路径（可选）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CapabilityInlineWarning(
                    if (typeIndex == 0) {
                        "仅支持公共 github.com 仓库。搜索时解析并固定 commit，安装不会跟随分支变化。"
                    } else {
                        "目录必须使用 HTTPS，且需声明 schema=eta-skill-catalog/v1；建议为每个包提供 SHA-256。"
                    },
                )
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = "添加来源",
                confirmEnabled = name.isNotBlank() && location.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    onAdd(
                        type,
                        name,
                        location,
                        ref.takeIf(String::isNotBlank),
                        path.takeIf(String::isNotBlank),
                    )
                },
            )
        }
    }
}

@Composable
private fun CapabilityEmptyRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreferenceIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CapabilityInlineWarning(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 19.sp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun CapabilityNote(text: String) {
    Card(Modifier.padding(horizontal = 12.dp, vertical = 3.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 20.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun LoadingCapabilityRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfiniteProgressIndicator(size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("正在读取能力信息", style = MiuixTheme.textStyles.body2)
    }
}

private data class CapabilityNotice(
    val title: String,
    val message: String,
    val isError: Boolean,
)

private data class PendingCapabilityReplacement(
    val item: SkillMarketItem,
    val skillId: String,
    val displayName: String,
)

private enum class VersionFilter {
    ALL,
    UPDATES,
    NOT_INSTALLED,
}

private data class MarketFilters(
    val sourceId: String? = null,
    val trust: String? = null,
    val tag: String? = null,
    val versionState: VersionFilter = VersionFilter.ALL,
) {
    val activeCount: Int
        get() = listOfNotNull(
            sourceId,
            trust,
            tag,
            versionState.takeUnless { it == VersionFilter.ALL },
        ).size
}

private fun compactFilterLabel(value: String, maxLength: Int): String =
    value.trim().let { if (it.length <= maxLength) it else it.take(maxLength - 1) + "…" }

private fun trustLabel(trust: String): String = when (trust) {
    SkillSourceTrust.OFFICIAL -> "官方"
    SkillSourceTrust.CURATED -> "精选"
    SkillSourceTrust.COMMUNITY -> "社区"
    else -> "自定义"
}

private fun safeMessage(failure: Throwable): String = failure.message
    ?.lineSequence()
    ?.firstOrNull()
    ?.trim()
    ?.take(300)
    .orEmpty()
    .ifBlank { "未知错误，请稍后重试" }

private fun formatCapabilityTime(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "时间未知"
    return SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
}

private fun skillUpdateStatus(state: SkillUpdateState): String = when {
    state.error != null -> state.error
    state.checkedAt <= 0L -> "尚未检查。检查只读取来源目录和版本信息。"
    state.updates.isEmpty() -> "上次检查：${formatCapabilityDateTime(state.checkedAt)} · 已是最新"
    else -> "上次检查：${formatCapabilityDateTime(state.checkedAt)}"
}

private fun formatCapabilityDateTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun Int?.orZero(): Int = this ?: 0

private const val MAX_VISIBLE_MARKET_ITEMS = 100
private const val MAX_FILTER_TAGS = 16
