package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityHealth
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
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
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.app.accessibilityPermissionHealthItem
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.components.color
import kotlinx.coroutines.flow.collect
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = EtaUiMetrics.pageHorizontal)
            .padding(bottom = EtaUiMetrics.groupGap)
            .etaGlassSurface(),
    ) {
        content()
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
) {
    EtaIconTile(
        icon = icon,
        modifier = Modifier.padding(end = 4.dp),
        accentColor = tint,
        enabled = enabled,
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.secondary),
                    ),
                ),
        )
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var assistantActive by remember {
        mutableStateOf(io.github.mangi.eta.agent.voice.EtaVoiceInteractionService.isDefaultAssistant(context))
    }
    var accessibilityHealth by remember {
        mutableStateOf(AgentAccessibilityHealth.snapshot(context))
    }
    val accessibilityItem = accessibilityPermissionHealthItem(context, accessibilityHealth)
    LaunchedEffect(context.applicationContext) {
        AgentAccessibilityHealth.observeChanges(context).collect {
            accessibilityHealth = AgentAccessibilityHealth.snapshot(context)
        }
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 关于组：版本信息与更新检查。结果对话框在列表外渲染，状态需要页面级 owner。
    val appPackageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val appVersionName = appPackageInfo.versionName.orEmpty()
    val appVersionSummary = "${appPackageInfo.versionName} (${appPackageInfo.longVersionCode})"
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    var showSupportDialog by remember { mutableStateOf(false) }
    val openQqGroup: () -> Unit = {
        val qqIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1107693849"),
        )
        val failed = runCatching { context.startActivity(qqIntent) }.isFailure
        if (failed) {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Nexus QQ 群", "1107693849"))
            Toast.makeText(context, "QQ 群号 1107693849 已复制", Toast.LENGTH_LONG).show()
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                assistantActive = io.github.mangi.eta.agent.voice.EtaVoiceInteractionService.isDefaultAssistant(context)
                accessibilityHealth = AgentAccessibilityHealth.snapshot(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(EtaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
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
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        target.displayName(context)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {
            // ── LLM 提供商 ──────────────────────────────────────────────
            item(key = "section_agent") {
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
                }
            }

            // ── 记忆与智能体 ────────────────────────────────────────────
            item(key = "section_agent_core") {
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
            item(key = "section_automation_capability") {
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
            item(key = "section_tools") {
                SettingsSectionHeader("工具与设备")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tools_list),
                        startAction = { SettingsIcon(Icons.Rounded.Build, tint = SettingsIconColors.Tool) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.web_tools_settings_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.TravelExplore,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Inventory2,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                }
                CompactToolsGrid(
                    context = context,
                    prefs = agentPrefs,
                    items = listOf(
                        ToolToggleSpec("网页浏览", Prefs.Keys.AGENT_BROWSER_TOOLS, Icons.Rounded.Language, SettingsIconColors.Blue),
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
            }

            // ── 系统助手接管 ──────────────────────────────────────────────
            item(key = "section_system_assistant") {
                SettingsSectionHeader("系统助手与手势")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        startAction = { SettingsIcon(Icons.Rounded.Security, tint = SettingsIconColors.System) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
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
                    if (prefs != null || hasConnectedFramework) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                            items = powerAssistantItems,
                            selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                            onSelectedIndexChange = { index ->
                                val target = powerAssistantTargets.getOrNull(index)
                                    ?: return@OverlayDropdownPreference
                                val targetPrefs = prefs ?: return@OverlayDropdownPreference
                                if (putStringSync(
                                        prefs = targetPrefs,
                                        key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                        value = target.persistedValue,
                                    )
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
                            },
                            startAction = {
                                SettingsIcon(
                                    icon = Icons.Rounded.PowerSettingsNew,
                                    tint = SettingsIconColors.Yellow,
                                    enabled = prefs != null,
                                )
                            },
                            enabled = prefs != null,
                        )
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                            key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                            icon = Icons.Rounded.Settings,
                            iconTint = SettingsIconColors.Green,
                        )
                    }
                    if (prefs != null || hasConnectedFramework) {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                            key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                            icon = Icons.Rounded.Cloud,
                            iconTint = SettingsIconColors.Blue,
                        )
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                            key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                            icon = Icons.Rounded.FilterAlt,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
                        if (prefs != null || hasConnectedFramework) {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                                key = Prefs.Keys.HOTWORD_SELF_HEAL,
                                icon = Icons.Rounded.Hearing,
                                iconTint = SettingsIconColors.Green,
                            )
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                                key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                                icon = Icons.Rounded.Lock,
                                iconTint = SettingsIconColors.Blue,
                            )
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                                key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                                icon = Icons.Rounded.SettingsVoice,
                                iconTint = SettingsIconColors.Green,
                            )
                        }
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
                    if (prefs != null || hasConnectedFramework) {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                            key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                            icon = Icons.Rounded.SwipeUp,
                            iconTint = SettingsIconColors.Blue,
                        )
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                            key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                            icon = Icons.Rounded.TouchApp,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                }
            }

            // ── 语音与外观 ──────────────────────────────────────────────
            item(key = "section_general") {
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
                    LanguagePreference(
                        iconTint = SettingsIconColors.Blue,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Language,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                    )
                }
            }

            // ── 项目与数据 ──────────────────────────────────────────────
            item(key = "section_project_data") {
                SettingsSectionHeader("项目与数据")
                SettingsGroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.projects_title),
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

            // ── 权限与运行状态 ──────────────────────────────────────────
            item(key = "section_permissions") {
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
                                    accessibilityHealth = AgentAccessibilityHealth.snapshot(context)
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
                item(key = "section_debug_diagnostics") {
                    SettingsSectionHeader("调试诊断")
                    DebugDiagnosticsCard()
                }
            }

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SettingsSectionHeader(stringResource(R.string.ui_about_bed172))
                SettingsGroupCard {
                    BasicComponent(
                        title = stringResource(R.string.ui_about_version_title),
                        summary = appVersionSummary,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Info,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_about_update_title),
                        summary = if (checkingUpdate) {
                            stringResource(R.string.ui_about_update_checking)
                        } else {
                            null
                        },
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.SystemUpdate,
                                tint = SettingsIconColors.Green,
                                enabled = !checkingUpdate,
                            )
                        },
                        enabled = !checkingUpdate,
                        onClick = checkForUpdate,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_about_feedback_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.BugReport,
                                tint = SettingsIconColors.Orange,
                            )
                        },
                        onClick = { openUrl("https://github.com/147258369zhh-lgtm/NEW-NEXUS/issues") },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_about_github_star_title),
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Code,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = { openUrl("https://github.com/147258369zhh-lgtm/NEW-NEXUS") },
                    )
                    ArrowPreference(
                        title = "加入 QQ 群",
                        summary = "群号：1107693849",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Forum,
                                tint = SettingsIconColors.Blue,
                            )
                        },
                        onClick = openQqGroup,
                    )
                    ArrowPreference(
                        title = "微信赞赏支持",
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Favorite,
                                tint = SettingsIconColors.Green,
                            )
                        },
                        onClick = { showSupportDialog = true },
                    )
                }
            }
        }

        WindowDialog(
            show = showSupportDialog,
            title = "微信赞赏支持",
            summary = "感谢支持 Nexus 持续开发",
            onDismissRequest = { showSupportDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.wechat_reward_qr),
                    contentDescription = "微信赞赏码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
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
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
private fun CompactToolsGrid(
    context: Context,
    prefs: SharedPreferences?,
    items: List<ToolToggleSpec>,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    CompactToolToggle(
                        context = context,
                        prefs = prefs,
                        item = item,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CompactToolToggle(
    context: Context,
    prefs: SharedPreferences?,
    item: ToolToggleSpec,
    modifier: Modifier = Modifier,
) {
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
        val target = prefs ?: return
        val next = !checked
        if (putBooleanSync(target, item.key, next)) {
            checked = next
            history.recordCommittedBoolean(item.key, next)
            Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
        } else {
            Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show()
        }
    }
    Card(
        modifier = modifier
            .etaGlassSurface(accent = item.accent, cornerRadius = 15.dp)
            .clickable(enabled = prefs != null) { toggle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EtaIconTile(
                    icon = item.icon,
                    accentColor = item.accent,
                    enabled = prefs != null,
                    size = 28.dp,
                    glyphSize = 15.dp,
                )
                Switch(
                    checked = checked,
                    enabled = prefs != null,
                    onCheckedChange = { toggle() },
                )
            }
            Text(
                text = item.title,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = if (prefs != null) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface,
                maxLines = 2,
            )
        }
    }
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
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
) {
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
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            SettingsIcon(icon = icon, enabled = enabled, tint = iconTint)
        },
        enabled = enabled,
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
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Nexus"
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
