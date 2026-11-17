package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.role.RoleManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.device.NexaSoftReboot
import io.github.mangi.eta.agent.device.NexaSoftRebootResult
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.SettingsRuntimeSnapshotStore
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.app.accessibilityPermissionHealthItem
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.components.color
import kotlinx.coroutines.flow.flowOf
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.NexusSettingsGroup
import io.github.mangi.eta.ui.components.NexusSettingsSectionTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private object SettingsIconColors {
    val Blue = EtaIconTone.MODEL.accent
    val Green = EtaIconTone.SYSTEM.accent
    val Orange = EtaIconTone.MEMORY.accent
    val Yellow = StatusWarning
    val Automation = EtaIconTone.AUTOMATION.accent
    val Tool = EtaIconTone.TOOL.accent
    val Data = EtaIconTone.DATA.accent
    val System = EtaIconTone.SYSTEM.accent
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) = NexusSettingsGroup(content = content)

@Composable
private fun SettingsIcon(icon: ImageVector, tint: Color, enabled: Boolean = true) {
    EtaIconTile(icon = icon, modifier = Modifier.padding(end = 4.dp), accentColor = tint, enabled = enabled)
}

@Composable
private fun SettingsSectionHeader(title: String) = NexusSettingsSectionTitle(title)

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

private enum class SettingsPage { HOME, ASSISTANT, GESTURES, SYSTEM_TOOLS }

private data class SettingsSearchEntry(
    val title: String,
    val keywords: String,
    val route: AppRoute,
)

@Composable
internal fun DigitalAssistantSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) =
    SettingsPageContent(LocalContext.current, onNavigate, onBack, SettingsPage.ASSISTANT)

@Composable
internal fun SearchGestureSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) =
    SettingsPageContent(LocalContext.current, onNavigate, onBack, SettingsPage.GESTURES)

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    page: SettingsPage = SettingsPage.HOME,
) {
    val coroutineScope = rememberCoroutineScope()
    val isHome = page == SettingsPage.HOME
    val toolsPrefs = remember { Prefs.localAgentPreferences() }
    val capabilities = rememberDeviceCapabilities()
    val runtimeSnapshot by SettingsRuntimeSnapshotStore.state.collectAsState()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }
    var savingPowerTarget by remember { mutableStateOf(false) }
    var showSoftRebootDialog by remember { mutableStateOf(false) }
    var requestingSoftReboot by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember { mutableStateOf(runtimeSnapshot.overlayGranted) }
    var assistantActive by remember { mutableStateOf(runtimeSnapshot.assistantActive) }
    var accessibilityHealth by remember { mutableStateOf(runtimeSnapshot.accessibilityHealth) }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(runtimeSnapshot.accessibilityProtectionEnabled)
    }
    val accessibilityItem = accessibilityPermissionHealthItem(context, accessibilityHealth)
    LaunchedEffect(runtimeSnapshot) {
        overlayGranted = runtimeSnapshot.overlayGranted
        assistantActive = runtimeSnapshot.assistantActive
        accessibilityHealth = runtimeSnapshot.accessibilityHealth
        accessibilityProtectionEnabled = runtimeSnapshot.accessibilityProtectionEnabled
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val roleOpened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    context.startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    true
                } else false
            }.getOrDefault(false)
        } else false
        val opened = roleOpened || listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
            .any { action -> runCatching { context.startActivity(Intent(action)); true }.getOrDefault(false) }
        if (!opened) Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
    }

    // 关于组：版本信息与更新检查。结果对话框在列表外渲染，状态需要页面级 owner。
    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionSummary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val openQqGroup: () -> Unit = {
        val qqIntent = Intent(
            Intent.ACTION_VIEW,
            qqGroupInviteUri("1107693849"),
        )
        val failed = runCatching { context.startActivity(qqIntent) }.isFailure
        if (failed) {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.ui_about_join_qq_group), "1107693849"))
            Toast.makeText(context, context.getString(R.string.ui_about_qq_group_copied, "1107693849"), Toast.LENGTH_LONG).show()
        }
    }
    val checkForUpdate: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            coroutineScope.launch {
                val release = runCatching {
                    withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatest() }
                }.getOrNull()
                checkingUpdate = false
                when {
                    release == null -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_check_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    AppUpdateChecker.isNewer(release.version, appVersionName) -> availableUpdate = release
                    else -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_already_latest),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    // Provider / Model 选中状态展示
    val providerState by remember(page) {
        if (isHome) providerSettingsLoadState(ProviderRepository.providersFlow())
        else flowOf(ProviderSettingsLoadState.Loading)
    }
        .collectAsState(initial = if (isHome) cachedProviderSettingsLoadState() else ProviderSettingsLoadState.Loading)
    val providers = (providerState as? ProviderSettingsLoadState.Loaded)?.providers.orEmpty()
    val selectedProviderId by remember(page) {
        if (isHome) RuntimeConfigRepository.selectedProviderIdFlow() else flowOf(null)
    }
        .collectAsState(initial = null)
    val selectedModelId by remember(page) {
        if (isHome) RuntimeConfigRepository.selectedModelIdFlow() else flowOf(null)
    }
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = when (providerState) {
        ProviderSettingsLoadState.Loading -> stringResource(R.string.settings_provider_loading)
        ProviderSettingsLoadState.Failed -> stringResource(R.string.settings_provider_load_failed)
        is ProviderSettingsLoadState.Loaded -> selectedProvider?.let { provider ->
            "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
        } ?: stringResource(R.string.settings_not_configured)
    }

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.cachedRemotePreferencesForUi(EtaApp.serviceInstance)) }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        var observedService = false
        var lastService: io.github.libxposed.service.XposedService? = null
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                if (observedService && lastService === service) return
                observedService = true
                lastService = service
                coroutineScope.launch {
                    val connected = withContext(Dispatchers.IO) {
                        Prefs.remotePreferencesForUi(service)?.also { remote ->
                            enhancementHistory.captureConnected(remote)
                            Prefs.reconcileAgentPreferences(remote)
                        }
                    }
                    if (EtaApp.serviceInstance !== service) return@launch
                    prefs = connected
                    if (connected != null) hasConnectedFramework = true
                    withContext(Dispatchers.IO) {
                        RuntimeConfigRepository.ensureDefaults(service)
                    }
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = remember(powerAssistantTargets, context) {
        powerAssistantTargets.map { target -> target.displayName(context) }
    }

    val homeList = rememberLazyListState()
    var assistantTab by rememberSaveable { mutableIntStateOf(0) }
    val defaultAssistantList = rememberLazyListState()
    val takeoverList = rememberLazyListState()
    val voiceAssistantList = rememberLazyListState()
    val gesturesList = rememberLazyListState()
    val isAssistant = page == SettingsPage.ASSISTANT
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val searchingSettings = isHome && settingsQuery.isNotBlank()
    val settingsSearchEntries = if (isHome) listOf(
        SettingsSearchEntry(stringResource(R.string.ui_model_provider_e8c7f5), "模型 换模型 提供商 接口 连接 model provider api", AppRoute.ModelProviders),
        SettingsSearchEntry("本地模型 / Local models", "本地 离线 推理 local model", AppRoute.LocalModels),
        SettingsSearchEntry(stringResource(R.string.route_memory), "记忆 自学习 memory learning", AppRoute.Memory),
        SettingsSearchEntry("角色与指令 / Characters", "角色 提示词 character prompt", AppRoute.Characters),
        SettingsSearchEntry("Subagent", "子智能体 智能体 agent", AppRoute.Subagents),
        SettingsSearchEntry(stringResource(R.string.route_task_center), "自动任务 定时 触发 workflow automation task", AppRoute.TaskCenter),
        SettingsSearchEntry(stringResource(R.string.route_agent_metrics), "统计 费用 token 用量 statistics", AppRoute.AgentMetrics),
        SettingsSearchEntry("能力中心 / Capabilities", "能力 插件 连接 capabilities", AppRoute.CapabilityCenter),
        SettingsSearchEntry(stringResource(R.string.route_skills), "技能 skill", AppRoute.Skills),
        SettingsSearchEntry(stringResource(R.string.route_mcp_servers), "mcp 服务 工具 server", AppRoute.McpServers),
        SettingsSearchEntry("API 连接 / API connectors", "api 连接 接口 oauth connector", AppRoute.ApiConnectors),
        SettingsSearchEntry(stringResource(R.string.route_a2a_agents), "a2a 远程 智能体 remote agent", AppRoute.A2AAgents),
        SettingsSearchEntry(stringResource(R.string.settings_tools_devices_title), "工具 设备 软重启 root reboot tools device", AppRoute.ToolsAndDevices),
        SettingsSearchEntry(stringResource(R.string.route_tools), "工具 tools", AppRoute.Tools),
        SettingsSearchEntry("云端工具", "云 OCR 文档 表格 翻译 生图 视频 向量 cloud", AppRoute.CloudToolsSettings),
        SettingsSearchEntry(stringResource(R.string.web_tools_settings_title), "云端工具 网页 搜索 读取 抓取 search fetch web", AppRoute.WebToolsSettings),
        SettingsSearchEntry(stringResource(R.string.route_linux_environment), "linux 终端 环境 rootfs", AppRoute.LinuxEnvironment),
        SettingsSearchEntry(stringResource(R.string.route_permissions), "权限 授权 permission", AppRoute.Permissions),
        SettingsSearchEntry(stringResource(R.string.capability_enhancements), "系统 增强 root system", AppRoute.SystemEnhance),
        SettingsSearchEntry(stringResource(R.string.settings_digital_assistant_title), "默认 助手 唤醒 语音唤醒 assistant", AppRoute.DigitalAssistantSettings),
        SettingsSearchEntry(stringResource(R.string.settings_search_gestures_title), "手势 圈选 gestures", AppRoute.SearchGestureSettings),
        SettingsSearchEntry(stringResource(R.string.meeting_settings_title), "会议 协作 meeting", AppRoute.MeetingSettings),
        SettingsSearchEntry(stringResource(R.string.appearance_title), "外观 主题 皮肤 玻璃 字体 排版 颜色 效果 手势 appearance theme glass font", AppRoute.AppearanceSettings),
        SettingsSearchEntry(stringResource(R.string.settings_language), "语言 中文 英文 language locale", AppRoute.AppearanceSettings),
        SettingsSearchEntry(stringResource(R.string.speech_settings_title), "语音 识别 合成 speech voice", AppRoute.SpeechSettings),
        SettingsSearchEntry("项目与文件", "项目 文件 工作区 project workspace files", AppRoute.Projects),
        SettingsSearchEntry(stringResource(R.string.data_backup_title), "备份 恢复 自动 backup restore", AppRoute.DataBackup),
        SettingsSearchEntry(stringResource(R.string.conversation_archive_settings_title), "会话 会议室 归档 时间线 archive meeting", AppRoute.ConversationArchiveSettings),
    ) else emptyList()
    val matchingSettings = remember(settingsSearchEntries, settingsQuery) {
        val searchTerms = settingsQuery.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        settingsSearchEntries.filter { entry ->
            searchTerms.all { term ->
                entry.title.contains(term, ignoreCase = true) || entry.keywords.contains(term, ignoreCase = true)
            }
        }
    }
    if (showAbout) {
        AboutRewardScreen(
            version = appVersionSummary,
            checkingUpdate = checkingUpdate,
            onCheckUpdate = checkForUpdate,
            onFeedback = { openUrl("https://github.com/147258369zhh-lgtm/NEW-NEXUS/issues") },
            onQqGroup = openQqGroup,
            onBack = { showAbout = false },
        )
        availableUpdate?.let { update ->
            WindowDialog(
                show = true,
                title = stringResource(R.string.ui_update_available_title),
                summary = stringResource(R.string.ui_update_available_message, update.version, appVersionName),
                onDismissRequest = { availableUpdate = null },
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.ui_update_go_download),
                    onCancel = { availableUpdate = null },
                    onConfirm = { availableUpdate = null; openUrl(update.url) },
                )
            }
        }
        return
    }
    MiuixScaffoldPage(
        title = stringResource(when (page) {
            SettingsPage.HOME -> R.string.ui_set_up_7debf9
            SettingsPage.ASSISTANT -> R.string.settings_digital_assistant_title
            SettingsPage.GESTURES -> R.string.settings_search_gestures_title
            SettingsPage.SYSTEM_TOOLS -> R.string.settings_tools_devices_title
        }),
        listState = when (page) {
            SettingsPage.HOME -> homeList
            SettingsPage.ASSISTANT -> listOf(defaultAssistantList, takeoverList, voiceAssistantList)[assistantTab]
            SettingsPage.GESTURES -> gesturesList
            SettingsPage.SYSTEM_TOOLS -> homeList
        },
        aboveContent = {
            if (isHome) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                InputField(
                    query = settingsQuery,
                    onQueryChange = { settingsQuery = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.settings_find_label),
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (isAssistant) NexusSettingsTabs(
                listOf(stringResource(R.string.settings_assistant_default),
                    stringResource(R.string.settings_assistant_takeover),
                    stringResource(R.string.settings_assistant_voice)),
                assistantTab, { assistantTab = it },
            )
        },
        onBack = onBack,
    ) {
            if (searchingSettings) {
                if (matchingSettings.isEmpty()) item(key = "settings_search_empty") {
                    Text(stringResource(R.string.settings_find_empty), modifier = Modifier.padding(24.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                matchingSettings.forEach { entry -> item(key = "settings_search:${entry.route}") {
                    val group = when (entry.route) {
                        AppRoute.ModelProviders -> "模型与推理"
                        AppRoute.Memory, AppRoute.Characters, AppRoute.Subagents -> "记忆与智能体"
                        AppRoute.TaskCenter, AppRoute.AgentMetrics, AppRoute.CapabilityCenter -> "自动化与能力"
                        AppRoute.Skills, AppRoute.McpServers, AppRoute.ApiConnectors, AppRoute.A2AAgents -> "自动化与能力 / 能力中心"
                        AppRoute.ToolsAndDevices -> "工具与设备"
                        AppRoute.Tools, AppRoute.LinuxEnvironment, AppRoute.Permissions -> stringResource(R.string.settings_tools_devices_title)
                        AppRoute.LocalModels -> "模型与推理"
                        AppRoute.SystemEnhance, AppRoute.DigitalAssistantSettings, AppRoute.SearchGestureSettings -> stringResource(R.string.settings_tools_devices_title)
                        AppRoute.AppearanceSettings, AppRoute.SpeechSettings -> "语音与外观"
                        AppRoute.MeetingSettings -> "会议室 / 会议设置"
                        else -> "项目与数据"
                    }
                    ArrowPreference(title = entry.title, summary = group, onClick = { onNavigate(entry.route) })
                } }
            }
            // ── LLM 提供商 ──────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_agent") {
                SettingsSectionHeader("模型与推理")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Cloud,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )
                    ArrowPreference(
                        title = "本地模型",
                        startAction = { SettingsIcon(Icons.Rounded.Smartphone, tint = SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.LocalModels) },
                    )
                }
            }

            // ── 记忆与智能体 ────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_agent_core") {
                SettingsSectionHeader("记忆与智能体")
                SettingsGroupCard {
                    ArrowPreference(
                        title = "记忆与自学习",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                    ArrowPreference(
                        title = "角色与指令",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Description,
                                tint = SettingsIconColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Characters) },
                    )
                    ArrowPreference(
                        title = "Subagent",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Hub,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Subagents) },
                    )
                }
            }

            // ── 自动化与能力 ────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_automation_capability") {
                SettingsSectionHeader("自动化与能力")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.route_task_center),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Schedule,
                                tint = SettingsIconColors.Automation,
                            )
                        },
                        onClick = { onNavigate(AppRoute.TaskCenter) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.route_agent_metrics),
                        startAction = {
                            SettingsIcon(Icons.Rounded.Dashboard, tint = SettingsIconColors.Data)
                        },
                        onClick = { onNavigate(AppRoute.AgentMetrics) },
                    )
                    ArrowPreference(
                        title = "能力中心",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Inventory2,
                                tint = SettingsIconColors.Tool,
                            )
                        },
                        onClick = { onNavigate(AppRoute.CapabilityCenter) },
                    )
                }
            }

            // ── 工具与设备 ─────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_tools") {
                SettingsSectionHeader(stringResource(R.string.settings_tools_devices_title))
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tools_devices_title),
                        startAction = { SettingsIcon(Icons.Rounded.Build, tint = SettingsIconColors.Tool) },
                        onClick = { onNavigate(AppRoute.ToolsAndDevices) },
                    )
                }
            }

            if (isAssistant && assistantTab == 0) item(key = "assistant_default") {
                SettingsSectionHeader(stringResource(R.string.settings_assistant_default))
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        summary = if (assistantActive) "已设为系统默认助手" else "未设为系统默认助手；唤醒词不会生效",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.SupportAgent,
                                tint = SettingsIconColors.Green,
                            )
                        },
                        onClick = openAssistantSettings,
                    )

                    OverlayDropdownPreference(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        items = powerAssistantItems,
                        selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                        onSelectedIndexChange = { index ->
                            val target = powerAssistantTargets.getOrNull(index)
                            ?: return@OverlayDropdownPreference
                            val targetPrefs = prefs ?: return@OverlayDropdownPreference
                            if (savingPowerTarget) return@OverlayDropdownPreference
                            savingPowerTarget = true
                            coroutineScope.launch {
                            try {
                            if (withContext(Dispatchers.IO) { putStringSync(
                                    prefs = targetPrefs,
                                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                    value = target.persistedValue,
                                ) }
                            ) {
                                powerAssistantTarget = target
                                enhancementHistory.recordCommittedTarget(target)
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            } finally { savingPowerTarget = false }
                            }
                        },
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.PowerSettingsNew,
                                tint = SettingsIconColors.Yellow,
                                enabled = prefs != null,
                            )
                        },
                        enabled = prefs != null && !savingPowerTarget,
                    )
                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = Icons.Rounded.Settings,
                        iconTint = SettingsIconColors.Green,
                    )

                }
                if (prefs == null) {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            summary = stringResource(R.string.settings_framework_required),
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }
            }

            if (isAssistant && assistantTab == 1) item(key = "assistant_takeover") {
                SettingsSectionHeader(stringResource(R.string.settings_assistant_takeover))
                SettingsGroupCard {

                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = Icons.Rounded.Cloud,
                        iconTint = SettingsIconColors.Blue,
                    )
                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = Icons.Rounded.FilterAlt,
                        iconTint = SettingsIconColors.Blue,
                    )

                }
                if (prefs == null) {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            summary = stringResource(R.string.settings_framework_required),
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }
            }

            if (isAssistant && assistantTab == 2) item(key = "assistant_voice") {
                SettingsSectionHeader(stringResource(R.string.settings_assistant_voice))
                SettingsGroupCard {

                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                        icon = Icons.Rounded.Hearing,
                        iconTint = SettingsIconColors.Green,
                    )
                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                        icon = Icons.Rounded.Lock,
                        iconTint = SettingsIconColors.Blue,
                    )
                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                        key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                        icon = Icons.Rounded.SettingsVoice,
                        iconTint = SettingsIconColors.Green,
                    )

                    if (capabilities.root.isGranted || hasUsedSystemizer) {
                        ArrowPreference(
                            title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.Inventory,
                                    tint = SettingsIconColors.Orange,
                                    enabled = !installingSystemizer,
                                )
                            },
                            summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                            enabled = !installingSystemizer,
                            holdDownState = showSystemizerDialog,
                            onClick = {
                                if (!capabilities.root.isGranted) {
                                    onNavigate(AppRoute.SystemEnhance)
                                } else if (!installingSystemizer) {
                                    showSystemizerDialog = true
                                }
                            },
                        )
                    }

                }
                if (prefs == null) {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            summary = stringResource(R.string.settings_framework_required),
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }
            }

            if (page == SettingsPage.GESTURES) item(key = "search_gestures") {
                SettingsSectionHeader(stringResource(R.string.settings_search_gestures_title))
                SettingsGroupCard {

                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.SwipeUp,
                        iconTint = SettingsIconColors.Blue,
                    )
                    SwitchPref(
                        scope = coroutineScope,
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.TouchApp,
                        iconTint = SettingsIconColors.Blue,
                    )

                }
                if (prefs == null) {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            summary = stringResource(R.string.settings_framework_required),
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }
            }


            // ── 语音与外观 ──────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_general") {
                SettingsSectionHeader("语音与外观")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Palette,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.speech_settings_title),
                        startAction = { SettingsIcon(icon = Icons.Rounded.Mic, tint = SettingsIconColors.Blue) },
                        onClick = { onNavigate(AppRoute.SpeechSettings) },
                    )
                }
            }

            // ── 项目与数据 ──────────────────────────────────────────────
            if (isHome && !searchingSettings) item(key = "section_project_data") {
                SettingsSectionHeader("项目与数据")
                SettingsGroupCard {
                    ArrowPreference(
                        title = "项目与文件",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.FolderSpecial,
                                tint = SettingsIconColors.Data,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Projects) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Description,
                                tint = SettingsIconColors.Data,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.conversation_archive_settings_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Archive,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ConversationArchiveSettings) },
                    )
                }
            }

            if (page == SettingsPage.SYSTEM_TOOLS) {
        item(key = "linux_first") {
            SettingsSectionHeader(stringResource(R.string.system_tools_linux))
            SettingsGroupCard {
                ArrowPreference(title = stringResource(R.string.route_linux_environment),
                    startAction = { SettingsIcon(Icons.Rounded.Terminal, SettingsIconColors.Orange) },
                    onClick = { onNavigate(AppRoute.LinuxEnvironment) })
                ArrowPreference(title = stringResource(R.string.system_tools_terminal),
                    startAction = { SettingsIcon(Icons.Rounded.Code, SettingsIconColors.Blue) },
                    onClick = { onNavigate(AppRoute.Terminal) })
            }
        }
        item(key = "access") {
            SettingsSectionHeader(stringResource(R.string.settings_tool_access_title))
                ToolAccessPreferences(
                    scope = coroutineScope,
                    context = context,
                    prefs = toolsPrefs,
                    items = listOf(
                        ToolToggleSpec(stringResource(R.string.system_tools_browser), Prefs.Keys.AGENT_BROWSER_TOOLS, Icons.Rounded.Language, SettingsIconColors.Blue),
                        ToolToggleSpec(
                            stringResource(R.string.settings_virtual_display_title),
                            Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                            Icons.Rounded.Layers,
                            SettingsIconColors.System,
                        ),
                        ToolToggleSpec(
                            stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                            Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                            Icons.Rounded.Smartphone,
                            SettingsIconColors.System,
                        ),
                        ToolToggleSpec(
                            stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                            Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                            Icons.Rounded.Visibility,
                            SettingsIconColors.Blue,
                        ),
                        ToolToggleSpec(
                            stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                            Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                            Icons.Rounded.GppMaybe,
                            SettingsIconColors.Automation,
                        ),
                        ToolToggleSpec(
                            stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                            Prefs.Keys.AGENT_TERMINAL_TOOLS,
                            Icons.Rounded.Terminal,
                            SettingsIconColors.Tool,
                        ),
                    ),
                )
                SettingsGroupCard {
                    SwitchPreference(
                        title = stringResource(R.string.nexa_soft_reboot),
                        summary = stringResource(R.string.nexa_soft_reboot_summary),
                        checked = requestingSoftReboot,
                        enabled = !requestingSoftReboot,
                        startAction = { SettingsIcon(Icons.Rounded.PowerSettingsNew, SettingsIconColors.Orange) },
                        onCheckedChange = { enabled -> if (enabled) showSoftRebootDialog = true },
                    )
                }
        }
        item(key = "services") {
            SettingsSectionHeader(stringResource(R.string.settings_tab_tool_services))
            SettingsGroupCard {
                ArrowPreference(title = stringResource(R.string.settings_tools_list),
                    startAction = { SettingsIcon(Icons.Rounded.Build, SettingsIconColors.Tool) },
                    onClick = { onNavigate(AppRoute.Tools) })
                ArrowPreference(title = "云端工具",
                    startAction = { SettingsIcon(Icons.Rounded.Cloud, SettingsIconColors.Blue) },
                    onClick = { onNavigate(AppRoute.CloudToolsSettings) })
            }
        }
        item(key = "assistant_and_gestures") {
            SettingsSectionHeader(stringResource(R.string.settings_system_assistant_group))
            SettingsGroupCard {
                ArrowPreference(title = stringResource(R.string.settings_digital_assistant_title),
                    startAction = { SettingsIcon(Icons.Rounded.SupportAgent, SettingsIconColors.Green) },
                    onClick = { onNavigate(AppRoute.DigitalAssistantSettings) })
                ArrowPreference(title = stringResource(R.string.settings_search_gestures_title),
                    startAction = { SettingsIcon(Icons.Rounded.TouchApp, SettingsIconColors.Blue) },
                    onClick = { onNavigate(AppRoute.SearchGestureSettings) })
            }
        }
        item(key = "enhancement_and_permissions") {
            SettingsSectionHeader(stringResource(R.string.system_tools_access))
            SettingsGroupCard {
                ArrowPreference(title = stringResource(R.string.capability_enhancements),
                    startAction = { SettingsIcon(Icons.Rounded.Security, SettingsIconColors.System) },
                    onClick = { onNavigate(AppRoute.SystemEnhance) })
                ArrowPreference(title = stringResource(R.string.ui_permissions_and_status_35f368),
                    startAction = { SettingsIcon(Icons.Rounded.VerifiedUser, SettingsIconColors.Green) },
                    onClick = { onNavigate(AppRoute.Permissions) })
            }
        }
            }

            // ── 权限与运行状态 ──────────────────────────────────────────
            if (page == SettingsPage.SYSTEM_TOOLS) item(key = "section_permissions") {
                SettingsSectionHeader("权限与运行状态")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Layers,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        endActions = {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        summary = accessibilityItem.summary,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.AccessibilityNew,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        endActions = {
                            Text(
                                text = accessibilityItem.status.label(),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = accessibilityItem.status.color(),
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                    if (AccessibilityProtectionClient.isSupported() && (prefs != null || hasConnectedFramework)) {
                        SwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = { enabled ->
                                if (accessibilityProtectionPending) {
                                    return@SwitchPreference
                                }
                                accessibilityProtectionPending = true
                                AccessibilityProtectionClient.setEnabled(
                                    context = context,
                                    enabled = enabled,
                                ) { result ->
                                    accessibilityProtectionPending = false
                                    accessibilityProtectionEnabled = result.enabled
                                    coroutineScope.launch { SettingsRuntimeSnapshotStore.refresh(context) }
                                    val failureMessage = when (result.status) {
                                        AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                        AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                            context.getString(R.string.accessibility_protection_unavailable)
                                        AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                            context.getString(R.string.accessibility_protection_rejected)
                                    }
                                    if (failureMessage != null) {
                                        Toast.makeText(
                                            context.applicationContext,
                                            failureMessage,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = SettingsIconColors.Blue,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                if (isHome && !searchingSettings) item(key = "section_debug_diagnostics") {
                    SettingsSectionHeader("调试诊断")
                    DebugDiagnosticsCard()
                }
            }

            // 关于入口集中展示，详情页使用独立的单层滚动列表。
            if (isHome && !searchingSettings) item(key = "section_about") {
                SettingsSectionHeader(stringResource(R.string.ui_about_bed172))
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.ui_about_bed172),
                        summary = appVersionSummary,
                        startAction = { SettingsIcon(Icons.Rounded.Info, SettingsIconColors.Blue) },
                        onClick = { showAbout = true },
                    )
                }
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                if (!capabilities.root.isGranted) {
                    showSystemizerDialog = false
                    onNavigate(AppRoute.SystemEnhance)
                    return@SystemizerConfirmDialog
                }
                enhancementHistory.recordSystemizerUse()
                hasUsedSystemizer = true
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )

        WindowDialog(
            show = showSoftRebootDialog,
            title = stringResource(R.string.nexa_soft_reboot),
            summary = stringResource(R.string.nexa_soft_reboot_confirm),
            onDismissRequest = { showSoftRebootDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.nexa_soft_reboot),
                onCancel = { showSoftRebootDialog = false },
                onConfirm = {
                    if (!requestingSoftReboot) {
                        showSoftRebootDialog = false
                        requestingSoftReboot = true
                        coroutineScope.launch {
                            try {
                                val message = when (NexaSoftReboot.request(context.applicationContext)) {
                                    NexaSoftRebootResult.REQUESTED -> R.string.nexa_soft_reboot_requested
                                    NexaSoftRebootResult.ROOT_REQUIRED -> R.string.nexa_soft_reboot_root_required
                                    NexaSoftRebootResult.UNSUPPORTED -> R.string.nexa_soft_reboot_unsupported
                                    NexaSoftRebootResult.BUSY -> R.string.nexa_soft_reboot_busy
                                    NexaSoftRebootResult.UNCONFIRMED -> R.string.nexa_soft_reboot_unconfirmed
                                }
                                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                            } finally {
                                requestingSoftReboot = false
                            }
                        }
                    }
                },
            )
        }

        availableUpdate?.let { update ->
            WindowDialog(
                show = true,
                title = stringResource(R.string.ui_update_available_title),
                summary = stringResource(R.string.ui_update_available_message, update.version, appVersionName),
                onDismissRequest = { availableUpdate = null },
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.ui_update_go_download),
                    onCancel = { availableUpdate = null },
                    onConfirm = {
                        availableUpdate = null
                        openUrl(update.url)
                    },
                )
            }
        }
}


@Composable
internal fun ToolsAndDevicesSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) =
    SettingsPageContent(LocalContext.current, onNavigate, onBack, SettingsPage.SYSTEM_TOOLS)

@Composable
private fun DebugDiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by RuntimeDebugTrace.snapshot.collectAsState()
    var runtimeQuery by remember { mutableStateOf("查询中…") }
    val client = remember(context.applicationContext) {
        AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
    }

    fun refreshRuntime() {
        runtimeQuery = "查询中…"
        scope.launch {
            val query = withContext(Dispatchers.IO) { client.queryActiveRuns() }
            runtimeQuery = when (query) {
                is AgentRuntimeClient.ActiveRunsQuery.Known -> {
                    RuntimeDebugTrace.activeRuntimeRuns(query.runs.size)
                    "${query.runs.size} 个运行中"
                }
                AgentRuntimeClient.ActiveRunsQuery.Unavailable -> {
                    RuntimeDebugTrace.activeRuntimeRuns(0)
                    "Runtime 不可用"
                }
            }
        }
    }

    LaunchedEffect(client) { refreshRuntime() }
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = if (snapshot.serviceConnected) "Runtime 已连接" else "Runtime 未连接/空闲",
                style = MiuixTheme.textStyles.body1,
            )
            Text(
                text = "运行中：$runtimeQuery · 会议：${snapshot.activeMeetings} · 后台执行：${snapshot.backgroundRunCount} · 超时：${snapshot.timeoutCount}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Agent 累计：工具 ${snapshot.agentToolCallCount} 次（失败 ${snapshot.agentFailedToolCount}）· 输入 ${snapshot.agentInputTokens} · 输出 ${snapshot.agentOutputTokens} tokens",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "连接 ${snapshot.connectionCount} · 绑定失败 ${snapshot.bindingFailureCount}" +
                    " · 任务失败 ${snapshot.taskFailureCount}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            snapshot.recentEvents.take(6).forEach { event ->
                Text(
                    text = "${RuntimeDebugTrace.formatTime(event.at)}  ${event.type}  ${event.detail}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TextButton(text = "刷新运行状态", onClick = ::refreshRuntime)
        }
    }
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (installing) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !installing,
            confirmEnabled = !installing,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

private data class ToolToggleSpec(
    val title: String,
    val key: String,
    val icon: ImageVector,
    val accent: Color,
)

@Composable
private fun ToolAccessPreferences(
    scope: CoroutineScope,
    context: Context,
    prefs: SharedPreferences?,
    items: List<ToolToggleSpec>,
) {
    SettingsGroupCard {
        items.forEach { item ->
            ToolAccessSwitch(scope = scope, context = context, prefs = prefs, item = item, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToolAccessSwitch(
    scope: CoroutineScope,
    context: Context,
    prefs: SharedPreferences?,
    item: ToolToggleSpec,
    modifier: Modifier = Modifier,
) {
    var saving by remember(item.key) { mutableStateOf(false) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[item.key] ?: true
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var checked by remember(prefs, item.key) {
        mutableStateOf(prefs?.getBoolean(item.key, default) ?: history.checked(item.key, default))
    }
    DisposableEffect(prefs, item.key) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == item.key) checked = changed.getBoolean(item.key, default)
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun toggle() {
        if (prefs == null || saving) return
        val next = !checked
        saving = true
        scope.launch {
            try {
                io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(context).setRuntimePreferenceFromUi(item.key, next)
                checked = next
                history.recordCommittedBoolean(item.key, next)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show() }
            finally { saving = false }
        }
    }
    SwitchPreference(
        title = item.title,
        checked = checked,
        enabled = prefs != null && !saving,
        onCheckedChange = { toggle() },
        startAction = { SettingsIcon(item.icon, tint = item.accent, enabled = prefs != null) },
        modifier = modifier,
    )
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
@Composable
private fun SwitchPref(
    scope: CoroutineScope,
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
) {
    var saving by remember(key) { mutableStateOf(false) }
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                if (saving) return@SwitchPreference
                saving = true
                scope.launch {
                    try {
                        io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(context).setRuntimePreferenceFromUi(key, value)
                        checked = value
                        history.recordCommittedBoolean(key, value)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show() }
                    finally { saving = false }
                }
                return@SwitchPreference
            }
            // Await persistence off main; only acknowledge a successful Binder commit.
            val targetPrefs = prefs ?: return@SwitchPreference
            if (saving) return@SwitchPreference
            saving = true
            scope.launch {
            try {
            if (withContext(Dispatchers.IO) { putBooleanSync(targetPrefs, key, value) }) {
                checked = value
                history.recordCommittedBoolean(key, value)
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            } finally { saving = false }
            }
        },
        startAction = {
            SettingsIcon(icon = icon, enabled = enabled, tint = iconTint)
        },
        enabled = enabled && !saving,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching {
        prefs.edit().putString(key, value).apply {
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET) {
                // Re-selecting the same target is still an explicit request. Persist
                // its id with the target so a delayed callback cannot apply an old choice.
                putString(
                    io.github.mangi.eta.hook.system.AssistantManager.SELECTION_REQUEST_KEY,
                    "${android.os.Process.myUserHandle().hashCode()}:${java.util.UUID.randomUUID()}",
                )
            }
        }.commit()
    }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "NEXA"
    }

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
