package io.github.mangi.eta.ui.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.control.EtaAppControlEvent
import io.github.mangi.eta.agent.control.EtaAppControlEvents
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.model.ModelRoleBinding
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomEntity
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.ConversationArchiveSettingsScreen
import io.github.mangi.eta.ui.IncomingShareRequest
import io.github.mangi.eta.ui.MeetingSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.NexusSettingsTheme
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.components.ConversationFolderDialogs
import io.github.mangi.eta.ui.components.ConversationFolderDialogRequest
import io.github.mangi.eta.ui.components.ConversationInsightsPaneScaffold
import io.github.mangi.eta.ui.components.HomeSwipeGestureAction
import io.github.mangi.eta.ui.components.HomeSwipeGesturePolicy
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogProviderScreen
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserActivity
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.ui.screens.capability.CapabilityCenterScreen
import io.github.mangi.eta.ui.screens.capability.ApiConnectorsScreen
import io.github.mangi.eta.ui.screens.capability.ConnectorCenterScreen
import io.github.mangi.eta.ui.screens.control.AppControlScreen
import io.github.mangi.eta.ui.screens.control.EtaAppControlConfirmationHost
import io.github.mangi.eta.ui.screens.control.appControlRoute
import io.github.mangi.eta.ui.screens.characters.CharacterDetailScreen
import io.github.mangi.eta.ui.screens.characters.CharacterEditorScreen
import io.github.mangi.eta.ui.screens.characters.CharacterLibraryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterMemoryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterPersonaScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.conversations.ConversationInsightsScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.mcp.McpCatalogScreen
import io.github.mangi.eta.ui.screens.metrics.AgentMetricsScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomConversationStage
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomCreateDialog
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomDeleteDialog
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomRenameDialog
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomScreen
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomsScreen
import io.github.mangi.eta.ui.screens.meeting.MeetingMarkdownExporter
import io.github.mangi.eta.ui.screens.a2a.A2ASettingsScreen
import io.github.mangi.eta.ui.screens.learning.LearningSettingsScreen
import io.github.mangi.eta.ui.screens.localmodel.LocalModelsScreen
import io.github.mangi.eta.ui.screens.tasks.AgentTaskCenterScreen
import io.github.mangi.eta.ui.screens.tasks.AutomationConversationDraft
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.prompt.PromptInjectionScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.subagent.SubagentSettingsScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.projects.ProjectsScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechOssScreen
import io.github.mangi.eta.ui.voice.SpeechRecognitionScreen
import io.github.mangi.eta.ui.voice.SpeechSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechSynthesisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Use the same full-width geometry for interactive back and programmatic navigation. A small
 * decorative offset leaves the outgoing page covering its destination until disposal, producing
 * an apparent freeze followed by a jump. Layer reads keep gesture frames out of composition.
 */
private val SettingsNavMotion = NavMotion(
    commit = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 900f),
    cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 900f),
    programmatic = NavSettleSpec.Tween(240, FastOutSlowInEasing),
)

private val SettingsNavTransition = navGraphicsTransition(motion = SettingsNavMotion) { scope ->
    if (scope.relativeDepth <= 0f) {
        val progress = (-scope.relativeDepth).coerceIn(0f, 1f)
        val direction = if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        translationX = kotlin.math.round(direction * progress * scope.layoutSize.width)
    }
}

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    requestedConversationId: String? = null,
    onRequestedConversationOpened: () -> Unit = {},
    requestedArchivedConversation: ArchivedConversationRequest? = null,
    onArchivedConversationOpened: () -> Unit = {},
    sharedContent: IncomingShareRequest? = null,
    onSharedContentAccepted: () -> Unit = {},
    openSpeechSettings: Boolean = false,
    openTerminal: Boolean = false,
    onTerminalOpened: () -> Unit = {},
    onSpeechSettingsOpened: () -> Unit = {},
    onAssistantConversationOpened: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uiScope = rememberCoroutineScope()
    val panePlacementMutex = remember { kotlinx.coroutines.sync.Mutex() }
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    LaunchedEffect(openSpeechSettings) {
        if (openSpeechSettings) {
            if (backStack.lastOrNull() != AppRoute.SpeechSettings) backStack.add(AppRoute.SpeechSettings)
            onSpeechSettingsOpened()
        }
    }
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            if (backStack.lastOrNull() != AppRoute.Terminal) backStack.add(AppRoute.Terminal)
            onTerminalOpened()
        }
    }
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    var navigationResetKey by rememberSaveable { mutableIntStateOf(0) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    if (agentState == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (appViewModel.startupFailed) {
                TextButton(text = "会话加载失败，点击重试", onClick = appViewModel::loadConversations)
            } else {
                Text("正在加载会话…")
            }
        }
        return
    }
    var activeMeetingRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectTaskToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var automationConversationDraft by remember { mutableStateOf<AutomationConversationDraft?>(null) }
    var preparingConversationTask by remember { mutableStateOf(false) }
    var projectMemoryScopeToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var conversationPaneShowsMeetings by rememberSaveable {
        mutableStateOf(activeMeetingRoomId != null)
    }
    var meetingArchiveViewOpen by rememberSaveable { mutableStateOf(false) }
    var homeNotepadOpen by rememberSaveable { mutableStateOf(false) }
    var createMeetingRoomDialogOpen by rememberSaveable { mutableStateOf(false) }
    val activeMeetingRoomFlow = remember(activeMeetingRoomId, context.applicationContext) {
        activeMeetingRoomId?.let { roomId ->
            MeetingRepository.room(context.applicationContext, roomId)
        } ?: flowOf<MeetingRoomEntity?>(null)
    }
    val activeMeetingRoom by activeMeetingRoomFlow.collectAsState(initial = null)
    val chatStreaming by remember(agentState) { derivedStateOf { agentState.homeState.isStreaming } }
    val runtimeActive by AgentRuntimeService.keepScreenOn.collectAsState()
    val view = LocalView.current
    val homeWorkActive = if (activeMeetingRoomId != null) {
        activeMeetingRoom?.status == MeetingRoomStatus.RUNNING
    } else {
        chatStreaming
    }
    val keepScreenOn = backStack.lastOrNull() == AppRoute.Home && homeWorkActive && runtimeActive
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val skills = appViewModel.skills
    val memory = appViewModel.memory
    val permissionHealth = appViewModel.permissionHealth
    val toolsState = remember { buildToolsState(context) }
    val characterStore = viewModel<CharacterLibraryViewModel>().store
    val communityCatalogStore = viewModel<CommunityCatalogViewModel>().store
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest(permissionHealth::refreshPermissionHealth)
    var microphonePermissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHealth.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                appViewModel.refreshKimiWeb()
                permissionHealth.refreshPermissionHealth()
                agentState.refreshRuntimeResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var conversationPaneOpen by rememberSaveable { mutableStateOf(false) }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationExportTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var meetingRoomRenameTarget by remember { mutableStateOf<MeetingRoomSummaryRow?>(null) }
    var meetingRoomDeleteTarget by remember { mutableStateOf<MeetingRoomSummaryRow?>(null) }
    var meetingRoomExportTarget by remember { mutableStateOf<MeetingRoomSummaryRow?>(null) }
    var folderDialogRequest by remember { mutableStateOf<ConversationFolderDialogRequest?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var contextBudgetConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = conversationExportTarget
        conversationExportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val markdown = agentState.exportConversationMarkdown(target.id)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_exported),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_export_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val meetingRoomExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = meetingRoomExportTarget
        meetingRoomExportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    MeetingRepository.snapshot(context.applicationContext, target.id).first()
                } ?: error("会议室不存在")
                val markdown = MeetingMarkdownExporter.export(snapshot)
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开导出文件")
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(context, "会议室已导出", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    context,
                    "导出失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    LaunchedEffect(requestedConversationId) {
        val conversationId = requestedConversationId ?: return@LaunchedEffect
        activeMeetingRoomId = null
        conversationPaneShowsMeetings = false
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
        navigator.popToHome()
        onRequestedConversationOpened()
    }

    LaunchedEffect(requestedArchivedConversation) {
        val request = requestedArchivedConversation ?: return@LaunchedEffect
        // 外部入口的结果要先导入归档才有会话；找不到时停在当前界面，不切到无关的会话。
        if (agentState.openArchivedConversation(request.source, request.conversationKey)) {
            activeMeetingRoomId = null
            conversationPaneShowsMeetings = false
            conversationPaneOpen = false
            navigator.popToHome()
        }
        onArchivedConversationOpened()
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            activeMeetingRoomId = null
            conversationPaneShowsMeetings = false
            conversationPaneOpen = false
            // 接管落到主聊天舞台：与主界面同一页面、同一侧边对话列表，不再开独立对话页。
            navigator.popToHome()
        }
        onAssistantConversationOpened(opened)
    }

    LaunchedEffect(sharedContent) {
        val request = sharedContent ?: return@LaunchedEffect
        focusManager.clearFocus()
        conversationPaneOpen = false
        if (backStack.lastOrNull() != AppRoute.Home) {
            navigator.popToHome()
            navigationResetKey++
        }
        agentState.acceptSharedContent(request)
        activeMeetingRoomId = null
        conversationPaneShowsMeetings = false
        onSharedContentAccepted()
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        if (!navigator.pop()) {
            (context as? Activity)?.finish()
        }
    }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        activeMeetingRoomId = null
        conversationPaneShowsMeetings = false
        agentState.selectConversation(conversationId)
        navigator.popToHome()
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        activeMeetingRoomId = null
        conversationPaneShowsMeetings = false
        agentState.createConversation()
        navigator.popToHome()
        conversationPaneOpen = false
    }

    fun selectHomeMode(
        showMeetings: Boolean,
        preferredMeetingRoomId: String? = null,
        closePane: Boolean = true,
    ) {
        focusManager.clearFocus()
        navigator.popToHome()
        if (closePane) conversationPaneOpen = false
        conversationPaneShowsMeetings = showMeetings
        meetingArchiveViewOpen = false
        if (!showMeetings) {
            activeMeetingRoomId = null
            return
        }
        val roomId = preferredMeetingRoomId ?: activeMeetingRoomId
        if (roomId != null) {
            activeMeetingRoomId = roomId
        } else {
            uiScope.launch {
                val firstRoomId = withContext(Dispatchers.IO) {
                    MeetingRepository.firstActiveRoomId(context.applicationContext)
                }
                if (firstRoomId == null) {
                    activeMeetingRoomId = null
                    createMeetingRoomDialogOpen = true
                } else {
                    activeMeetingRoomId = firstRoomId
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        EtaAppControlEvents.events.collect { event ->
            when (event) {
                is EtaAppControlEvent.Navigate -> {
                    val route = appControlRoute(event.routeKey) ?: return@collect
                    focusManager.clearFocus()
                    conversationPaneOpen = false
                    if (route == AppRoute.Home) navigator.popToHome() else pushRoute(route)
                }
            }
        }
    }

    fun openConversationBrowser() {
        focusManager.clearFocus()
        val sessionKey = agentState.conversationPaneState.selectedConversationId
            ?: BrowserSessionRegistry.MANUAL_SESSION_KEY
        context.startActivity(AgentBrowserActivity.createIntent(context, sessionKey))
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        homeGesturesEnabled: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route && homeGesturesEnabled,
            conversationPaneState = agentState.conversationPaneState,
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onConversationPaneModeChange = { showMeetings, meetingRoomId ->
                selectHomeMode(showMeetings, meetingRoomId, closePane = false)
            },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onOpenBranches = { pushRoute(AppRoute.ConversationBranches(agentState.conversationPaneState.selectedConversationId)) },
            onLaunchKimiWeb = {
                requestExecutionNotifications()
                if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
                    appViewModel.launchKimiWeb { result ->
                        if (result is KimiWebLaunchResult.Failed) {
                            Toast.makeText(
                                context,
                                result.message(context),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pushRoute(AppRoute.LinuxEnvironment)
                }
            },
            kimiWebLabel = appViewModel.kimiWebState.actionLabel(context),
            canStopKimiWeb = appViewModel.kimiWebState.canStop,
            onStopKimiWeb = appViewModel::stopKimiWeb,
            onRefreshKimiWeb = appViewModel::refreshKimiWeb,
            onOpenBrowser = { openConversationBrowser() },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationTogglePin = { conversation ->
                agentState.toggleConversationPin(conversation.id)
            },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationExport = { conversation ->
                conversationExportTarget = conversation
                conversationExportLauncher.launch(
                    ConversationMarkdownExporter.defaultFileName(
                        title = conversation.title.ifBlank { conversation.preview },
                        fallback = resources.getString(R.string.conversation_export_default_name),
                    ),
                )
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onConversationArchive = { conversation ->
                agentState.archiveConversation(conversation.id)
                if (conversation.id == agentState.conversationPaneState.selectedConversationId) {
                    agentState.setConversationArchiveViewOpen(true)
                }
            },
            onConversationRestore = { conversation ->
                agentState.restoreConversation(conversation.id)
            },
            onConversationMoveToFolder = { conversation ->
                folderDialogRequest = ConversationFolderDialogRequest.MoveConversation(conversation)
            },
            onConversationDropToFolder = { conversationId, folderId ->
                uiScope.launch { agentState.moveConversationToFolder(conversationId, folderId) }
            },
            onPanePlacement = { placement ->
                uiScope.launch {
                    panePlacementMutex.withLock {
                    runCatching {
                        if (placement.subject.kind == io.github.mangi.eta.data.model.ConversationSubject.Kind.MEETING) {
                            io.github.mangi.eta.fork.conversations.ConversationPaneOrganizationStore.validateMeetingFolder(context, placement.folderId)
                        } else check(placement.folderId == null || agentState.conversationPaneState.folders.any { it.id == placement.folderId }) {
                            "文件夹已不存在，请重试"
                        }
                        when (placement.subject.kind) {
                            io.github.mangi.eta.data.model.ConversationSubject.Kind.CHAT -> {
                                val row = agentState.conversationPaneState.unfilteredConversations.firstOrNull { it.id == placement.subject.id }
                                    ?: error("会话已不存在")
                                // UI snapshots can lag behind the preceding placement command.
                                // Pinning preserves the latest folder, including queued moves.
                                if (!placement.pinned) agentState.moveConversationToFolder(row.id, placement.folderId)
                                check(agentState.setConversationPinned(row.id, placement.pinned)?.await() != false) { "置顶状态保存失败" }
                            }
                            io.github.mangi.eta.data.model.ConversationSubject.Kind.MEETING -> withContext(Dispatchers.IO) {
                                MeetingRepository.setRoomPinned(context, placement.subject.id, placement.pinned)
                            }
                            else -> error("无法移动此条目")
                        }
                        io.github.mangi.eta.fork.conversations.ConversationPaneOrganizationStore.savePlacement(context, placement)
                    }.onFailure { error -> Toast.makeText(context, "移动未完成：${error.message ?: "请重试"}", Toast.LENGTH_LONG).show() }
                    }
                }
            },
            onOpenMeetingRoom = { roomId ->
                focusManager.clearFocus()
                activeMeetingRoomId = roomId
                conversationPaneShowsMeetings = true
                navigator.popToHome()
                conversationPaneOpen = false
            },
            onMeetingRoomRename = { room -> meetingRoomRenameTarget = room },
            onMeetingRoomTogglePin = { room ->
                uiScope.launch {
                    runCatching {
                        MeetingRepository.setRoomPinned(
                            context.applicationContext,
                            room.id,
                            room.pinnedAt == null,
                        )
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "置顶操作失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onMeetingRoomExport = { room ->
                meetingRoomExportTarget = room
                meetingRoomExportLauncher.launch(
                    MeetingMarkdownExporter.defaultFileName(room.title),
                )
            },
            onMeetingRoomDelete = { room -> meetingRoomDeleteTarget = room },
            onMeetingRoomArchiveChange = { room, archived ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            MeetingRepository.setRoomArchived(
                                context = context.applicationContext,
                                roomId = room.id,
                                archived = archived,
                            )
                        }
                    }.onSuccess {
                        meetingArchiveViewOpen = archived
                        conversationPaneShowsMeetings = true
                        if (archived && activeMeetingRoomId == room.id) {
                            activeMeetingRoomId = null
                        }
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "${if (archived) "归档" else "恢复"}失败：" +
                                (error.message ?: error.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onCreateFolder = { folderDialogRequest = ConversationFolderDialogRequest.Create(null) },
            onRenameFolder = { folder -> folderDialogRequest = ConversationFolderDialogRequest.Menu(folder) },
            onArchiveViewChange = agentState::setConversationArchiveViewOpen,
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.CapabilityCenter) },
            onOpenCharacters = { pushRoute(AppRoute.Characters) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenAppControl = { pushRoute(AppRoute.AppControlCenter) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
            homeTitle = if (route is AppRoute.Home && activeMeetingRoomId != null) {
                activeMeetingRoom?.title?.takeIf(String::isNotBlank)
                    ?: resources.getString(R.string.meeting_room_title)
            } else {
                null
            },
            showMeetingsByDefault = conversationPaneShowsMeetings,
            activeMeetingRoomId = activeMeetingRoomId,
            meetingArchiveViewOpen = meetingArchiveViewOpen,
            onMeetingArchiveViewChange = { meetingArchiveViewOpen = it },
            homeCharacter = if (route is AppRoute.Home && activeMeetingRoomId == null) {
                agentState.homeState.roleplayChatUi()
            } else {
                null
            },
            onOpenCharacter = {
                agentState.homeState.roleplay?.let { pushRoute(AppRoute.CharacterDetail(it.characterId)) }
            },
            onOpenNotepad = if (activeMeetingRoomId == null) {
                { homeNotepadOpen = true }
            } else {
                null
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                content()
            }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = swipeBackDirection.takeIf {
        LocalAppearanceSettings.current.swipeDismissEnabled
    }
    key(navigationResetKey) {
        NavDisplay(
            backStack = backStack,
            onBack = { popRoute() },
            transition = SettingsNavTransition,
            effects = NavDisplayEffects(
                enableCornerClip = false,
                dimAmount = 0f,
            ),
        ) {
            // 首页拥有自己的中央模式切换与右侧详情手势，导航返回滑动不能参与竞争。
            entry<AppRoute.Home>() {
                val insightMeetingRoomId = activeMeetingRoomId.orEmpty()
                val insightConversationId = agentState.conversationPaneState.selectedConversationId.orEmpty()
                var insightArtifactPreview by remember(insightConversationId, insightMeetingRoomId) { mutableStateOf<String?>(null) }
                insightArtifactPreview?.let { id -> io.github.mangi.eta.ui.components.SubjectArtifactPreview(id) { insightArtifactPreview = null } }
                ConversationInsightsPaneScaffold(
                    subjectKey = if (insightMeetingRoomId.isNotBlank()) "meeting:$insightMeetingRoomId" else "chat:$insightConversationId",
                    enabled = backStack.lastOrNull() == AppRoute.Home && !conversationPaneOpen &&
                        (insightMeetingRoomId.isNotBlank() || insightConversationId.isNotBlank()),
                    paneContent = { dismiss ->
                        ConversationInsightsScreen(
                            conversationId = insightConversationId,
                            meetingRoomId = insightMeetingRoomId,
                            onBack = dismiss,
                            onRunClick = { pushRoute(AppRoute.AgentMetricDetail(it)) },
                            onArtifactClick = { insightArtifactPreview = it },
                            onDshCommand = { name, args ->
                                if (args.isNotBlank()) false
                                else if (insightMeetingRoomId.isNotBlank()) {
                                    if (name == "stop") { io.github.mangi.eta.fork.meeting.MeetingRunCoordinator.stop(insightMeetingRoomId); true } else false
                                } else when (name) {
                                    "stop" -> { agentState.stopCurrentRun(); true }
                                    "compact" -> { agentState.compactCurrentContext(); true }
                                    else -> false
                                }
                            },
                        )
                    },
                ) { insightPaneActive ->
                    val homeGestureAppearance = LocalAppearanceSettings.current.normalized()
                    val homeModeSwipeEnabled = homeGestureAppearance.homeModeSwipeEnabled
                    val insightSwipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) {
                        homeGestureAppearance.homeModeSwipeThresholdDp.dp.toPx()
                    }
                    BackHandler(
                        enabled = activeMeetingRoomId != null && !conversationPaneOpen && !insightPaneActive &&
                            backStack.lastOrNull() == AppRoute.Home,
                    ) {
                        activeMeetingRoomId = null
                        conversationPaneShowsMeetings = false
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(activeMeetingRoomId, conversationPaneOpen, insightPaneActive, insightSwipeThreshold, homeModeSwipeEnabled) {
                                if (!homeModeSwipeEnabled || conversationPaneOpen || insightPaneActive || backStack.lastOrNull() != AppRoute.Home) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val gestureStartX = down.position.x
                                    val gestureStartY = down.position.y
                                    // Lower gestures belong exclusively to the two continuous side panes.
                                    if (gestureStartY >= size.height * 0.40f) return@awaitEachGesture
                                    var gestureDistance = 0f
                                    val active = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                        gestureDistance = overSlop
                                        change.consume()
                                    } ?: return@awaitEachGesture
                                    if (!active.isConsumed) return@awaitEachGesture
                                    var cancelled = false
                                    while (active.pressed) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == active.id } ?: break
                                        if (change.isConsumed) { cancelled = true; break }
                                        val delta = change.positionChange().x
                                        if (delta != 0f) {
                                            gestureDistance += delta
                                            change.consume()
                                        }
                                        if (!change.pressed) break
                                    }
                                    if (cancelled) return@awaitEachGesture
                                    when (HomeSwipeGesturePolicy.classify(
                                        startX = gestureStartX,
                                        width = size.width.toFloat(),
                                        totalDragX = gestureDistance,
                                        thresholdPx = insightSwipeThreshold,
                                        startY = gestureStartY,
                                        height = size.height.toFloat(),
                                    )) {
                                        HomeSwipeGestureAction.SwitchConversationMeeting -> {
                                            selectHomeMode(showMeetings = activeMeetingRoomId == null)
                                        }
                                        else -> Unit
                                    }
                                }
                            },
                    ) {
                        RoutedShell(route = AppRoute.Home, homeGesturesEnabled = !insightPaneActive) {
                            if (activeMeetingRoomId != null) {
                                MeetingRoomConversationStage(
                                    roomId = requireNotNull(activeMeetingRoomId),
                                    onDeleted = {
                                        activeMeetingRoomId = null
                                        conversationPaneShowsMeetings = false
                                    },
                                    onArchived = {
                                        activeMeetingRoomId = null
                                        conversationPaneShowsMeetings = true
                                        meetingArchiveViewOpen = true
                                    },
                                    onOpenSettings = { pushRoute(AppRoute.MeetingSettings) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                AgentHomeScreen(
                                    state = agentState.homeState,
                                    modelPickerState = agentState.modelPickerState,
                                    conversationKey = agentState.conversationPaneState.selectedConversationId,
                                    onNewConversation = { createConversation() },
                                    onOpenMcp = { pushRoute(AppRoute.McpServers) },
                                    onOpenTools = { pushRoute(AppRoute.Tools) },
                                    notepadOpen = homeNotepadOpen,
                                    onNotepadOpenChange = { homeNotepadOpen = it },
                                    onAction = { action ->
                                        when (action) {
                                        is AgentHomeAction.ReasoningEffortChanged ->
                                            agentState.updateReasoningEffort(action.effort)
                                        is AgentHomeAction.WorkflowModeChanged ->
                                            agentState.updateWorkflowMode(action.mode)
                                        is AgentHomeAction.SelectedSkillIdsChanged ->
                                            agentState.updateSelectedSkillIds(action.skillIds)
                                        AgentHomeAction.CompactContext -> agentState.compactCurrentContext()
                                        AgentHomeAction.ConfigureContextBudget -> {
                                            contextBudgetConversationId = agentState.conversationPaneState.selectedConversationId
                                        }
                                        AgentHomeAction.LoadEarlierMessages -> agentState.loadEarlierMessages()
                                        is AgentHomeAction.ForkMessage -> agentState.createConversationBranch(action.id)
                                        is AgentHomeAction.CreateTaskFromMessage -> {
                                            val conversationId = agentState.conversationPaneState.selectedConversationId
                                            val message = agentState.homeState.messages.filterIsInstance<io.github.mangi.eta.ui.model.UserMessageUi>()
                                                .firstOrNull { it.id == action.id }
                                            if (!preparingConversationTask && conversationId != null && message != null && !agentState.homeState.isStreaming) {
                                                val parsed = io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.parse(message.content)
                                                if (parsed.request.isNotBlank()) {
                                                    preparingConversationTask = true
                                                    uiScope.launch {
                                                        try {
                                                            val binding = io.github.mangi.eta.fork.projects.ProjectRepository.bindingForSubject(
                                                                context, io.github.mangi.eta.fork.projects.ProjectSubjectType.CONVERSATION, conversationId)
                                                            automationConversationDraft = AutomationConversationDraft(parsed.request, binding?.projectId,
                                                                message.images.isNotEmpty() || parsed.references.isNotEmpty())
                                                            projectTaskToOpen = null
                                                            pushRoute(AppRoute.TaskCenter)
                                                        } catch (cancelled: CancellationException) {
                                                            throw cancelled
                                                        } catch (_: Exception) {
                                                            Toast.makeText(context, R.string.chat_create_automation_failed, Toast.LENGTH_SHORT).show()
                                                        } finally {
                                                            preparingConversationTask = false
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        is AgentHomeAction.HistoryViewportChanged -> agentState.updateHistoryViewport(action.atLatest)
                                        AgentHomeAction.RetryConversationLoad -> agentState.retryConversationLoad()
                                        is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId)
                                        is AgentHomeAction.InputSyncApplied -> agentState.consumeInputSync(action.conversationId, action.version, action.text)
                                        is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                        is AgentHomeAction.InterruptAndSubmitMessage -> { requestExecutionNotifications(); agentState.interruptAndSendCurrentMessage(action.text) }
                                        is AgentHomeAction.SteerQueuedMessage -> { requestExecutionNotifications(); agentState.steerQueuedMessage(action.id) }
                                        is AgentHomeAction.RecallQueuedMessage -> agentState.recallQueuedMessage(action.id, action.currentDraftText)
                                        is AgentHomeAction.RemoveQueuedMessage -> agentState.removeQueuedMessage(action.id)
                                        AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                        is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                        is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                        is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                        is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                        is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                        is AgentHomeAction.RemoveFileReference ->
                                            agentState.removePendingFileReference(action.id)
                                        is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                        AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                        is AgentHomeAction.DeleteMessage -> {
                                            agentState.messageRevisionImpact(action.id)?.let { impact ->
                                                messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                            }
                                        }
                                        is AgentHomeAction.RegenerateMessage -> {
                                            val impact = agentState.messageRevisionImpact(action.id)
                                            if (agentState.homeState.roleplay != null || impact?.laterTurnCount == 0) {
                                                agentState.regenerateMessage(action.id)
                                            } else if (impact != null) {
                                                messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                            }
                                        }
                                        is AgentHomeAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
                                        AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                        AgentHomeAction.OpenSkills -> pushRoute(AppRoute.CapabilityCenter)
                                        AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                        AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                        AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                        AgentHomeAction.OpenBrowser -> openConversationBrowser()
                                        AgentHomeAction.ExpandRunTrace -> Unit
                                        }
                                    },
                                    isLoadingConversation = agentState.isLoadingConversation,
                                    onSubmitMessage = { text, interrupt ->
                                        requestExecutionNotifications()
                                        if (interrupt) agentState.interruptAndSendCurrentMessage(text)
                                        else agentState.sendCurrentMessage(text)
                                    },
                                    conversationLoadFailed = agentState.conversationLoadFailed,
                                    historyLoadState = agentState.historyLoadState,
                                )
                            }
                        }
                    }
                }
            }
            entry<AppRoute.Browser> { route ->
                // 保留已保存路由的恢复入口；新打开操作直接进入原生浏览器窗口。
                LaunchedEffect(route) {
                    context.startActivity(AgentBrowserActivity.createIntent(context, route.sessionKey))
                    backStack.remove(route)
                    if (backStack.isEmpty()) backStack.add(AppRoute.Home)
                }
            }
            entry<AppRoute.Terminal>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                        isVisible = backStack.lastOrNull() == AppRoute.Terminal,
                    )
                }
            }
            entry<AppRoute.Tools>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    AgentToolsScreen(
                        state = toolsState,
                        onAction = { action ->
                            when (action) {
                                AgentToolsAction.NavigateBack -> popRoute()
                                AgentToolsAction.OpenBrowser -> openConversationBrowser()
                                AgentToolsAction.OpenWebToolsSettings -> pushRoute(AppRoute.WebToolsSettings)
                                AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                                AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                            }
                        },
                    )
                }
            }
            entry<AppRoute.AppControlCenter>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    AppControlScreen(
                        onBack = ::popRoute,
                        onNavigate = { route -> pushRoute(route) },
                    )
                }
            }
            entry<AppRoute.ToolsAndDevices>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.ToolsAndDevicesSettingsScreen(
                        onBack = ::popRoute,
                        onNavigate = { pushRoute(it) },
                    )
                }
            }
            entry<AppRoute.WebToolsSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    WebToolsSettingsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.CloudToolsSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.screens.tools.CloudToolsSettingsScreen(
                        onBack = ::popRoute, onNavigate = { pushRoute(it) })
                }
            }
            entry<AppRoute.Subagents>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SubagentSettingsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.TaskCenter>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    val requestedTask = projectTaskToOpen
                    AgentTaskCenterScreen(onBack = { projectTaskToOpen = null; automationConversationDraft = null; popRoute() },
                        initialTaskId = requestedTask, initialDraft = automationConversationDraft)
                }
            }
            entry<AppRoute.A2AAgents>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    A2ASettingsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.SelfLearning>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LearningSettingsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.Skills>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LaunchedEffect(Unit) {
                        skills.refreshSkills()
                    }
                    AgentSkillsScreen(
                        state = skills.skillsState,
                        onAction = { action ->
                            when (action) {
                                AgentSkillsAction.NavigateBack -> popRoute()
                                AgentSkillsAction.Refresh -> skills.refreshSkills()
                                is AgentSkillsAction.ImportZip -> skills.importSkillZip(action.uri)
                                AgentSkillsAction.ConfirmZipReplacement -> skills.confirmSkillZipReplacement()
                                AgentSkillsAction.CancelZipReplacement -> skills.cancelSkillZipReplacement()
                                AgentSkillsAction.DismissNotice -> skills.dismissSkillNotice()
                                is AgentSkillsAction.ToggleSkill -> skills.toggleSkill(action.skillId, action.enabled)
                                is AgentSkillsAction.DeleteSkill -> skills.deleteSkill(action.skillId)
                                is AgentSkillsAction.ReinstallBuiltin -> skills.reinstallBuiltin(action.skillId)
                                is AgentSkillsAction.OpenEditor -> skills.openEditor(action.skillId)
                                is AgentSkillsAction.SaveEditor -> skills.saveEditor(action.skillId, action.name, action.body, action.revision)
                                AgentSkillsAction.CloseEditor -> skills.closeEditor()
                                is AgentSkillsAction.RestoreSkillVersion ->
                                    skills.restoreSkillVersion(action.skillId, action.backupId)
                            }
                        },
                    )
                }
            }
            entry<AppRoute.PromptInjection>(swipeDismiss = swipeDismiss) {
                NexusSettingsTheme {
                    LaunchedEffect(Unit) { characterStore.loadLibrary() }
                    CharacterLibraryScreen(
                        store = characterStore,
                        onNavigate = { if (navigator.current() == AppRoute.PromptInjection) pushRoute(it) },
                        onOpenInstructions = {},
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.CapabilityCenter>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    val dshConversation = remember { agentState.conversationPaneState.selectedConversationId }
                    CapabilityCenterScreen(
                        onBack = ::popRoute,
                        onOpenSkills = { pushRoute(AppRoute.Skills) },
                        onOpenMcp = { pushRoute(AppRoute.McpServers) },
                        onOpenApiConnectors = { pushRoute(AppRoute.ApiConnectors) },
                        onOpenA2A = { pushRoute(AppRoute.A2AAgents) },
                        onOpenConnectorCenter = { pushRoute(AppRoute.ConnectorCenter) },
                        onOpenPermissions = { pushRoute(AppRoute.Permissions) },
                        onOpenPromptInjection = { pushRoute(AppRoute.Characters) },
                        onOpenLocalModels = { pushRoute(AppRoute.LocalModels) },
                        onOpenSpeechRecognition = { pushRoute(AppRoute.SpeechRecognition) },
                        onOpenSpeechSynthesis = { pushRoute(AppRoute.SpeechSynthesis) },
                        onDshInsert = { text -> agentState.insertPluginDraft(dshConversation, text) },
                        dshDraft = { agentState.pluginDraft(dshConversation) },
                        onDshCommand = { name, args ->
                            if (args.isNotBlank() || agentState.conversationPaneState.selectedConversationId != dshConversation) false else when (name) {
                                "compact" -> { agentState.compactCurrentContext(); true }
                                "stop" -> { agentState.stopCurrentRun(); true }
                                else -> false
                            }
                        },
                    )
                }
            }
            entry<AppRoute.ApiConnectors>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    ApiConnectorsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.ConnectorCenter>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    ConnectorCenterScreen(
                        onBack = ::popRoute,
                        onOpenA2A = { pushRoute(AppRoute.A2AAgents) },
                        onOpenMcp = { pushRoute(AppRoute.McpServers) },
                        onOpenRest = { pushRoute(AppRoute.ApiConnectors) },
                    )
                }
            }
            entry<AppRoute.Characters>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LaunchedEffect(backStack.lastOrNull() == AppRoute.Characters) {
                        if (backStack.lastOrNull() == AppRoute.Characters) characterStore.loadLibrary()
                    }
                    CharacterLibraryScreen(
                        store = characterStore,
                        onNavigate = { if (navigator.current() == AppRoute.Characters) pushRoute(it) },
                        onOpenInstructions = {
                            if (navigator.current() == AppRoute.Characters) pushRoute(AppRoute.PromptInjection)
                        },
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.CharacterDetail>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadDetail(route.characterId)
                }
                CharacterDetailScreen(route.characterId, characterStore, { if (navigator.current() == route) pushRoute(it) }, ::popRoute) { binding, greeting ->
                    if (navigator.current() == route) {
                        agentState.startCharacterConversation(binding, greeting)
                        conversationPaneOpen = false
                        navigator.popToHome()
                        // 开始新故事直接呈现首页；重置导航呈现态，避免多层退栈扫过角色列表。
                        navigationResetKey++
                    }
                }
            }
            entry<AppRoute.CharacterEditor>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadEditor(route.characterId)
                }
                CharacterEditorScreen(route.characterId, characterStore, ::popRoute) { id ->
                    if (navigator.current() == route) navigator.replace(AppRoute.CharacterDetail(id))
                }
            }
            entry<AppRoute.CharacterPersona>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LaunchedEffect(backStack.lastOrNull() == AppRoute.CharacterPersona) {
                        if (backStack.lastOrNull() == AppRoute.CharacterPersona) characterStore.loadPersona()
                    }
                    CharacterPersonaScreen(characterStore) {
                        if (navigator.current() == AppRoute.CharacterPersona) popRoute()
                    }
                }
            }
            entry<AppRoute.CharacterMemory>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadMemory(route.characterId)
                }
                CharacterMemoryScreen(route.characterId, characterStore, ::popRoute)
            }
            entry<AppRoute.Permissions>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LaunchedEffect(Unit) {
                        permissionHealth.refreshPermissionHealth()
                    }
                    PermissionHealthScreen(
                        state = permissionHealth.permissionHealthState,
                        onAction = { action ->
                            when (action) {
                                PermissionHealthAction.NavigateBack -> popRoute()
                                is PermissionHealthAction.SetRootAccessibilityRecovery ->
                                    permissionHealth.setRootAccessibilityRecovery(action.enabled)
                                PermissionHealthAction.RetryRootAccessibilityRecovery ->
                                    permissionHealth.retryRootAccessibilityRecovery()
                                is PermissionHealthAction.SetAgentPermissionMode ->
                                    permissionHealth.setAgentPermissionMode(action.mode)
                                is PermissionHealthAction.OpenItemAction -> {
                                    when (action.itemId) {
                                        LOCAL_NETWORK_PERMISSION_ITEM_ID -> requestLocalNetworkPermission()
                                        "calendar" -> permissionRequestLauncher.launch(io.github.mangi.eta.agent.device.CalendarPermissions.requested)
                                        "notification_policy" -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                        "accessibility" -> {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            }
                                        }
                                        "microphone" -> {
                                            val microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                                android.content.pm.PackageManager.PERMISSION_GRANTED
                                            val canRequestAgain = (context as? Activity)?.shouldShowRequestPermissionRationale(
                                                Manifest.permission.RECORD_AUDIO,
                                            ) == true
                                            if (microphoneGranted || (microphonePermissionRequested && !canRequestAgain)) {
                                                openFirstAvailableSettings(context, listOf(Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    "package:${context.packageName}".toUri(),
                                                )))
                                            } else {
                                                microphonePermissionRequested = true
                                                permissionRequestLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                            }
                                        }
                                        "overlay" -> {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                        "background" -> {
                                            uiScope.launch {
                                                openBackgroundPermissionSettings(context)
                                            }
                                        }
                                        "app_list" -> {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                        "location" -> {
                                            when (DeviceLocationProvider.accessState(context)) {
                                                DeviceLocationProvider.AccessState.DENIED -> {
                                                    permissionRequestLauncher.launch(
                                                        arrayOf(
                                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                                        )
                                                    )
                                                }
                                                DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(
                                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                Uri.parse("package:${context.packageName}")
                                                            )
                                                        )
                                                    }
                                                }
                                                DeviceLocationProvider.AccessState.DISABLED -> {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                        )
                                                    }
                                                }
                                                DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                    permissionHealth.refreshPermissionHealth()
                                                }
                                            }
                                        }
                                        "notification_history" -> {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                            }
                                        }
                                        "usage_access" -> {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                            }
                                        }
                                        "notifications" -> {
                                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                        }
                                        "root" -> pushRoute(AppRoute.SystemEnhance)
                                    }
                                }
                            }
                        },
                    )
                }
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SystemEnhanceScreen(
                        onAction = { action ->
                            when (action) {
                                AgentSystemEnhanceAction.NavigateBack -> popRoute()
                                AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                                AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Workspace>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                io.github.mangi.eta.ui.screens.projects.ProjectsWorkspaceScreen(
                    onBack = ::popRoute,
                    initialFiles = true,
                    onOpenSubject = { subject ->
                        if (subject.type == io.github.mangi.eta.fork.projects.ProjectSubjectType.MEETING) {
                            selectHomeMode(showMeetings = true, preferredMeetingRoomId = subject.id)
                        } else selectConversation(subject.id)
                    },
                    onOpenTask = { taskId -> projectTaskToOpen = taskId; pushRoute(AppRoute.TaskCenter) },
                    onOpenMemory = { projectId -> projectMemoryScopeToOpen = "project:$projectId"; pushRoute(AppRoute.Memory) },
                )
            }
            entry<AppRoute.Projects>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                io.github.mangi.eta.ui.screens.projects.ProjectsWorkspaceScreen(onBack = ::popRoute, onOpenSubject = { subject ->
                    if (subject.type == io.github.mangi.eta.fork.projects.ProjectSubjectType.MEETING) {
                        selectHomeMode(showMeetings = true, preferredMeetingRoomId = subject.id)
                    } else {
                        selectConversation(subject.id)
                    }
                }, onOpenTask = { taskId ->
                    projectTaskToOpen = taskId
                    pushRoute(AppRoute.TaskCenter)
                }, onOpenMemory = { projectId ->
                    projectMemoryScopeToOpen = "project:$projectId"
                    pushRoute(AppRoute.Memory)
                })
            }
            entry<AppRoute.Settings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SettingsScreen(
                        context = context,
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute
                    )
                }
            }
            entry<AppRoute.DigitalAssistantSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.DigitalAssistantSettingsScreen(
                        onBack = ::popRoute, onNavigate = { pushRoute(it) },
                    )
                }
            }
            entry<AppRoute.SearchGestureSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.SearchGestureSettingsScreen(
                        onBack = ::popRoute, onNavigate = { pushRoute(it) },
                    )
                }
            }
            entry<AppRoute.SpeechSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SpeechSettingsScreen(
                        onBack = ::popRoute,
                        onNavigate = { route -> pushRoute(route) },
                    )
                }
            }
            entry<AppRoute.SpeechRecognition>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SpeechRecognitionScreen(
                        onBack = ::popRoute,
                        onOpenOss = { pushRoute(AppRoute.SpeechOss) },
                        onOpenLocalModels = { pushRoute(AppRoute.LocalModels) },
                    )
                }
            }
            entry<AppRoute.SpeechSynthesis>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SpeechSynthesisScreen(
                        onBack = ::popRoute,
                        onOpenLocalModels = { pushRoute(AppRoute.LocalModels) },
                    )
                }
            }
            entry<AppRoute.SpeechOss>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SpeechOssScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    AppearanceSettingsScreen(onBack = ::popRoute,
                        onOpenMessageTypography = { pushRoute(AppRoute.MessageTypography) })
                }
            }
            entry<AppRoute.MessageTypography>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.MessageTypographyScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.ConversationArchiveSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    ConversationArchiveSettingsScreen(
                        onBack = ::popRoute,
                        onOpenConversation = { id -> selectConversation(id) },
                        onOpenMeeting = { id ->
                            focusManager.clearFocus()
                            activeMeetingRoomId = id
                            conversationPaneShowsMeetings = true
                            meetingArchiveViewOpen = true
                            conversationPaneOpen = false
                            navigator.popToHome()
                        },
                    )
                }
            }
            entry<AppRoute.MeetingRooms>(swipeDismiss = swipeDismiss) { route ->
                MeetingRoomsScreen(
                    onOpenRoom = { roomId ->
                        activeMeetingRoomId = roomId
                        conversationPaneShowsMeetings = true
                        meetingArchiveViewOpen = false
                        navigator.popToHome()
                    },
                    onBack = ::popRoute,
                    openCreateOnStart = route.openCreateOnStart,
                )
            }
            entry<AppRoute.MeetingSettings>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    MeetingSettingsScreen(onBack = ::popRoute)
                }
            }
            entry<AppRoute.MeetingRoom>(swipeDismiss = swipeDismiss) { route ->
                MeetingRoomScreen(
                    roomId = route.roomId,
                    onBack = ::popRoute,
                    onOpenSettings = { pushRoute(AppRoute.MeetingSettings) },
                )
            }
            entry<AppRoute.DataBackup>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    DataBackupScreen(
                        context = context,
                        onBack = ::popRoute,
                        onExport = agentState::exportBackup,
                        onImport = agentState::importBackup,
                    )
                }
            }
            entry<AppRoute.Memory>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LaunchedEffect(Unit) {
                        memory.refreshMemory()
                    }
                    AgentMemoryScreen(
                        state = memory.memoryState,
                        initialScope = projectMemoryScopeToOpen,
                        onOpenCharacterMemory = { characterId -> pushRoute(AppRoute.CharacterMemory(characterId)) },
                        onAction = { action ->
                            when (action) {
                                AgentMemoryAction.NavigateBack -> { projectMemoryScopeToOpen = null; popRoute() }
                                is AgentMemoryAction.ToggleEnabled -> memory.setMemoryEnabled(action.enabled)
                                is AgentMemoryAction.DraftChanged -> memory.updateMemoryDraft(action.content)
                                AgentMemoryAction.Save -> memory.saveMemory()
                                AgentMemoryAction.Clear -> memory.clearMemory()
                                AgentMemoryAction.DismissNotice -> memory.dismissMemoryNotice()
                                is AgentMemoryAction.ScopeSelected -> memory.selectScope(action.scope)
                            }
                        },
                    )
                }
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LinuxEnvironmentScreen(
                        context = context,
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    SharedFoldersScreen(
                        context = context,
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    ModelProviderListScreen(
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute
                    )
                }
            }
            entry<AppRoute.LocalModels>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    LocalModelsScreen(
                        onBack = ::popRoute,
                        onOpenSpeechRecognition = { pushRoute(AppRoute.SpeechRecognition) },
                        onOpenSpeechSynthesis = { pushRoute(AppRoute.SpeechSynthesis) },
                        onOpenLocalService = {
                            pushRoute(AppRoute.ModelProviderNew(NewProviderType.LocalRuntime))
                        },
                    )
                }
            }
            entry<AppRoute.CommunityCatalog>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    CommunityCatalogScreen(
                        store = communityCatalogStore,
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.CommunityCatalogProvider>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    CommunityCatalogProviderScreen(
                        catalogId = route.catalogId,
                        store = communityCatalogStore,
                        onImported = { providerId -> navigator.replace(AppRoute.ModelProviderDetail(providerId)) },
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.McpServers>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    McpServersScreen(
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute,
                    )
                }
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    McpServerDetailScreen(
                        serverId = route.serverId,
                        onBack = ::popRoute,
                        onOpenCatalog = { pushRoute(AppRoute.McpCatalog(route.serverId)) },
                    )
                }
            }
            entry<AppRoute.McpCatalog>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    McpCatalogScreen(serverId = route.serverId, onBack = ::popRoute)
                }
            }
            entry<AppRoute.AgentMetrics>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    AgentMetricsScreen(onBack = ::popRoute, onOpenRun = { pushRoute(AppRoute.AgentMetricDetail(it)) },
                        onOpenPrices = { pushRoute(AppRoute.AgentTokenPricing) })
                }
            }
            entry<AppRoute.ConversationBranches>(swipeDismiss = swipeDismiss) { route ->
                io.github.mangi.eta.ui.screens.conversations.ConversationBranchesScreen(
                    conversationId = route.conversationId, onBack = ::popRoute,
                    onOpenConversation = { id -> activeMeetingRoomId = null; agentState.selectConversation(id); popRoute() },
                    onDelete = { id -> if (!agentState.isConversationBusy(id)) agentState.deleteConversation(id) },
                    isBusy = agentState::isConversationBusy,
                    onOpenRun = { pushRoute(AppRoute.AgentMetricDetail(it)) })
            }
            entry<AppRoute.ConversationInsights>(swipeDismiss = swipeDismiss) { route ->
                var insightArtifactPreview by remember(route.conversationId, route.meetingRoomId) { mutableStateOf<String?>(null) }
                insightArtifactPreview?.let { id -> io.github.mangi.eta.ui.components.SubjectArtifactPreview(id) { insightArtifactPreview = null } }
                ConversationInsightsScreen(
                    conversationId = route.conversationId,
                    meetingRoomId = route.meetingRoomId,
                    onBack = ::popRoute,
                    onRunClick = { pushRoute(AppRoute.AgentMetricDetail(it)) },
                    onArtifactClick = { insightArtifactPreview = it },
                    onDshCommand = { name, args ->
                        if (args.isNotBlank()) false
                        else if (route.meetingRoomId.isNotBlank()) {
                            if (name == "stop") { io.github.mangi.eta.fork.meeting.MeetingRunCoordinator.stop(route.meetingRoomId); true } else false
                        } else if (agentState.conversationPaneState.selectedConversationId != route.conversationId) false
                        else when (name) {
                            "stop" -> { agentState.stopCurrentRun(); true }
                            "compact" -> { agentState.compactCurrentContext(); true }
                            else -> false
                        }
                    },
                )
            }
            entry<AppRoute.AgentTokenPricing>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) {
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.screens.metrics.AgentTokenPricingScreen(onBack = ::popRoute,
                        onEdit = { provider, model -> pushRoute(AppRoute.AgentTokenPriceEditor(provider, model)) })
                }
            }
            entry<AppRoute.AgentTokenPriceEditor>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.screens.metrics.AgentTokenPriceEditorScreen(route.providerId, route.modelId, onBack = ::popRoute)
                }
            }
            entry<AppRoute.AgentMetricDetail>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    io.github.mangi.eta.ui.screens.metrics.AgentMetricDetailScreen(
                        recordId = route.recordId,
                        onBack = ::popRoute,
                        onOpenConversation = ::selectConversation,
                        onOpenMeeting = { pushRoute(AppRoute.MeetingRoom(it)) },
                        onOpenTasks = { pushRoute(AppRoute.TaskCenter) },
                    )
                }
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    ModelProviderDetailScreen(
                        providerId = route.providerId,
                        onBack = ::popRoute
                    )
                }
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = swipeDismiss, transition = SettingsNavTransition) { route ->
                NexusSettingsTheme {
                    ModelProviderDetailScreen(
                        newType = route.providerType,
                        onBack = ::popRoute
                    )
                }
            }
        }
    }

    if (createMeetingRoomDialogOpen) {
        MeetingRoomCreateDialog(
            onDismiss = {
                createMeetingRoomDialogOpen = false
                if (activeMeetingRoomId == null) conversationPaneShowsMeetings = false
            },
            onCreated = { roomId ->
                createMeetingRoomDialogOpen = false
                activeMeetingRoomId = roomId
                conversationPaneShowsMeetings = true
                meetingArchiveViewOpen = false
                navigator.popToHome()
                conversationPaneOpen = false
            },
        )
    }

    WindowDialog(show = agentState.isCreatingConversationBranch, title = stringResource(R.string.branch_create),
        summary = stringResource(agentState.conversationBranchPhase), onDismissRequest = agentState::cancelConversationBranch) {
        TextButton(text = stringResource(R.string.action_cancel), onClick = agentState::cancelConversationBranch,
            enabled = agentState.canCancelConversationBranch, modifier = Modifier.fillMaxWidth())
    }

    contextBudgetConversationId?.let { conversationId ->
        ConversationContextBudgetDialog(
            conversationId = conversationId,
            historySize = agentState.homeState.history.size,
            onCompactSelection = { selection -> agentState.compactCurrentContext(selection) },
            onDismiss = { contextBudgetConversationId = null },
        )
    }

    meetingRoomRenameTarget?.let { room ->
        MeetingRoomRenameDialog(
            roomId = room.id,
            currentTitle = room.title,
            onDismiss = { meetingRoomRenameTarget = null },
        )
    }

    meetingRoomDeleteTarget?.let { room ->
        MeetingRoomDeleteDialog(
            roomId = room.id,
            roomTitle = room.title,
            onDismiss = { meetingRoomDeleteTarget = null },
            onDeleted = {
                meetingRoomDeleteTarget = null
                if (activeMeetingRoomId == room.id) {
                    activeMeetingRoomId = null
                    conversationPaneShowsMeetings = false
                }
            },
        )
    }

    characterStore.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = "角色",
            summary = notice,
            cornerRadius = DialogDefaults.CornerRadius,
            onDismissRequest = characterStore::dismissNotice,
        ) {
            top.yukonga.miuix.kmp.basic.TextButton(
                text = "知道了", onClick = characterStore::dismissNotice, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_rename_title),
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    ConversationFolderDialogs(
        request = folderDialogRequest,
        folders = agentState.conversationPaneState.folders,
        onRequestChange = { folderDialogRequest = it },
        onCreate = { name, parentId ->
            agentState.createConversationFolder(name, parentId)
        },
        onRename = agentState::renameConversationFolder,
        onMoveFolder = agentState::moveConversationFolder,
        onMoveConversation = agentState::moveConversationToFolder,
        onSetFolderPinned = agentState::setConversationFolderPinned,
        onDelete = agentState::deleteConversationFolder,
    )

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(if (conversation.isActiveRun) R.string.conversation_stop_and_delete_message else R.string.conversation_delete_message),
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(if (conversation.isActiveRun) R.string.conversation_stop_and_delete else R.string.action_delete),
                destructive = true,
                onCancel = { conversationDeleteTarget = null },
                onConfirm = {
                    if ((backStack.lastOrNull() as? AppRoute.Browser)?.sessionKey == conversation.id) {
                        navigator.popToHome()
                    }
                    agentState.deleteConversation(conversation.id)
                    conversationDeleteTarget = null
                },
            )
        }
    }

    messageDeleteTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_delete_message_body)
            } else {
                pluralStringResource(
                    R.plurals.conversation_delete_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { messageDeleteTarget = null },
                onConfirm = {
                    agentState.deleteMessageTurn(target.messageId)
                    messageDeleteTarget = null
                },
            )
        }
    }

    messageRegenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_regenerate_current_turn)
            } else {
                pluralStringResource(
                    R.plurals.conversation_regenerate_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageRegenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_regenerate),
                destructive = true,
                onCancel = { messageRegenerateTarget = null },
                onConfirm = {
                    agentState.regenerateMessage(target.messageId)
                    messageRegenerateTarget = null
                },
            )
        }
    }

    EtaAppControlConfirmationHost()
    io.github.mangi.eta.ui.screens.control.RunUserInputHost(
        onRecoveredAnswer = { request, answer -> agentState.resumeRecoveredRunInput(request, answer) },
        conversationId = agentState.conversationPaneState.selectedConversationId,
        meetingRoomId = activeMeetingRoomId,
    )
}

@Composable
private fun ConversationContextBudgetDialog(
    conversationId: String,
    historySize: Int,
    onCompactSelection: (IntRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(conversationId) { mutableStateOf<Int?>(null) }
    var customText by rememberSaveable(conversationId) { mutableStateOf("") }
    var loaded by remember(conversationId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var rangeText by rememberSaveable(conversationId) { mutableStateOf("") }
    var compactionBinding by remember(conversationId) { mutableStateOf<ModelRoleBinding?>(null) }
    var choosingCompaction by rememberSaveable(conversationId) { mutableStateOf(false) }
    var chooserProviderId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val compactionProviders = remember(providers) {
        providers.filter { provider ->
            provider.isEnabled && provider.hasUsableConnection() && provider.models.any { it.isEnabled }
        }
    }
    val selectedCompaction = remember(compactionProviders, compactionBinding) {
        compactionBinding?.let { binding ->
            compactionProviders.firstNotNullOfOrNull { provider ->
                if (binding.providerId != null && provider.id != binding.providerId) return@firstNotNullOfOrNull null
                provider.models.firstOrNull { it.id == binding.modelId && it.isEnabled }
                    ?.let { model -> provider to model }
            }
        }
    }
    val compactionLabel = when {
        compactionBinding == null -> "跟随全局"
        selectedCompaction == null -> "覆盖不可用，正在跟随全局"
        else -> "${selectedCompaction.second.displayName} · ${selectedCompaction.first.name}"
    }
    val presets = listOf<Int?>(null, 128_000, 256_000, 512_000, 1_000_000)
    LaunchedEffect(conversationId) {
        selected = ConversationExtensionRepository.contextWindowOverride(context, conversationId)
        compactionBinding = ConversationExtensionRepository.compactionBinding(context, conversationId)
        customText = selected?.toString().orEmpty()
        loaded = true
    }
    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = "本会话上下文长度",
        summary = "长按上下文圆圈可单独设置；默认跟随模型配置。",
        onDismissRequest = { if (!saving) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!loaded) {
                Text("正在读取当前设置…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                presets.forEach { value ->
                    TextButton(
                        text = value?.let { "${it / 1_000}K tokens" } ?: "跟随模型默认",
                        onClick = { selected = value; customText = value?.toString().orEmpty() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextField(
                    value = customText,
                    onValueChange = { value ->
                        customText = value.filter(Char::isDigit).take(8)
                        selected = customText.toIntOrNull()
                    },
                    label = "自定义长度（tokens，最少 1K）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = rangeText,
                    onValueChange = { rangeText = it.filter { c -> c.isDigit() || c == '-' }.take(32) },
                    label = "手动压缩范围（历史索引，例如 0-8；留空为自动）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "本会话压缩模型：$compactionLabel",
                    onClick = {
                        choosingCompaction = !choosingCompaction
                        if (!choosingCompaction) chooserProviderId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (choosingCompaction) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TextButton(
                            text = "跟随全局",
                            onClick = {
                                compactionBinding = null
                                chooserProviderId = null
                                choosingCompaction = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val activeProvider = chooserProviderId?.let { id ->
                            compactionProviders.firstOrNull { it.id == id }
                        }
                        if (activeProvider == null) {
                            compactionProviders.forEach { provider ->
                                TextButton(
                                    text = "${provider.name} · ${provider.models.count { it.isEnabled }} 个模型",
                                    onClick = { chooserProviderId = provider.id },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            TextButton(
                                text = "← ${activeProvider.name}",
                                onClick = { chooserProviderId = null },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            activeProvider.models.filter { it.isEnabled }.forEach { model ->
                                TextButton(
                                    text = model.displayName,
                                    onClick = {
                                        compactionBinding = ModelRoleBinding(model.id, activeProvider.id)
                                        chooserProviderId = null
                                        choosingCompaction = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                Text("可选历史消息：0–${(historySize - 1).coerceAtLeast(0)}。系统提示和未完成工具链会自动保护。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            MiuixDialogActions(
                confirmText = if (saving) "保存中…" else "保存",
                confirmEnabled = loaded && !saving,
                onCancel = { if (!saving) onDismiss() },
                onConfirm = {
                    val requested = customText.toIntOrNull()
                    if (customText.isNotBlank() && (requested == null || requested !in 1_024..16_000_000)) {
                        Toast.makeText(context, "上下文长度需在 1,024–16,000,000 tokens 之间", Toast.LENGTH_SHORT).show()
                        return@MiuixDialogActions
                    }
                    saving = true
                    scope.launch {
                        runCatching {
                            ConversationExtensionRepository.setContextWindowOverride(
                                context,
                                conversationId,
                                selected,
                            )
                            ConversationExtensionRepository.setCompactionBinding(
                                context,
                                conversationId,
                                compactionBinding,
                            )
                        }.onSuccess {
                            val selection = rangeText.trim().takeIf { it.isNotBlank() }?.let { raw ->
                                val parts = raw.split('-', limit = 2)
                                val start = parts.getOrNull(0)?.toIntOrNull()
                                val end = parts.getOrNull(1)?.toIntOrNull()
                                if (start == null || end == null || start < 0 || end < start || end >= historySize) {
                                    Toast.makeText(context, "范围格式或索引无效", Toast.LENGTH_SHORT).show()
                                    null
                                } else start..end
                            }
                            if (rangeText.isBlank() || selection != null) onCompactSelection(selection)
                            onDismiss()
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                "保存失败：${error.message ?: error.javaClass.simpleName}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        saving = false
                    }
                },
            )
        }
    }
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)
