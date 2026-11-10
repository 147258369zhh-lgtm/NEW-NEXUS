package io.github.mangi.eta.ui.components

import android.content.ClipData
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsBar
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.ui.screens.meeting.MeetingRoomCreateDialog
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

/**
 * 会话侧边栏尺寸标尺。
 *
 * 外层水平边距（12.dp）+ 行内水平边距（16.dp）= 28.dp，与 Miuix 原生 [SmallTitle] 的默认内边距
 * 及 [InputField] 内部搜索图标起点完全对齐，保证搜索框、分组标题与对话标题处于同一条垂直基准线。
 */
private object ConversationPanelMetrics {
    val PaneHorizontalPadding = 12.dp
    val TopInset = 8.dp
    val AfterActionBar = 8.dp
    val BottomInset = 4.dp
    val ActionIconSize = 20.dp
    val FirstSectionTopPadding = 8.dp
    val SectionTopPadding = 16.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 48.dp
    val RowGap = 3.dp
    val RowCornerRadius = 14.dp
    val RowHorizontalPadding = 16.dp
    val RowVerticalPadding = 7.dp
    val PinSwipeThreshold = 64.dp
    val PinSwipeMaxReveal = 112.dp
    val DeleteSwipeThreshold = 64.dp
    val DeleteSwipeMaxReveal = 100.dp
    val PinSwipeIconInset = 20.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val EmptyVerticalPadding = 28.dp
    val DockVerticalPadding = 4.dp
    val DockEntryCornerRadius = 12.dp
    val DockEntryIconSize = 20.dp
    const val SettleStiffness = 146f
}

private val CollapsedFolderIdsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 抬高一档到 surfaceContainer，保证两侧始终可分。浅色维持 surface，避免改变现有观感。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) {
        colors.surfaceContainer
    } else {
        colors.surfaceContainerHigh
    }
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
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
    onPaneModeChange: (showMeetings: Boolean, meetingRoomId: String?) -> Unit,
    meetingArchiveViewOpen: Boolean = false,
    onMeetingArchiveViewChange: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    showMeetingsByDefault: Boolean = false,
    activeMeetingRoomId: String? = null,
    modifier: Modifier = Modifier,
) {
    val paneMode = if (showMeetingsByDefault) ConversationPaneMode.Meetings else ConversationPaneMode.Chats
    var showCreateMeeting by rememberSaveable { mutableStateOf(false) }
    var meetingQuery by rememberSaveable { mutableStateOf("") }
    var collapsedFolderIds by rememberSaveable(stateSaver = CollapsedFolderIdsSaver) {
        mutableStateOf(emptySet<String>())
    }
    val context = LocalContext.current
    val meetingRooms by MeetingRepository.rooms(context).collectAsState(initial = emptyList())
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val displayedConversations = if (state.archiveViewOpen) {
        state.archivedConversations
    } else {
        state.conversations
    }
    val groups = remember(displayedConversations, state.folders, state.archiveViewOpen, query) {
        displayedConversations.groupForDrawer(
            folders = state.folders,
            archived = state.archiveViewOpen,
            searching = query.isNotBlank(),
        )
    }
    val filteredMeetingRooms = remember(
        meetingRooms,
        meetingQuery,
        meetingArchiveViewOpen,
    ) {
        val normalized = meetingQuery.trim()
        meetingRooms
            .filter { room -> (room.archivedAt != null) == meetingArchiveViewOpen }
            .filter { room ->
                normalized.isEmpty() ||
                    room.title.contains(normalized, ignoreCase = true) ||
                    room.hostProfile.contains(normalized, ignoreCase = true)
            }
    }
    LaunchedEffect(state.archiveViewOpen) {
        listState.scrollToItem(0)
    }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        color = conversationPaneContainerColor(),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        // 搜索与工具条占据独立布局空间，列表只在中间视口内滚动和回弹。
        Column(modifier = Modifier.fillMaxSize()) {
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    Spacer(Modifier.height(ConversationPanelMetrics.TopInset))
                    Spacer(Modifier.height(4.dp))
                    if (paneMode == ConversationPaneMode.Chats) {
                        PaneActionBar(
                            query = state.searchQuery,
                            archiveViewOpen = state.archiveViewOpen,
                            onSearchChange = onSearchChange,
                            onCreateFolder = onCreateFolder,
                            onToggleArchive = { onArchiveViewChange(!state.archiveViewOpen) },
                        )
                    } else {
                        MeetingPaneActionBar(
                            query = meetingQuery,
                            archiveViewOpen = meetingArchiveViewOpen,
                            onSearchChange = { meetingQuery = it },
                            onToggleArchive = {
                                onMeetingArchiveViewChange(!meetingArchiveViewOpen)
                            },
                            onCreateMeeting = { showCreateMeeting = true },
                        )
                    }
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                }
            }
            if (paneMode == ConversationPaneMode.Chats) {
                LazyColumn(
                    state = listState,
                    modifier = conversationPaneListModifier().weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                    overscrollEffect = null,
                ) {
                    if (groups.isEmpty()) {
                        item {
                            EmptyConversations(
                                isSearching = query.isNotBlank(),
                                isArchive = state.archiveViewOpen,
                            )
                        }
                    } else {
                        groups.forEachIndexed { groupIndex, group ->
                            val folder = (group.section as? ConversationDrawerSection.Folder)?.folder
                            if (folder != null) {
                                item(key = "folder-${folder.id}") {
                                    ConversationFolderGroup(
                                        group = group,
                                        collapsedFolderIds = collapsedFolderIds,
                                        forceExpanded = query.isNotBlank(),
                                        selectedConversationId = state.selectedConversationId,
                                        archived = state.archiveViewOpen,
                                        onToggleFolder = { id ->
                                            collapsedFolderIds = if (id in collapsedFolderIds) collapsedFolderIds - id else collapsedFolderIds + id
                                        },
                                        onFolderActions = onRenameFolder,
                                        onConversationSelected = onConversationSelected,
                                        onConversationTogglePin = onConversationTogglePin,
                                        onConversationRename = onConversationRename,
                                        onConversationExport = onConversationExport,
                                        onConversationDelete = onConversationDelete,
                                        onConversationArchive = onConversationArchive,
                                        onConversationRestore = onConversationRestore,
                                        onConversationMoveToFolder = onConversationMoveToFolder,
                                        onConversationDropToFolder = onConversationDropToFolder,
                                    )
                                }
                            } else {
                                item(key = "section-${group.section}") {
                                    ConversationSectionHeader(
                                        group = group,
                                        isFirstSection = groupIndex == 0,
                                        onRenameFolder = onRenameFolder,
                                    )
                                }
                                items(
                                    items = group.items,
                                    key = { it.id },
                                ) { conversation ->
                                    ConversationTextRow(
                                        conversation = conversation,
                                        selected = conversation.id == state.selectedConversationId,
                                        onClick = { onConversationSelected(conversation.id) },
                                        onTogglePin = { onConversationTogglePin(conversation) },
                                        onRename = { onConversationRename(conversation) },
                                        onExport = { onConversationExport(conversation) },
                                        onDelete = { onConversationDelete(conversation) },
                                        onArchive = { onConversationArchive(conversation) },
                                        onRestore = { onConversationRestore(conversation) },
                                        onMoveToFolder = { onConversationMoveToFolder(conversation) },
                                        archived = state.archiveViewOpen,
                                        hasBranchChildren = group.items.any { it.branchParentConversationId == conversation.id },
                                        isBranchChild = conversation.branchParentConversationId in group.items.map { it.id },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = conversationPaneListModifier().weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    overscrollEffect = null,
                ) {
                    if (filteredMeetingRooms.isEmpty()) {
                        item(key = "meeting_empty") {
                            ListEmptyState(
                                title = when {
                                    meetingQuery.isNotBlank() -> "没有匹配的会议室"
                                    meetingArchiveViewOpen -> "还没有归档会议室"
                                    else -> "还没有会议室"
                                },
                                summary = if (meetingQuery.isNotBlank()) {
                                    "换一个名称或主持人搜索。"
                                } else if (meetingArchiveViewOpen) {
                                    "归档的会议室会保留完整记录，并可随时恢复或导出。"
                                } else {
                                    "创建一个会议室，让主持人和多个 Agent 协同工作。"
                                },
                                icon = Icons.Rounded.Groups,
                                action = if (meetingQuery.isBlank() && !meetingArchiveViewOpen) {
                                    { PaneCreateMeetingAction { showCreateMeeting = true } }
                                } else {
                                    null
                                },
                            )
                        }
                    } else {
                        item(key = "meeting_heading") {
                            SmallTitle(
                                if (meetingArchiveViewOpen) {
                                    "已归档 · ${filteredMeetingRooms.size}"
                                } else {
                                    "会议室 · ${filteredMeetingRooms.size}"
                                },
                            )
                        }
                        items(filteredMeetingRooms, key = { "meeting:${it.id}" }) { room ->
                            MeetingRoomPaneRow(
                                room = room,
                                selected = room.id == activeMeetingRoomId,
                                archived = meetingArchiveViewOpen,
                                onClick = { onOpenMeetingRoom(room.id) },
                                onRename = { onMeetingRoomRename(room) },
                                onTogglePin = { onMeetingRoomTogglePin(room) },
                                onExport = { onMeetingRoomExport(room) },
                                onDelete = { onMeetingRoomDelete(room) },
                                onArchiveChange = { archivedNow ->
                                    onMeetingRoomArchiveChange(room, archivedNow)
                                },
                            )
                        }
                    }
                }
            }
            PaneFixedRegion {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = ConversationPanelMetrics.RowHorizontalPadding),
                        thickness = 0.5.dp,
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.DockVerticalPadding))
                    PaneDock(
                        mode = paneMode,
                        onModeChange = { next ->
                            if (next == ConversationPaneMode.Meetings) {
                                val targetRoomId = activeMeetingRoomId
                                    ?: meetingRooms.firstOrNull { it.archivedAt == null }?.id
                                if (targetRoomId == null) onPaneModeChange(true, null)
                                else {
                                    if (state.archiveViewOpen) onArchiveViewChange(false)
                                    onMeetingArchiveViewChange(false)
                                    onPaneModeChange(true, targetRoomId)
                                }
                            } else {
                                onPaneModeChange(false, null)
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(ConversationPanelMetrics.BottomInset))
                }
            }
        }
    }
    if (showCreateMeeting) {
        MeetingRoomCreateDialog(
            onDismiss = { showCreateMeeting = false },
            onCreated = { roomId ->
                showCreateMeeting = false
                onOpenMeetingRoom(roomId)
            },
        )
    }
}

private enum class ConversationPaneMode { Chats, Meetings }

@Composable
private fun conversationPaneListModifier(): Modifier = Modifier
    .fillMaxWidth()
    .clipToBounds()
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
    .scrollEndHaptic()
    .overScrollVertical()

@Composable
private fun MeetingPaneActionBar(
    query: String,
    archiveViewOpen: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleArchive: () -> Unit,
    onCreateMeeting: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            modifier = Modifier.weight(1f).height(38.dp),
            insideMargin = DpSize.Zero,
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索会议室或主持人",
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
            },
            content = {},
        )
        Spacer(Modifier.width(8.dp))
        PaneActionButton(
            icon = if (archiveViewOpen) Icons.Outlined.Refresh else Icons.Outlined.Archive,
            contentDescription = if (archiveViewOpen) "返回会议室" else "查看已归档会议室",
            selected = archiveViewOpen,
            onClick = onToggleArchive,
        )
        Spacer(Modifier.width(6.dp))
        PaneActionButton(
            icon = Icons.Outlined.Add,
            contentDescription = "新建会议室",
            onClick = onCreateMeeting,
        )
    }
}

@Composable
private fun MeetingRoomPaneRow(
    room: MeetingRoomSummaryRow,
    selected: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onArchiveChange: (Boolean) -> Unit,
) {
    val running = room.status == MeetingRoomStatus.RUNNING
    val stale = System.currentTimeMillis() - room.updatedAt >= 7L * 24 * 60 * 60 * 1_000
    var showActionMenu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    var swipeOffset by remember(room.id) { mutableFloatStateOf(0f) }
    val pinThresholdPx = with(density) { ConversationPanelMetrics.PinSwipeThreshold.toPx() }
    val deleteThresholdPx = with(density) { ConversationPanelMetrics.DeleteSwipeThreshold.toPx() }
    val maxPinPx = with(density) { ConversationPanelMetrics.PinSwipeMaxReveal.toPx() }
    val maxDeletePx = with(density) { ConversationPanelMetrics.DeleteSwipeMaxReveal.toPx() }
    val currentOnTogglePin by rememberUpdatedState(onTogglePin)
    val currentOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(room.id, room.pinnedAt, selected) { swipeOffset = 0f }
    Box(
        modifier = Modifier.fillMaxWidth().then(
            if (archived) Modifier else Modifier.pointerInput(room.id, pinThresholdPx, deleteThresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = 0f
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        dragged = overSlop.coerceIn(-maxDeletePx, maxPinPx)
                    } ?: return@awaitEachGesture
                    swipeOffset = dragged
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == slop.id } ?: break
                        val delta = change.positionChange().x
                        if (delta != 0f) {
                            dragged = (swipeOffset + delta).coerceIn(-maxDeletePx, maxPinPx)
                            swipeOffset = dragged
                            change.consume()
                        }
                        if (!change.pressed) break
                    }
                    if (dragged >= pinThresholdPx) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnTogglePin()
                    } else if (dragged <= -deleteThresholdPx) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnDelete()
                    }
                    swipeOffset = 0f
                }
            },
        ),
    ) {
        Box(
            Modifier.matchParentSize()
                .alpha(if (swipeOffset == 0f) 0f else 1f)
                .squircleSurface(
                if (swipeOffset < 0f) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.tertiaryContainer,
                ConversationPanelMetrics.RowCornerRadius,
                ),
            contentAlignment = if (swipeOffset < 0f) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    if (swipeOffset < 0f) Icons.Outlined.Delete else Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (swipeOffset < 0f) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    if (swipeOffset < 0f) "删除会议室" else if (room.pinnedAt == null) "置顶会议室" else "取消置顶",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (swipeOffset < 0f) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .heightIn(min = 52.dp)
                .squircleSurface(
                    when {
                        selected -> MiuixTheme.colorScheme.primaryContainer
                        running -> MiuixTheme.colorScheme.secondaryContainer
                        archived -> MiuixTheme.colorScheme.surfaceContainerHigh
                        else -> MiuixTheme.colorScheme.surfaceContainerHigh
                    },
                    ConversationPanelMetrics.RowCornerRadius,
                )
                .squircleBorder(
                    width = .5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = if (selected) .28f else .12f),
                    cornerRadius = ConversationPanelMetrics.RowCornerRadius,
                )
                .clickable(onClickLabel = room.title, onClick = onClick)
                .padding(start = 14.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(7.dp).background(
                    when {
                        room.status == MeetingRoomStatus.FAILED -> MiuixTheme.colorScheme.error
                        running -> Color(0xFF2FA66A)
                        stale -> MiuixTheme.colorScheme.error
                        room.pinnedAt != null -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.outline.copy(alpha = .55f)
                    },
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .squircleSurface(
                        if (room.status == MeetingRoomStatus.FAILED) {
                            MiuixTheme.colorScheme.errorContainer
                        } else {
                            MiuixTheme.colorScheme.tertiaryContainer
                        },
                        12.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (room.status == MeetingRoomStatus.FAILED) {
                        MiuixTheme.colorScheme.onErrorContainer
                    } else {
                        MiuixTheme.colorScheme.onTertiaryContainer
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.title,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = if (selected || running) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (room.pinnedAt != null) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.padding(start = 4.dp).size(14.dp),
                            tint = if (selected) MiuixTheme.colorScheme.onPrimaryContainer
                            else MiuixTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "${room.hostProfile} 主持 · ${room.participantCount} 位 Agent",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (room.status) {
                        MeetingRoomStatus.RUNNING -> "讨论中"
                        MeetingRoomStatus.WAITING_FOR_USER -> "待你发言"
                        MeetingRoomStatus.FAILED -> "需处理"
                        else -> "空闲"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = when (room.status) {
                        MeetingRoomStatus.FAILED -> MiuixTheme.colorScheme.error
                        MeetingRoomStatus.RUNNING -> MiuixTheme.colorScheme.primary
                        MeetingRoomStatus.WAITING_FOR_USER -> MiuixTheme.colorScheme.onTertiaryContainer
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                IconButton(
                    onClick = { showActionMenu = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = "管理会议室",
                        modifier = Modifier.size(19.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
        WindowListPopup(
            show = showActionMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showActionMenu = false },
        ) {
            val renameText = "重命名"
            val pinText = if (room.pinnedAt == null) "置顶会议室" else "取消置顶"
            val exportText = "导出完整记录"
            val archiveText = if (archived) "恢复到会议室" else "归档会议室"
            val deleteText = "删除会议室"
            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val exportItem = remember(exportText) {
                DropdownItem(
                    text = exportText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val pinItem = remember(pinText) {
                DropdownItem(
                    text = pinText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = if (room.pinnedAt == null) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText, running) {
                DropdownItem(
                    text = deleteText,
                    enabled = !running,
                    summary = if (running) "本轮运行结束后可删除" else null,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val archiveItem = remember(archiveText, running, archived) {
                DropdownItem(
                    text = archiveText,
                    enabled = !running,
                    summary = if (running) "本轮运行结束后可操作" else null,
                    icon = { modifier ->
                        Icon(
                            imageVector = if (archived) Icons.Outlined.Refresh else Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = renameItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = pinItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onTogglePin()
                    },
                )
                DropdownImpl(
                    item = exportItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 2,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onExport()
                    },
                )
                DropdownImpl(
                    item = archiveItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 3,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        if (!running) onArchiveChange(!archived)
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 5,
                    isSelected = false,
                    index = 4,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        if (!running) onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun PaneCreateMeetingAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .squircleSurface(MiuixTheme.colorScheme.tertiaryContainer, 12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "新建会议室",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun PaneFixedRegion(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(conversationPaneContainerColor()),
    ) {
        content()
    }
}

@Composable
private fun PaneActionBar(
    query: String,
    archiveViewOpen: Boolean,
    onSearchChange: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            modifier = Modifier.weight(1f).height(38.dp),
            insideMargin = DpSize.Zero,
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.conversation_search_hint),
                    // 深色下侧栏已抬高到 surfaceContainer，搜索框需再高一档才能显出轮廓；
                    // 浅色的 surfaceContainerHigh 与 surfaceContainerHighest 相同，观感不变。
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
            },
            content = {},
        )
        Spacer(Modifier.width(8.dp))
        PaneActionButton(
            icon = if (archiveViewOpen) Icons.Outlined.Refresh else Icons.Outlined.Archive,
            contentDescription = stringResource(
                if (archiveViewOpen) R.string.conversation_archive_close_action else R.string.conversation_archive_open_action,
            ),
            selected = archiveViewOpen,
            onClick = onToggleArchive,
        )
        Spacer(Modifier.width(6.dp))
        PaneActionButton(
            icon = Icons.Outlined.Add,
            contentDescription = stringResource(R.string.conversation_folder_create_action),
            onClick = onCreateFolder,
        )
    }
}

@Composable
private fun PaneActionButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHighest
                },
                cornerRadius = ConversationPanelMetrics.DockEntryCornerRadius,
            )
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(ConversationPanelMetrics.ActionIconSize),
            tint = if (selected) {
                MiuixTheme.colorScheme.onTertiaryContainer
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            },
        )
    }
}

@Composable
private fun ConversationFolderGroup(
    group: ConversationDrawerGroup,
    collapsedFolderIds: Set<String>,
    forceExpanded: Boolean,
    selectedConversationId: String?,
    archived: Boolean,
    onToggleFolder: (String) -> Unit,
    onFolderActions: (ConversationFolderUi) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationArchive: (ConversationSummaryUi) -> Unit,
    onConversationRestore: (ConversationSummaryUi) -> Unit,
    onConversationMoveToFolder: (ConversationSummaryUi) -> Unit,
    onConversationDropToFolder: (conversationId: String, folderId: String) -> Unit,
) {
    val folder = (group.section as ConversationDrawerSection.Folder).folder
    val expanded = forceExpanded || folder.id !in collapsedFolderIds
    var dropActive by remember(folder.id) { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val onToggleExpanded = {
        if (!forceExpanded) {
            hapticFeedback.performHapticFeedback(if (expanded) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggleFolder(folder.id)
        }
    }
    val colors = MiuixTheme.colorScheme
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = ConversationPanelMetrics.SettleStiffness,
        ),
        label = "folder-chevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.toAndroidDragEvent().clipData?.description?.label == "eta-conversation"
                },
                target = remember(folder.id, onConversationDropToFolder) {
                    object : DragAndDropTarget {
                        override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                            dropActive = true
                        }

                        override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                            dropActive = false
                        }

                        override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                            dropActive = false
                            val conversationId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                                ?.takeIf(String::isNotBlank)
                                ?: return false
                            onConversationDropToFolder(conversationId, folder.id)
                            return true
                        }

                        override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                            dropActive = false
                        }
                    }
                },
            )
            .squircleSurface(
                color = if (dropActive) colors.primaryContainer else colors.surfaceContainer,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = if (dropActive) 1.2.dp else 0.5.dp,
                color = if (dropActive) colors.primary else colors.outline.copy(alpha = 0.24f),
                cornerRadius = 14.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .clickable(enabled = !forceExpanded, onClickLabel = folder.name, onClick = onToggleExpanded)
                .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = folder.name,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.conversationCount.toString(),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 7.dp),
            )
            IconButton(
                onClick = { onFolderActions(folder) },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = stringResource(R.string.folder_tree_manage),
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceVariantActions,
                )
            }
            IconButton(
                onClick = onToggleExpanded,
                enabled = !forceExpanded,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.conversation_folder_collapse_action)
                    } else {
                        stringResource(R.string.conversation_folder_expand_action)
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = colors.onSurfaceVariantActions,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = ConversationPanelMetrics.SettleStiffness,
                ),
                expandFrom = Alignment.Top,
            ) + fadeIn(),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = ConversationPanelMetrics.SettleStiffness,
                ),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(start = 9.dp, end = 5.dp, bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                group.children.forEach { child ->
                    key((child.section as ConversationDrawerSection.Folder).folder.id) {
                        ConversationFolderGroup(
                            group = child,
                            collapsedFolderIds = collapsedFolderIds,
                            forceExpanded = forceExpanded,
                            selectedConversationId = selectedConversationId,
                            archived = archived,
                            onToggleFolder = onToggleFolder,
                            onFolderActions = onFolderActions,
                            onConversationSelected = onConversationSelected,
                            onConversationTogglePin = onConversationTogglePin,
                            onConversationRename = onConversationRename,
                            onConversationExport = onConversationExport,
                            onConversationDelete = onConversationDelete,
                            onConversationArchive = onConversationArchive,
                            onConversationRestore = onConversationRestore,
                            onConversationMoveToFolder = onConversationMoveToFolder,
                            onConversationDropToFolder = onConversationDropToFolder,
                        )
                    }
                }
                group.items.forEach { conversation ->
                    val groupIds = group.items.mapTo(hashSetOf(), ConversationSummaryUi::id)
                    ConversationTextRow(
                        conversation = conversation,
                        selected = conversation.id == selectedConversationId,
                        onClick = { onConversationSelected(conversation.id) },
                        onTogglePin = { onConversationTogglePin(conversation) },
                        onRename = { onConversationRename(conversation) },
                        onExport = { onConversationExport(conversation) },
                        onDelete = { onConversationDelete(conversation) },
                        onArchive = { onConversationArchive(conversation) },
                        onRestore = { onConversationRestore(conversation) },
                        onMoveToFolder = { onConversationMoveToFolder(conversation) },
                        archived = archived,
                        hasBranchChildren = group.items.any { it.branchParentConversationId == conversation.id },
                        isBranchChild = conversation.branchParentConversationId in groupIds,
                        modifier = Modifier.padding(horizontal = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    isFirstSection: Boolean,
    onRenameFolder: (ConversationFolderUi) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val folder = (group.section as? ConversationDrawerSection.Folder)?.folder
        SmallTitle(
            text = group.localizedLabel(),
            insideMargin = PaddingValues(
                start = ConversationPanelMetrics.RowHorizontalPadding,
                top = if (isFirstSection) {
                    ConversationPanelMetrics.FirstSectionTopPadding
                } else {
                    ConversationPanelMetrics.SectionTopPadding
                },
                end = if (folder != null) 44.dp else ConversationPanelMetrics.RowHorizontalPadding,
                bottom = ConversationPanelMetrics.SectionBottomPadding,
            ),
        )
        if (folder != null) {
            IconButton(
                onClick = { onRenameFolder(folder) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = stringResource(R.string.conversation_folder_rename_action),
                    modifier = Modifier.size(ConversationPanelMetrics.ActionIconSize),
                )
            }
        }
    }
}

@Composable
private fun ConversationStatusMarker(
    conversation: ConversationSummaryUi,
) {
    val colors = MiuixTheme.colorScheme
    val stale = conversation.updatedAtMillis > 0L &&
        System.currentTimeMillis() - conversation.updatedAtMillis >= 7L * 24 * 60 * 60 * 1_000
    val color = when {
        conversation.isActiveRun -> Color(0xFF2FA66A)
        stale -> colors.error
        conversation.isPinned -> colors.primary
        else -> colors.outline.copy(alpha = .62f)
    }
    Canvas(Modifier.width(18.dp).height(28.dp)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        drawCircle(color = color, radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(centerX, centerY))
        if (conversation.isActiveRun) {
            drawCircle(color = color.copy(alpha = .22f), radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(centerX, centerY))
            drawCircle(color = color, radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(centerX, centerY))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMoveToFolder: () -> Unit,
    archived: Boolean,
    hasBranchChildren: Boolean = false,
    isBranchChild: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    var swipeOffset by remember(conversation.id) { mutableFloatStateOf(0f) }
    var settleVersion by remember(conversation.id) { mutableIntStateOf(0) }
    val pinThresholdPx = with(density) { ConversationPanelMetrics.PinSwipeThreshold.toPx() }
    val deleteThresholdPx = with(density) { ConversationPanelMetrics.DeleteSwipeThreshold.toPx() }
    val maxRevealPx = with(density) { ConversationPanelMetrics.PinSwipeMaxReveal.toPx() }
    val maxDeletePx = with(density) { ConversationPanelMetrics.DeleteSwipeMaxReveal.toPx() }
    val currentOnTogglePin by rememberUpdatedState(onTogglePin)
    val currentOnDelete by rememberUpdatedState(onDelete)

    LaunchedEffect(conversation.id, conversation.isPinned, selected) {
        swipeOffset = 0f
    }

    LaunchedEffect(settleVersion) {
        if (settleVersion == 0 || swipeOffset == 0f) return@LaunchedEffect
        val settle = Animatable(swipeOffset)
        settle.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = ConversationPanelMetrics.SettleStiffness,
            ),
        ) { swipeOffset = value }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (archived) Modifier else Modifier.pointerInput(conversation.id, pinThresholdPx, deleteThresholdPx, maxRevealPx, maxDeletePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var draggedPx = 0f
                    val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        draggedPx = overSlop.coerceIn(-maxDeletePx, maxRevealPx)
                    } ?: return@awaitEachGesture

                    swipeOffset = draggedPx
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == slopChange.id } ?: break
                            val horizontalDelta = change.positionChange().x
                            if (horizontalDelta != 0f) {
                                draggedPx = (swipeOffset + horizontalDelta).coerceIn(-maxDeletePx, maxRevealPx)
                                swipeOffset = draggedPx
                                change.consume()
                            }
                            if (!change.pressed) break
                        }

                        if (shouldCommitConversationPin(draggedPx, pinThresholdPx)) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnTogglePin()
                        } else if (draggedPx <= -deleteThresholdPx) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnDelete()
                        }
                    } finally {
                        settleVersion++
                    }
                }
            }),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .alpha(if (swipeOffset == 0f) 0f else 1f)
                .squircleSurface(
                    color = if (swipeOffset < 0f) {
                        MiuixTheme.colorScheme.errorContainer
                    } else {
                        MiuixTheme.colorScheme.tertiaryContainer
                    },
                    cornerRadius = ConversationPanelMetrics.RowCornerRadius,
                ),
            contentAlignment = if (swipeOffset < 0f) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (swipeOffset < 0f) Modifier.padding(end = ConversationPanelMetrics.PinSwipeIconInset)
                        else Modifier.padding(start = ConversationPanelMetrics.PinSwipeIconInset),
                    )
                    .padding(end = ConversationPanelMetrics.RowHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (swipeOffset < 0f) Icons.Outlined.Delete else Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(ConversationPanelMetrics.ActionIconSize),
                    tint = if (swipeOffset < 0f) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (swipeOffset < 0f) "删除会话" else stringResource(
                        if (conversation.isPinned) R.string.action_unpin_conversation else R.string.action_pin_conversation,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (swipeOffset < 0f) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                )
            }
        }
        val branchRailColor = MiuixTheme.colorScheme.primary.copy(alpha = .22f)
        val stale = conversation.updatedAtMillis > 0L &&
            System.currentTimeMillis() - conversation.updatedAtMillis >= 7L * 24 * 60 * 60 * 1_000
        val rowAccent = when {
            conversation.isActiveRun -> Color(0xFF2FA66A)
            stale -> MiuixTheme.colorScheme.error
            conversation.isPinned -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.outline
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .drawBehind {
                    if (!hasBranchChildren && !isBranchChild) return@drawBehind
                    val railX = ConversationPanelMetrics.RowHorizontalPadding.toPx() + 9.dp.toPx()
                    val centerY = size.height / 2f
                    if (isBranchChild) {
                        drawLine(
                            color = branchRailColor,
                            start = androidx.compose.ui.geometry.Offset(railX, 0f),
                            end = androidx.compose.ui.geometry.Offset(railX, centerY),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    if (hasBranchChildren) {
                        drawLine(
                            color = branchRailColor,
                            start = androidx.compose.ui.geometry.Offset(railX, centerY),
                            end = androidx.compose.ui.geometry.Offset(railX, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
                .squircleSurface(
                    color = if (selected) {
                        MiuixTheme.colorScheme.tertiaryContainer
                    } else {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    },
                    cornerRadius = ConversationPanelMetrics.RowCornerRadius,
                )
                .squircleBorder(
                    width = .5.dp,
                    color = if (selected) rowAccent.copy(alpha = .42f) else rowAccent.copy(alpha = .13f),
                    cornerRadius = ConversationPanelMetrics.RowCornerRadius,
                )
                .dragAndDropSource { _: androidx.compose.ui.geometry.Offset ->
                    DragAndDropTransferData(
                        clipData = ClipData.newPlainText("eta-conversation", conversation.id),
                        localState = conversation.id,
                        flags = 0,
                    )
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = null,
                )
                .padding(
                    horizontal = ConversationPanelMetrics.RowHorizontalPadding,
                    vertical = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationStatusMarker(conversation)
            Spacer(Modifier.width(8.dp))
            conversation.characterName?.let { name ->
                CharacterAvatar(
                    name = name,
                    avatarPath = conversation.characterAvatarPath,
                    size = 28.dp,
                    shape = CircleShape,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val title = conversation.title.ifBlank { conversation.preview }
                Text(
                    text = title,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onTertiaryContainer
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = conversation.runStatus
                    ?: conversation.characterName?.takeIf { it != title }
                    ?: conversation.timeLabel
                secondary?.takeIf(String::isNotBlank)?.let { value ->
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(
                onClick = { showActionMenu = true },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "会话操作",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }

        WindowListPopup(
            show = showActionMenu,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = { showActionMenu = false },
        ) {
            val pinText = stringResource(
                if (conversation.isPinned) {
                    R.string.action_unpin_conversation
                } else {
                    R.string.action_pin_conversation
                },
            )
            val renameText = stringResource(R.string.action_rename)
            val moveText = stringResource(R.string.conversation_folder_move_action)
            val exportText = stringResource(R.string.action_export)
            val archiveText = stringResource(R.string.conversation_archive_action)
            val restoreText = stringResource(R.string.conversation_archive_restore_action)
            val deleteText = stringResource(R.string.action_delete)
            val pinItem = remember(pinText) {
                DropdownItem(
                    text = pinText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val exportItem = remember(exportText) {
                DropdownItem(
                    text = exportText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val moveItem = remember(moveText) {
                DropdownItem(
                    text = moveText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val archiveItem = remember(archiveText) {
                DropdownItem(
                    text = archiveText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val restoreItem = remember(restoreText) {
                DropdownItem(
                    text = restoreText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                if (archived) {
                    DropdownImpl(
                        item = restoreItem,
                        optionSize = 3,
                        isSelected = false,
                        index = 0,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onRestore()
                        },
                    )
                    DropdownImpl(
                        item = exportItem,
                        optionSize = 3,
                        isSelected = false,
                        index = 1,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onExport()
                        },
                    )
                    DropdownImpl(
                        item = deleteItem,
                        optionSize = 3,
                        isSelected = false,
                        index = 2,
                        dropdownColors = deleteColors,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onDelete()
                        },
                    )
                } else {
                    DropdownImpl(
                        item = pinItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 0,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onTogglePin()
                        },
                    )
                    DropdownImpl(
                        item = renameItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 1,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onRename()
                        },
                    )
                    DropdownImpl(
                        item = moveItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 2,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onMoveToFolder()
                        },
                    )
                    DropdownImpl(
                        item = exportItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 3,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onExport()
                        },
                    )
                    DropdownImpl(
                        item = archiveItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 4,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onArchive()
                        },
                    )
                    DropdownImpl(
                        item = deleteItem,
                        optionSize = 6,
                        isSelected = false,
                        index = 5,
                        dropdownColors = deleteColors,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean, isArchive: Boolean) {
    Text(
        text = stringResource(
            when {
                isSearching -> R.string.conversation_no_results
                isArchive -> R.string.conversation_archive_empty
                else -> R.string.conversation_empty
            },
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(
            horizontal = ConversationPanelMetrics.RowHorizontalPadding,
            vertical = ConversationPanelMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneDock(
    mode: ConversationPaneMode,
    onModeChange: (ConversationPaneMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DockEntry(
            label = "会话",
            selected = mode == ConversationPaneMode.Chats,
            onClick = { onModeChange(ConversationPaneMode.Chats) },
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            label = "会议室",
            selected = mode == ConversationPaneMode.Meetings,
            onClick = { onModeChange(ConversationPaneMode.Meetings) },
            modifier = Modifier.weight(1f),
        )
        DockEntry(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = onOpenSettings,
            modifier = Modifier.weight(0.8f),
        )
    }
}

@Composable
private fun DockEntry(
    icon: ImageVector? = null,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .58f)
                },
                cornerRadius = 11.dp,
            )
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(ConversationPanelMetrics.DockEntryIconSize),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        } else {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Archive -> stringResource(R.string.conversation_archive_section)
    is ConversationDrawerSection.Folder -> value.folder.name
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}
