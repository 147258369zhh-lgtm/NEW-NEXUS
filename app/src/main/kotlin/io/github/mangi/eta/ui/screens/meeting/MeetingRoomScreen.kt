package io.github.mangi.eta.ui.screens.meeting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingComposerState
import io.github.mangi.eta.fork.meeting.MeetingMessageEntity
import io.github.mangi.eta.fork.meeting.MeetingMentionRouter
import io.github.mangi.eta.fork.meeting.MeetingParticipantEntity
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomUiState
import io.github.mangi.eta.fork.meeting.MeetingRoutingDecision
import io.github.mangi.eta.fork.meeting.MeetingRunCoordinator
import io.github.mangi.eta.fork.meeting.MeetingSettingsStore
import io.github.mangi.eta.fork.meeting.MeetingTaskEntity
import io.github.mangi.eta.fork.meeting.MeetingTaskStatus
import io.github.mangi.eta.fork.meeting.toUiState
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ModelRoleBinding
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ConversationStage
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.components.AgentIdentityAvatar
import io.github.mangi.eta.ui.components.AgentIdentityPalette
import io.github.mangi.eta.ui.components.AgentTimelineEntry
import io.github.mangi.eta.ui.components.toTimelineEntries
import io.github.mangi.eta.ui.components.ConversationTextEditor
import io.github.mangi.eta.ui.components.ConversationDraftFeedback
import io.github.mangi.eta.ui.components.ConversationEditorState
import io.github.mangi.eta.ui.components.ConversationDraftStatus
import io.github.mangi.eta.ui.components.rememberConversationAttachments
import io.github.mangi.eta.ui.components.ConversationAttachmentEditor
import io.github.mangi.eta.ui.components.ConversationAttachmentFeedback
import io.github.mangi.eta.ui.components.AgentAttachmentPickerButton
import io.github.mangi.eta.ui.components.PendingImageStrip
import io.github.mangi.eta.ui.components.PendingFileReferenceStrip
import io.github.mangi.eta.ui.components.ConversationComposerSurface
import io.github.mangi.eta.ui.components.LocalNexusBackdrop
import io.github.mangi.eta.ui.components.rememberGlassRenderPolicy
import io.github.mangi.eta.ui.components.NexusSettingsTheme
import io.github.mangi.eta.ui.components.NexusSettingsCard
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import io.github.mangi.eta.ui.components.rememberConversationEditor
import androidx.compose.ui.text.TextRange
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusIdle
import io.github.mangi.eta.ui.components.StatusRunning
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private data class TimelineProjectionInput(
    val state: MeetingRoomUiState,
    val previewLines: Int,
    val toolInterrupted: String,
    val sensitiveTool: String,
    val streamPreview: String,
    val streamInterrupted: String,
)

@Composable
internal fun MeetingRoomScreen(
    roomId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onArchived: () -> Unit = onBack,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by remember(context, roomId) {
        MeetingRepository.snapshot(context, roomId).map { it?.toUiState() }.flowOn(Dispatchers.Default)
    }
        .collectAsState(initial = null)
    val state = snapshot?.takeIf { it.room.id == roomId }

    MiuixScaffold(
        title = state?.room?.title ?: stringResource(R.string.meeting_room_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            state?.let {
                MeetingStatusBadge(status = it.room.status)
            }
        },
    ) { padding, _, sidePadding ->
        if (state == null) {
            MeetingRoomLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = sidePadding),
            )
        } else {
            ConversationStage(ConversationSubject.meeting(roomId), onDshCommand = { name, args ->
                if (name == "stop" && args.isBlank()) { MeetingRunCoordinator.stop(roomId); true } else false
            }) {
                MeetingRoomContent(
                    state = state,
                    onDeleted = onDeleted,
                    onArchived = onArchived,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .padding(horizontal = sidePadding)
                        .imePadding(),
                )
            }
        }
    }
}

/** Main-chat meeting stage. It deliberately has no scaffold so Home owns navigation and the side pane. */
@Composable
internal fun MeetingRoomConversationStage(
    roomId: String,
    onDeleted: () -> Unit = {},
    onArchived: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by remember(context, roomId) {
        MeetingRepository.snapshot(context, roomId).map { it?.toUiState() }.flowOn(Dispatchers.Default)
    }
        .collectAsState(initial = null)
    val state = snapshot?.takeIf { it.room.id == roomId }
    if (state == null) {
        MeetingRoomLoading(modifier = modifier.fillMaxSize())
    } else {
        ConversationStage(ConversationSubject.meeting(roomId), onDshCommand = { name, args ->
            if (name == "stop" && args.isBlank()) { MeetingRunCoordinator.stop(roomId); true } else false
        }) {
            MeetingRoomContent(
                state = state,
                onDeleted = onDeleted,
                onArchived = onArchived,
                onOpenSettings = onOpenSettings,
                modifier = modifier
                    .fillMaxSize()
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun MeetingRoomContent(
    state: MeetingRoomUiState,
    onDeleted: () -> Unit,
    onArchived: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val settingsStore = remember(context.applicationContext) {
        MeetingSettingsStore.get(context.applicationContext)
    }
    val meetingSettings by settingsStore.settings.collectAsState()
    val editor = rememberConversationEditor(ConversationSubject.meeting(state.room.id))
    val attachments = rememberConversationAttachments(ConversationSubject.meeting(state.room.id))
    val input = editor.field.text.toString()
    var selectedParticipantName by rememberSaveable(state.room.id) { mutableStateOf<String?>(null) }
    var showParticipantDetails by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var keepBottomAnchored by rememberSaveable(state.room.id) { mutableStateOf(true) }
    var followAfterSortIndex by remember(state.room.id) { mutableStateOf<Int?>(null) }
    var managementOpen by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var exportTitle by rememberSaveable(state.room.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val title = exportTitle
        exportTitle = null
        if (uri == null || title == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = MeetingRepository.snapshot(context, state.room.id).first()
                        ?: error("会议室不存在")
                    val markdown = MeetingMarkdownExporter.export(snapshot)
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("无法打开导出文件")
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                Toast.makeText(context, "会议室已导出", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "导出失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val activeMention = if (editor.field.selection.collapsed) {
        MeetingComposerState.activeMention(input, editor.field.selection.end)
    } else null
    var submitting by remember(state.room.id) { mutableStateOf(false) }
    val mentionMatches = remember(activeMention?.query, state.mentionCandidates) {
        val query = activeMention?.query.orEmpty()
        state.mentionCandidates.filter { participant ->
            query.isBlank() || participant.profileName.contains(query, ignoreCase = true) ||
                participant.displayName.contains(query, ignoreCase = true)
        }
    }
    val toolInterruptedDetail = stringResource(R.string.meeting_tool_interrupted)
    val sensitiveToolDetail = stringResource(R.string.meeting_tool_sensitive_hidden)
    val streamPreviewDetail = stringResource(R.string.meeting_stream_preview_truncated)
    val streamInterruptedDetail = stringResource(R.string.meeting_stream_interrupted)
    var timeline by remember(state.room.id) { mutableStateOf<MeetingConversationTimeline?>(null) }
    val projectionInput by rememberUpdatedState(TimelineProjectionInput(
        state, meetingSettings.summaryPreviewLines, toolInterruptedDetail, sensitiveToolDetail,
        streamPreviewDetail, streamInterruptedDetail,
    ))
    LaunchedEffect(state.room.id) {
        var previous: MeetingConversationTimeline? = null
        val metadataCache = MeetingTimelineMetadataCache()
        // Finish one projection at a time and keep only the newest pending update.
        snapshotFlow { projectionInput }.conflate().collect { input ->
            val projection = withContext(Dispatchers.Default) {
                val projectionContext = currentCoroutineContext()
                input.state.conversationTimeline(input.previewLines, input.toolInterrupted, input.sensitiveTool,
                    input.streamPreview, input.streamInterrupted, previous = previous, metadataCache = metadataCache,
                    checkCancelled = { projectionContext.ensureActive() })
            }
            previous = projection
            timeline = projection
        }
    }
    val isRunning = state.isRunning
    val routing = remember(input, state.mentionCandidates, state.host, meetingSettings.maxParallelParticipants) {
        input.takeIf(String::isNotBlank)?.let { text ->
            MeetingMentionRouter.route(
                text = text,
                availableProfiles = state.mentionCandidates.map(MeetingParticipantEntity::profileName),
                maxParticipants = meetingSettings.maxParallelParticipants,
                hostProfile = state.host?.profileName ?: state.room.hostProfile,
            )
        }
    }
    val selectedParticipant = state.participants.firstOrNull {
        it.profileName == selectedParticipantName
    } ?: state.host ?: state.participants.firstOrNull()

    LaunchedEffect(state.messages, followAfterSortIndex, timeline) {
        val after = followAfterSortIndex
        val currentTimeline = timeline
        val sentMessage = after?.let { sortIndex ->
            state.messages.lastOrNull { it.authorType == MeetingAuthorType.USER && it.sortIndex > sortIndex }
        }
        if (currentTimeline != null && sentMessage != null && currentTimeline.messages.any { it.id == sentMessage.id }) {
            keepBottomAnchored = true
            listState.requestScrollToItem(currentTimeline.messages.toTimelineEntries(workGroups = currentTimeline.identities.mapValues { it.value.workGroup }).size)
            followAfterSortIndex = null
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val glassPolicy = rememberGlassRenderPolicy()
    val sampleMessages = state.messages.isNotEmpty() && LocalEtaDesignTokens.current.skin.isGlass && glassPolicy.sampleBackdrop
    val messageBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    Column(modifier = modifier) {
        MeetingRoomOverview(
            state = state,
            selectedParticipant = selectedParticipant,
            onParticipantClick = { participant ->
                selectedParticipantName = participant.profileName
                showParticipantDetails = true
            },
            onOpenParticipantDetails = { showParticipantDetails = true },
            onOpenManagement = { managementOpen = true },
            onStop = {
                if (MeetingRunCoordinator.stop(state.room.id)) {
                    Toast.makeText(context, "正在停止本轮运行", Toast.LENGTH_SHORT).show()
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()
            .then(if (sampleMessages) Modifier.layerBackdrop(messageBackdrop) else Modifier)) {
            val currentTimeline = timeline
            if (currentTimeline == null) {
                MeetingRoomLoading(modifier = Modifier.align(Alignment.Center))
            } else if (currentTimeline.messages.isEmpty()) {
                ListEmptyState(
                    title = stringResource(R.string.meeting_room_no_messages),
                    summary = stringResource(R.string.meeting_room_topology_summary),
                    icon = Icons.Rounded.Groups,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AgentConversationMessages(
                    visibleMessages = currentTimeline.messages,
                    messageIdentities = currentTimeline.identities,
                    completedReplyMessageIds = currentTimeline.completedReplyIds,
                    allowMessageChanges = false,
                    scrollState = listState,
                    isStreaming = isRunning,
                    bottomInset = 0.dp,
                    keepBottomAnchored = keepBottomAnchored,
                    onBottomAnchorChanged = { keepBottomAnchored = it },
                    followIncomingMessages = meetingSettings.autoFollowMessages,
                    initiallyScrollToBottom = true,
                    onStopWaiting = { MeetingRunCoordinator.stop(state.room.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (activeMention != null && mentionMatches.isNotEmpty()) {
            MentionSuggestions(
                participants = mentionMatches,
                onSelect = { participant ->
                    val current = editor.field.text.toString()
                    val active = MeetingComposerState.activeMention(current, editor.field.selection.end)
                        ?: return@MentionSuggestions
                    val completed = MeetingComposerState.completeMention(current, active, participant.profileName)
                    editor.replace(completed, TextRange(active.replaceStart + MeetingMentionRouter.formatMention(participant.profileName).length + 1))
                },
            )
        }
        routing?.let { decision ->
            MeetingRoutePreview(
                decision = state.host?.let { MeetingMentionRouter.throughHost(decision, it.profileName, input) } ?: decision,
                maxParallelParticipants = meetingSettings.maxParallelParticipants,
                autoDispatchEnabled = meetingSettings.autoDispatchWithoutMention || decision !is MeetingRoutingDecision.HostDispatch,
                hostExplicitlyAddressed = decision !is MeetingRoutingDecision.HostDispatch,
            )
        }
        CompositionLocalProvider(LocalNexusBackdrop provides messageBackdrop.takeIf { sampleMessages }) {
        MeetingComposer(
            editor = editor,
            attachments = attachments,
            running = isRunning,
            canSend = routing !is MeetingRoutingDecision.UnknownMentions,
            onStop = { MeetingRunCoordinator.stop(state.room.id) },
            onSend = {
                val message = editor.field.text.toString().trim()
                if (!submitting && (message.isNotEmpty() || !attachments.value.isEmpty) && attachments.ready && attachments.pending == 0) {
                    val submitted = attachments.value
                    submitting = true
                    scope.launch {
                        try {
                            val accepted = if (isRunning) MeetingRunCoordinator.enqueue(context, state.room.id, message, submitted)
                                else MeetingRunCoordinator.start(context, state.room.id, message, submitted)
                            if (accepted) {
                                if (editor.field.text.toString().trim() == message) editor.replace("")
                                attachments.clearSubmitted(submitted)
                                followAfterSortIndex = state.messages.lastOrNull()?.sortIndex ?: -1
                                if (isRunning) Toast.makeText(context, R.string.meeting_queue_accepted, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.meeting_send_failed, Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            submitting = false
                        }
                    }
                }
            },
        )
        }
    }

    if (showParticipantDetails) selectedParticipant?.let { participant ->
        MeetingParticipantDialog(
            participant = participant,
            modelLabel = participant.modelDisplayLabel(providers),
            task = state.latestTaskFor(participant.profileName),
            latestActivity = state.latestActivityFor(participant.profileName),
            roomStatus = state.room.status,
            waiting = state.isParticipantWaiting(participant.profileName),
            onMention = if (participant.enabled) {
                {
                    showParticipantDetails = false
                    selectedParticipantName = null
                    editor.replace(if (input.isBlank()) {
                        "${MeetingMentionRouter.formatMention(participant.profileName)} "
                    } else {
                        input.trimEnd() + " ${MeetingMentionRouter.formatMention(participant.profileName)} "
                    })
                }
            } else {
                null
            },
            onDismiss = { showParticipantDetails = false },
        )
    }

    if (managementOpen) {
        MeetingRoomManagementDialog(
            roomTitle = state.room.title,
            onExport = {
                managementOpen = false
                exportTitle = state.room.title
                exportLauncher.launch(MeetingMarkdownExporter.defaultFileName(state.room.title))
            },
            onOpenSettings = { managementOpen = false; onOpenSettings() },
            onDismiss = { managementOpen = false },
        )
    }
}

@Composable
private fun MeetingRoomManagementDialog(
    roomTitle: String,
    onExport: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    NexusSettingsTheme {
        WindowDialog(show = true, title = roomTitle, onDismissRequest = onDismiss) {
            NexusSettingsCard {
                NexusSettingsArrowPreference(
                    title = "会议室参数设置",
                    startAction = { MeetingManageActionIcon(Icons.Rounded.Settings, MiuixTheme.colorScheme.primary) },
                    onClick = onOpenSettings,
                )
                NexusSettingsArrowPreference(
                    title = "导出完整会议记录",
                    startAction = { MeetingManageActionIcon(Icons.Rounded.Download, StatusSuccess) },
                    onClick = onExport,
                )
            }
        }
    }
}
@Composable
private fun MeetingManageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
internal fun MeetingRoomRenameDialog(
    roomId: String,
    currentTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(roomId) { mutableStateOf(currentTitle) }
    WindowDialog(
        show = true,
        title = "重命名会议室",
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = title,
                onValueChange = { title = it.take(120) },
                label = "会议室名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = "保存",
                confirmEnabled = title.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    val normalized = title.trim()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                MeetingRepository.renameRoom(context, roomId, normalized)
                            }
                        }.onSuccess {
                            onDismiss()
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                "重命名失败：${error.message ?: "未知错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun MeetingRoomDeleteDialog(
    roomId: String,
    roomTitle: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        title = "删除“$roomTitle”？",
        summary = "会议室的全部消息、Agent 任务和事件记录都会被删除，此操作无法撤销。建议先导出记录。",
        onDismissRequest = onDismiss,
    ) {
        MiuixDialogActions(
            confirmText = "删除",
            onCancel = onDismiss,
            onConfirm = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            MeetingRepository.deleteRoom(context, roomId)
                        }
                    }.onSuccess {
                        onDeleted()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "删除失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun MeetingRoomOverview(
    state: MeetingRoomUiState,
    selectedParticipant: MeetingParticipantEntity?,
    onParticipantClick: (MeetingParticipantEntity) -> Unit,
    onOpenParticipantDetails: () -> Unit,
    onOpenManagement: () -> Unit,
    onStop: () -> Unit,
) {
    val selectedTask = selectedParticipant?.let { state.latestTaskFor(it.profileName) }
    val selectedActivity = selectedParticipant?.let { state.latestActivityFor(it.profileName) }
    val completedTasks = state.latestRoundTasks.count { it.status == MeetingTaskStatus.COMPLETED }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 16.dp)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                cornerRadius = 16.dp,
            )
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeetingStatusDot(status = state.room.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meetingStatusLabel(state.room.status),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = meetingStatusHint(state),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (state.latestRoundTasks.isEmpty()) {
                    "${state.mentionCandidates.size} 位 Agent"
                } else {
                    "本轮 $completedTasks/${state.latestRoundTasks.size}"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = when {
                    state.isFailed -> MiuixTheme.colorScheme.error
                    state.isRunning -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            if (state.isRunning) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "停止本轮运行",
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onOpenManagement, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "会议室管理",
                    modifier = Modifier.size(19.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(state.participants, key = MeetingParticipantEntity::profileName) { participant ->
                ParticipantChip(
                    participant = participant,
                    selected = participant.profileName == selectedParticipant?.profileName,
                    task = state.latestTaskFor(participant.profileName),
                    roomStatus = state.room.status,
                    waiting = state.isParticipantWaiting(participant.profileName),
                    onClick = { onParticipantClick(participant) },
                )
            }
        }
        selectedParticipant?.let { participant ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .squircleSurface(MiuixTheme.colorScheme.surface.copy(alpha = 0.58f), 12.dp)
                    .clickable(onClick = onOpenParticipantDetails)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MeetingProcessDot(task = selectedTask, roomStatus = state.room.status, isHost = participant.isHost,
                    enabled = participant.enabled, waiting = state.isParticipantWaiting(participant.profileName))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${participant.displayName} · ${participantProcessLabel(participant, selectedTask, state.room.status, state.isParticipantWaiting(participant.profileName))}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = participantProcessPreview(participant, selectedTask, selectedActivity),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "查看进程",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MeetingAssignmentsDialog(
    state: MeetingRoomUiState,
    onOpenResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val participantsByProfile = remember(state.participants) { state.participants.associateBy { it.profileName } }
    val messageIds = remember(state.messages) { state.messages.mapTo(hashSetOf()) { it.id } }
    WindowDialog(show = true, title = stringResource(R.string.meeting_assignments), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.latestRoundTasks, key = { it.id }) { task ->
                val participant = participantsByProfile[task.participantProfile]
                val name = participant?.displayName ?: task.participantProfile
                val status = when (task.status) {
                    MeetingTaskStatus.QUEUED -> "排队中"
                    MeetingTaskStatus.RUNNING -> "执行中"
                    MeetingTaskStatus.COMPLETED -> "已完成"
                    MeetingTaskStatus.FAILED -> "执行失败"
                    MeetingTaskStatus.CANCELLED -> "已取消"
                    else -> task.status
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$name · $status", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                    Text(task.instruction.ifBlank { stringResource(R.string.meeting_no_instruction) },
                        style = MiuixTheme.textStyles.footnote1)
                    task.errorMessage?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                    }
                    task.resultMessageId?.takeIf { id -> id in messageIds }?.let { id ->
                        TextButton(text = stringResource(R.string.meeting_open_result), onClick = { onOpenResult(id) })
                    }
                }
            }
        }
        TextButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    }
}

@Composable
private fun ParticipantChip(
    participant: MeetingParticipantEntity,
    selected: Boolean,
    task: MeetingTaskEntity?,
    roomStatus: String,
    waiting: Boolean,
    onClick: () -> Unit,
) {
    val isHost = participant.isHost
    val background = when {
        selected -> MiuixTheme.colorScheme.primaryContainer
        isHost -> MiuixTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.surface
    }
    val foreground = when {
        selected -> MiuixTheme.colorScheme.onPrimaryContainer
        isHost -> MiuixTheme.colorScheme.onSecondaryContainer
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .squircleSurface(background, cornerRadius = 16.dp)
            .squircleBorder(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.38f)
                },
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeetingAgentAvatar(
            displayName = participant.displayName,
            identity = participant.profileName,
            task = task,
            roomStatus = roomStatus,
            isHost = isHost,
            enabled = participant.enabled,
            waiting = waiting,
            selected = selected,
            size = 22.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = participant.displayName,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected || isHost) FontWeight.SemiBold else FontWeight.Normal,
            color = foreground,
        )
        if (isHost) {
            Text(
                text = stringResource(R.string.meeting_room_moderator_badge),
                style = MiuixTheme.textStyles.footnote2,
                color = foreground.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

@Composable
private fun MeetingAgentAvatar(
    displayName: String,
    identity: String,
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
    enabled: Boolean = true,
    waiting: Boolean = false,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp,
) {
    val statusColor = if (!enabled) Color(0xFF8A9299) else if (waiting) Color(0xFFF28C18) else meetingProcessColor(task, roomStatus, isHost)
    val statusDescription = if (!enabled) "已停用" else if (waiting) "等待处理" else meetingProcessLabel(task, roomStatus, isHost)
    AgentIdentityAvatar(
        displayName = displayName,
        seed = identity,
        size = size,
        icon = null,
        singleInitial = true,
        statusColor = statusColor,
        statusDescription = statusDescription,
        selected = selected,
        palette = AgentIdentityPalette.MeetingVivid,
    )
}

private fun meetingStatusHint(state: MeetingRoomUiState): String = when {
    state.isRunning && state.completingTasks > 0 ->
        "${state.completingTasks} 项任务正在执行或排队"
    state.isRunning -> "主持人正在选择参与者或汇总本轮结果"
    state.isFailed -> "本轮未完成，可查看 Agent 进程后重试"
    state.isWaiting -> "本轮已结束，可继续发起下一轮"
    else -> "@ 一位直接执行，@ 多位并行执行"
}

private fun participantProcessLabel(
    participant: MeetingParticipantEntity,
    task: MeetingTaskEntity?,
    roomStatus: String,
    waiting: Boolean,
): String = when {
    !participant.enabled -> "已停用"
    waiting -> "等待处理"
    else -> when (task?.status) {
    MeetingTaskStatus.QUEUED -> "排队等待执行"
    MeetingTaskStatus.RUNNING -> "正在独立执行"
    MeetingTaskStatus.COMPLETED -> "本轮任务已完成"
    MeetingTaskStatus.FAILED -> "本轮任务失败"
    MeetingTaskStatus.CANCELLED -> "本轮任务已取消"
    else -> when {
        participant.isHost && roomStatus == MeetingRoomStatus.RUNNING -> "正在监督并协调本轮"
        participant.isHost && roomStatus == MeetingRoomStatus.FAILED -> "主持人进程已中断"
        participant.isHost -> "等待下一轮，持续监督"
        else -> "本轮尚未分派任务"
    }
    }
}

private fun participantProcessPreview(
    participant: MeetingParticipantEntity,
    task: MeetingTaskEntity?,
    activity: MeetingMessageEntity?,
): String {
    val value = when {
        !participant.enabled -> "该 Agent 已停用，不再参与新任务；历史进程仍可查看"
        task?.status == MeetingTaskStatus.FAILED -> task.errorMessage
        activity?.content?.isNotBlank() == true -> activity.content
        task?.instruction?.isNotBlank() == true -> task.instruction
        else -> participant.profileSummary
    }.orEmpty()
    return value.replace(Regex("\\s+"), " ").trim().ifBlank {
        if (participant.isHost) "负责选择参与者、监督并行任务并汇总结果" else "等待任务分派"
    }
}

@Composable
private fun MeetingProcessDot(task: MeetingTaskEntity?, roomStatus: String, isHost: Boolean, enabled: Boolean, waiting: Boolean) {
    val color = if (!enabled) Color(0xFF8A9299) else if (waiting) Color(0xFFF28C18) else meetingProcessColor(task, roomStatus, isHost)
    Box(
        modifier = Modifier
            .size(7.dp)
            .squircleSurface(color, 7.dp)
            .semantics { contentDescription = if (!enabled) "已停用" else if (waiting) "等待处理" else meetingProcessLabel(task, roomStatus, isHost) },
    )
}

private fun meetingProcessLabel(
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
): String = when (task?.status) {
    MeetingTaskStatus.COMPLETED -> "已完成"
    MeetingTaskStatus.FAILED -> "执行失败"
    MeetingTaskStatus.CANCELLED -> "已取消"
    MeetingTaskStatus.QUEUED -> "排队中"
    MeetingTaskStatus.RUNNING -> "执行中"
    else -> when {
        isHost && roomStatus == MeetingRoomStatus.FAILED -> "主持人进程已中断"
        isHost && roomStatus == MeetingRoomStatus.RUNNING -> "主持人正在协调"
        else -> "空闲"
    }
}

@Composable
private fun taskStatusColor(status: String) = when (status) {
    MeetingTaskStatus.COMPLETED -> StatusSuccess
    MeetingTaskStatus.FAILED -> StatusError
    MeetingTaskStatus.CANCELLED -> StatusIdle
    MeetingTaskStatus.QUEUED -> StatusWarning
    MeetingTaskStatus.RUNNING -> StatusRunning
    else -> StatusIdle
}

@Composable
private fun meetingProcessColor(
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
): Color = when (task?.status) {
    MeetingTaskStatus.COMPLETED -> Color(0xFF8A9299)
    MeetingTaskStatus.FAILED -> Color(0xFFE43D45)
    MeetingTaskStatus.CANCELLED -> Color(0xFF8A9299)
    MeetingTaskStatus.QUEUED -> Color(0xFFF28C18)
    MeetingTaskStatus.RUNNING -> Color(0xFF18A957)
    else -> when {
        isHost && roomStatus == MeetingRoomStatus.FAILED -> Color(0xFFE43D45)
        isHost && roomStatus == MeetingRoomStatus.RUNNING -> Color(0xFF18A957)
        else -> Color(0xFF8A9299)
    }
}

@Composable
private fun MeetingTaskPill(task: MeetingTaskEntity) {
    val color = taskStatusColor(task.status)
    Row(
        modifier = Modifier
            .squircleSurface(MiuixTheme.colorScheme.surface.copy(alpha = 0.62f), 11.dp)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .squircleSurface(color, 6.dp),
        )
        Text(
            text = task.participantProfile,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = when (task.status) {
                MeetingTaskStatus.QUEUED -> "排队"
                MeetingTaskStatus.RUNNING -> "执行中"
                MeetingTaskStatus.COMPLETED -> "完成"
                MeetingTaskStatus.FAILED -> "失败"
                MeetingTaskStatus.CANCELLED -> "取消"
                else -> task.status
            },
            style = MiuixTheme.textStyles.footnote2,
            color = color,
        )
    }
}

@Composable
private fun MeetingStatusDot(status: String) {
    val running = status == MeetingRoomStatus.RUNNING
    val alpha = if (running) rememberInfiniteTransition(label = "meeting_status").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "meeting_status_alpha",
    ) else null
    val color = when (status) {
        MeetingRoomStatus.FAILED -> MiuixTheme.colorScheme.error
        MeetingRoomStatus.RUNNING -> MiuixTheme.colorScheme.primary
        MeetingRoomStatus.WAITING_FOR_USER -> MiuixTheme.colorScheme.onTertiaryContainer
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha?.value ?: 1f }
            .squircleSurface(color, 8.dp),
    )
}

@Composable
private fun MeetingStatusBadge(status: String) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 14.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MeetingStatusDot(status)
        Text(
            text = meetingStatusLabel(status),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MentionSuggestions(
    participants: List<MeetingParticipantEntity>,
    onSelect: (MeetingParticipantEntity) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(participants, key = MeetingParticipantEntity::profileName) { participant ->
            EtaChoiceChip(
                text = "@${participant.profileName}",
                selected = false,
                onClick = { onSelect(participant) },
            )
        }
    }
}

@Composable
private fun MeetingRoutePreview(
    decision: MeetingRoutingDecision,
    maxParallelParticipants: Int,
    autoDispatchEnabled: Boolean,
    hostExplicitlyAddressed: Boolean,
) {
    val unknown = decision is MeetingRoutingDecision.UnknownMentions
    val hostBlocked = decision is MeetingRoutingDecision.HostDispatch && !autoDispatchEnabled
    val warning = unknown || hostBlocked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .squircleSurface(
                if (warning) MiuixTheme.colorScheme.errorContainer
                else MiuixTheme.colorScheme.surfaceContainer,
                11.dp,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = if (warning) Icons.Rounded.Warning else Icons.AutoMirrored.Rounded.Send,
            contentDescription = null,
            tint = if (warning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = when (decision) {
                is MeetingRoutingDecision.Direct -> "直接执行 · @${decision.participant}"
                is MeetingRoutingDecision.Parallel ->
                    "独立并行 · ${decision.participants.joinToString(" + ") { "@$it" }}"
                is MeetingRoutingDecision.HostDispatch ->
                    if (autoDispatchEnabled) {
                        "${if (hostExplicitlyAddressed) "主持人协调" else "未指定参与者"} · 主持人将选择 1–$maxParallelParticipants 位 Agent"
                    } else {
                        "当前设置要求明确执行者 · 请输入 @Agent"
                    }
                is MeetingRoutingDecision.UnknownMentions ->
                    "无法发送 · 未识别 ${decision.mentions.joinToString(" ", transform = MeetingMentionRouter::describeUnknown)}"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = if (warning) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MeetingComposer(
    editor: ConversationEditorState,
    attachments: ConversationAttachmentEditor,
    running: Boolean,
    canSend: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
) {
    val draftStatus by editor.status.collectAsState()
    var anchorTop by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val maxHeight = with(density) { anchorTop.toDp() }.coerceAtLeast(200.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        ConversationDraftFeedback(editor)
        if (running) Text(stringResource(R.string.meeting_queue_stop_hint),
            modifier = Modifier.padding(bottom = 4.dp), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        io.github.mangi.eta.ui.screens.capability.DshComposerPlugins(generating = running)
        ConversationAttachmentFeedback(attachments)
        if (attachments.value.images.isNotEmpty()) PendingImageStrip(attachments.value.images, attachments::removeImage,
            Modifier.padding(vertical = 4.dp))
        if (attachments.value.files.isNotEmpty()) PendingFileReferenceStrip(attachments.value.files, attachments::removeFile,
            Modifier.padding(vertical = 4.dp))
        ConversationComposerSurface(Modifier.onGloballyPositioned { anchorTop = it.positionInWindow().y.toInt() }) {
            ConversationTextEditor(
                state = editor.field,
                onUserEdit = editor::onUserEdit,
                placeholder = stringResource(if (running) R.string.meeting_room_queue_hint else R.string.meeting_room_mention_hint),
                maxLines = 5,
                maxLength = 32_000,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (attachments.ready) AgentAttachmentPickerButton(
                    popupAnchorTopPx = anchorTop,
                    popupMaxHeight = maxHeight,
                    onAttachImage = attachments::image,
                    onAttachFiles = attachments::files,
                    onAttachFolder = attachments::folder,
                    onAttachFilePath = attachments::path,
                    showSkillPicker = false,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.weight(1f))
                if (running) {
                    IconButton(onClick = onStop, minWidth = 40.dp, minHeight = 40.dp) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                }
                val sendEnabled = canSend &&
                    (editor.field.text.isNotBlank() || !attachments.value.isEmpty) &&
                    attachments.ready && attachments.pending == 0 &&
                    (draftStatus == ConversationDraftStatus.READY || draftStatus == ConversationDraftStatus.SAVE_FAILED)
                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (sendEnabled) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surfaceContainerHigh,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = if (running) "排队发送" else stringResource(R.string.meeting_room_send),
                            modifier = Modifier.size(17.dp),
                            tint = if (sendEnabled) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingParticipantDialog(
    participant: MeetingParticipantEntity,
    modelLabel: String,
    task: MeetingTaskEntity?,
    latestActivity: MeetingMessageEntity?,
    roomStatus: String,
    waiting: Boolean,
    onMention: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = participant.displayName,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MeetingAgentAvatar(participant.displayName, participant.profileName, task, roomStatus,
                    isHost = participant.isHost, enabled = participant.enabled, waiting = waiting, size = 42.dp)
                Column {
                    Text(participant.displayName, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(if (participant.isHost) "主持人" else "会议参与者", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            ParticipantDetailRow("Profile", "@${participant.profileName}")
            ParticipantDetailRow("模型", modelLabel)
            ParticipantDetailRow(
                "执行进程",
                participantProcessLabel(participant, task, roomStatus, waiting),
            )
            if (!participant.enabled) {
                ParticipantDetailRow("状态", "已停用，不接收新任务；历史进程仍保留")
            }
            task?.let { currentTask ->
                ParticipantDetailRow("本轮指令", currentTask.instruction.take(6_000))
                currentTask.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                    ParticipantDetailRow("错误", error)
                }
            }
            latestActivity?.content?.takeIf(String::isNotBlank)?.let { output ->
                ParticipantDetailRow(
                    if (participant.isHost) "最新汇总" else "最新结果",
                    output.take(8_000),
                )
            }
            if (participant.profileSummary.isNotBlank()) {
                ParticipantDetailRow("职责", participant.profileSummary)
            }
          }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = if (onMention == null) "当前已停用" else "加入 @ 指令",
                    onClick = onMention ?: {},
                    enabled = onMention != null,
                )
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ParticipantDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun MeetingParticipantEntity.modelDisplayLabel(providers: List<ProviderSetting>): String {
    val modelId = modelId?.trim().orEmpty()
    if (modelId.isEmpty()) return "自动选择 · Profile → 全局 Subagent → 主模型"
    val provider = if (providerId != null) {
        providers.firstOrNull { it.id == providerId }
    } else {
        providers.firstOrNull { candidate -> candidate.models.any { it.id == modelId } }
    }
    val model = provider?.models?.firstOrNull { it.id == modelId }
    return when {
        provider != null && model != null -> "${provider.name} · ${model.displayName.ifBlank { model.modelId }}"
        providerId == null -> providers.firstNotNullOfOrNull { candidate ->
            candidate.models.firstOrNull { it.id == modelId }?.let { "${candidate.name} · ${it.displayName.ifBlank { it.modelId }}" }
        } ?: modelId
        else -> "指定模型当前不可用 · $modelId"
    }
}

@Composable
private fun MeetingRoomLoading(modifier: Modifier = Modifier) {
    ListEmptyState(
        title = stringResource(R.string.meeting_room_loading),
        icon = Icons.Rounded.Groups,
        modifier = modifier,
    )
}
