package io.github.mangi.eta.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onConversationPaneModeChange: (showMeetings: Boolean, meetingRoomId: String?) -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranches: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationArchive: (ConversationSummaryUi) -> Unit,
    onConversationRestore: (ConversationSummaryUi) -> Unit,
    onConversationMoveToFolder: (ConversationSummaryUi) -> Unit,
    onConversationDropToFolder: (conversationId: String, folderId: String) -> Unit = { _, _ -> },
    onOpenMeetingRoom: (String) -> Unit,
    onMeetingRoomRename: (MeetingRoomSummaryRow) -> Unit,
    onMeetingRoomTogglePin: (MeetingRoomSummaryRow) -> Unit,
    onMeetingRoomExport: (MeetingRoomSummaryRow) -> Unit,
    onMeetingRoomDelete: (MeetingRoomSummaryRow) -> Unit,
    onMeetingRoomArchiveChange: (MeetingRoomSummaryRow, Boolean) -> Unit,
    onCreateFolder: () -> Unit,
    onRenameFolder: (ConversationFolderUi) -> Unit,
    onArchiveViewChange: (Boolean) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppControl: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    homeTitle: String? = null,
    showMeetingsByDefault: Boolean = false,
    activeMeetingRoomId: String? = null,
    meetingArchiveViewOpen: Boolean = false,
    onMeetingArchiveViewChange: (Boolean) -> Unit = {},
    homeCharacter: RoleplayChatUi? = null,
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        Box {
                            AgentTopBar(
                                route = currentRoute,
                                scrollBehavior = scrollBehavior,
                                color = topBarColor,
                                onBack = onBack,
                                onOpenConversationPane = onOpenConversationPane,
                                onOpenTerminal = onOpenTerminal,
                                onLaunchKimiWeb = onLaunchKimiWeb,
                                kimiWebLabel = kimiWebLabel,
                                canStopKimiWeb = canStopKimiWeb,
                                onStopKimiWeb = onStopKimiWeb,
                                onRefreshKimiWeb = onRefreshKimiWeb,
                                onOpenBrowser = onOpenBrowser,
                                onOpenBranches = onOpenBranches,
                                homeTitle = homeTitle,
                                homeCharacter = homeCharacter,
                                onOpenCharacter = onOpenCharacter,
                                onOpenAppControl = onOpenAppControl,
                            )
                            if (currentRoute is AppRoute.Home) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    HomeModeSwitch(
                                        showMeetings = showMeetingsByDefault,
                                        onSelect = { onConversationPaneModeChange(it, null) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                content(padding)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onConversationArchive = onConversationArchive,
                onConversationRestore = onConversationRestore,
                onConversationMoveToFolder = onConversationMoveToFolder,
                onConversationDropToFolder = onConversationDropToFolder,
                onOpenMeetingRoom = onOpenMeetingRoom,
                onMeetingRoomRename = onMeetingRoomRename,
                onMeetingRoomTogglePin = onMeetingRoomTogglePin,
                onMeetingRoomExport = onMeetingRoomExport,
                onMeetingRoomDelete = onMeetingRoomDelete,
                onMeetingRoomArchiveChange = onMeetingRoomArchiveChange,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onArchiveViewChange = onArchiveViewChange,
                onPaneModeChange = onConversationPaneModeChange,
                meetingArchiveViewOpen = meetingArchiveViewOpen,
                onMeetingArchiveViewChange = onMeetingArchiveViewChange,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
                showMeetingsByDefault = showMeetingsByDefault,
                activeMeetingRoomId = activeMeetingRoomId,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranches: () -> Unit,
    homeTitle: String?,
    homeCharacter: RoleplayChatUi?,
    onOpenCharacter: () -> Unit,
    onOpenAppControl: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenConversationPane) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ShortText,
                        modifier = Modifier.size(24.dp),
                        contentDescription = stringResource(R.string.action_conversation_history),
                    )
                }
                // 角色对话的身份标识放在顶栏左侧，点按进入角色详情；普通对话保持空标题。
                homeCharacter?.let { character ->
                    CharacterTopBarIdentity(character = character, onClick = onOpenCharacter)
                }
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            TopBarOverflowMenu(
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                onOpenBranches = onOpenBranches,
                onOpenAppControl = onOpenAppControl,
            )
        }
    }

    // 首页聊天舞台与终端这类整屏工具页没有可驱动折叠的滚动列表，大标题会一直展开、
    // 白占一屏顶部；它们使用紧凑标题，其余二级内容页统一使用可折叠大标题。
    val compactTitle = isHome || route is AppRoute.Terminal
    val topBarTitle = if (isHome) homeTitle.orEmpty() else titleForRoute(route)
    if (compactTitle) {
        SmallTopAppBar(
            title = topBarTitle,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = topBarTitle,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

/**
 * 首页主舞台的模式切换固定在顶栏操作区，与右侧溢出菜单共用同一条垂直中心线。
 * 控件保持小块玻璃胶囊形态，不占用聊天正文高度。
 */
@Composable
private fun HomeModeSwitch(
    showMeetings: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .width(126.dp)
            .height(32.dp)
            .squircleSurface(colors.surfaceContainerHigh.copy(alpha = .98f), 16.dp)
            .border(.5.dp, colors.outline.copy(alpha = .20f), CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeModeSwitchItem(
            label = "会话",
            selected = !showMeetings,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
        HomeModeSwitchItem(
            label = "会议室",
            selected = showMeetings,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeModeSwitchItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .squircleSurface(
                if (selected) colors.tertiaryContainer else Color.Transparent,
                12.dp,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onTertiaryContainer else colors.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenAppControl: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val appControlText = "软件管家"
            val launchKimiWebText = kimiWebLabel
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            val branchesText = stringResource(R.string.branch_graph_title)
            val autoReadText = stringResource(
                if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
            )
            val menuItems = remember(
                openTerminalText,
                appControlText,
                launchKimiWebText,
                openBrowserText,
                branchesText,
                stopKimiWebText,
                canStopKimiWeb,
                autoReadText,
                hasSpeechPlayback,
                autoReadEnabled,
            ) {
                listOf(
                    DropdownItem(
                        text = appControlText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.SupportAgent,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = launchKimiWebText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(R.drawable.ic_kimi_code),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(text = branchesText),
                ) + (if (canStopKimiWeb) {
                    listOf(
                        DropdownItem(
                            text = stopKimiWebText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else {
                    emptyList()
                }) + if (hasSpeechPlayback) {
                    listOf(
                        DropdownItem(
                            text = autoReadText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp
                                        else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else emptyList()
            }
            ListPopupColumn {
                menuItems.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            when {
                                index == 0 -> onOpenAppControl()
                                index == 1 -> onOpenTerminal()
                                index == 2 -> onLaunchKimiWeb()
                                index == 3 -> onOpenBrowser()
                                index == 4 -> onOpenBranches()
                                canStopKimiWeb && index == 5 -> onStopKimiWeb()
                                hasSpeechPlayback && index == menuItems.lastIndex -> speechSettings?.let { current ->
                                    scope.launch {
                                        SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home, is AppRoute.Browser -> ""
    is AppRoute.AppControlCenter -> "软件管家"
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.PromptInjection -> "角色与指令"
    is AppRoute.CapabilityCenter -> "能力中心"
    is AppRoute.ApiConnectors -> "REST API 连接"
    is AppRoute.Subagents -> "Subagent"
    is AppRoute.TaskCenter -> stringResource(R.string.route_task_center)
    is AppRoute.A2AAgents -> stringResource(R.string.route_a2a_agents)
    is AppRoute.SelfLearning -> stringResource(R.string.route_self_learning)
    is AppRoute.Characters -> "角色与指令"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.ConversationInsights -> "会话详情"
    is AppRoute.ConnectorCenter -> "统一连接中心"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.ConversationArchiveSettings -> stringResource(R.string.conversation_archive_settings_title)
    is AppRoute.MeetingRooms -> stringResource(R.string.meeting_rooms_title)
    is AppRoute.MeetingRoom -> stringResource(R.string.meeting_room_title)
    is AppRoute.MeetingSettings -> stringResource(R.string.meeting_settings_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.Projects -> stringResource(R.string.projects_title)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.LocalModels -> stringResource(R.string.local_models_title)
    is AppRoute.CommunityCatalog -> "从 models.dev 添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.AgentMetrics -> stringResource(R.string.route_agent_metrics)
    is AppRoute.ConversationBranches -> stringResource(R.string.branch_graph_title)
    is AppRoute.AgentMetricDetail -> stringResource(R.string.metrics_detail_title)
    is AppRoute.AgentTokenPricing -> stringResource(R.string.metrics_prices_title)
    is AppRoute.AgentTokenPriceEditor -> stringResource(R.string.metrics_prices_edit)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.McpCatalog -> stringResource(R.string.mcp_catalog_title)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}

@Composable
private fun CharacterTopBarIdentity(character: RoleplayChatUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 32.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
