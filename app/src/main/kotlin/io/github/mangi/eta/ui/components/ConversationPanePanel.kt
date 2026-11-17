package io.github.mangi.eta.ui.components

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.CreateNewFolder
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.fork.conversations.ConversationPaneOrganization
import io.github.mangi.eta.fork.conversations.ConversationPaneOrganizationStore
import io.github.mangi.eta.fork.conversations.ConversationPanePlacement
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
    val RowMinHeight = 42.dp
    val RowGap = 3.dp
    val RowCornerRadius = 23.dp
    val RowOuterPadding = 17.dp
    val RowHorizontalPadding = 16.dp
    val RowVerticalPadding = 4.dp
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

private val ConversationRowShape = smoothShape(ConversationPanelMetrics.RowCornerRadius)

/** Fill, rim and feedback must share one outline, including at the diagonal of each corner. */
@Composable
private fun Modifier.conversationRowSurface(color: Color, rim: Color): Modifier {
    val skin = LocalEtaDesignTokens.current.skin
    val light = MiuixTheme.colorScheme.background.luminance() > 0.5f
    val fill = when {
        !light || !skin.isGlass -> color
        skin == AppearanceSkin.LIQUID_GLASS -> color.copy(alpha = .84f)
        else -> color.copy(alpha = .95f)
    }
    val surface = background(fill, ConversationRowShape)
    return if (light && skin.isGlass) {
        surface.border(.5.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .90f), rim,
        )), ConversationRowShape).clip(ConversationRowShape)
    } else {
        surface.border(.5.dp, rim, ConversationRowShape).clip(ConversationRowShape)
    }
}

@Composable
private fun conversationPaneRowColor(): Color = if (MiuixTheme.colorScheme.background.luminance() > .5f) {
    Color.White
} else MiuixTheme.colorScheme.surfaceContainerHigh

private val CollapsedFolderIdsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 深色下聊天区保持 surface（纯黑/近黑），侧栏沿用同一颜色会在展开后与聊天区融为一体；
 * 浅色用浅灰白底承托更亮的白色条目；深色保持已有层次。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.background.luminance() > 0.5f) {
        Color(0xFFF2F3F5)
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
    onPanePlacement: (io.github.mangi.eta.fork.conversations.ConversationPanePlacement) -> Unit = {},
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
    activityAnimationEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val paneMode = if (showMeetingsByDefault) ConversationPaneMode.Meetings else ConversationPaneMode.Chats
    var showCreateMeeting by rememberSaveable { mutableStateOf(false) }
    var meetingQuery by rememberSaveable { mutableStateOf("") }
    var meetingFolderDialogRequest by remember { mutableStateOf<ConversationFolderDialogRequest?>(null) }
    var collapsedFolderIds by rememberSaveable(stateSaver = CollapsedFolderIdsSaver) {
        mutableStateOf(emptySet<String>())
    }
    var collapsedSectionKeys by rememberSaveable(stateSaver = CollapsedFolderIdsSaver) {
        mutableStateOf(emptySet<String>())
    }
    val context = LocalContext.current
    val meetingRooms by remember(context) { MeetingRepository.rooms(context) }.collectAsState(initial = emptyList())
    val activeMeetingRoomIds by MeetingRepository.activeRoomIds.collectAsState(initial = emptySet())
    val activityEnvironment = rememberConversationActivityEnvironment(activityAnimationEnabled)
    val organization by remember(context) { ConversationPaneOrganizationStore.observe(context) }
        .collectAsState(initial = ConversationPaneOrganization())
    val meetingFolders = remember(organization.meetingFolderDefinitions) {
        organization.meetingFolderDefinitions.sortedBy { it.sortOrder }.map {
            ConversationFolderUi(it.id, it.name, it.sortOrder, it.parentId)
        }
    }
    val meetingListState = rememberLazyListState()
    val dragSession = remember { PaneDragSession() }
    val currentPlacement by rememberUpdatedState(onPanePlacement)
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val displayedConversations = if (state.archiveViewOpen) {
        state.archivedConversations
    } else {
        state.conversations
    }
    val groups = remember(displayedConversations, state.folders, state.archiveViewOpen, query, organization) {
        displayedConversations.groupForDrawer(
            folders = state.folders,
            archived = state.archiveViewOpen,
            searching = query.isNotBlank(),
        ).map { it.withPaneOrder(organization) }
    }
    val fullChatGroups = remember(state.unfilteredConversations, state.folders, organization) {
        state.unfilteredConversations.groupForDrawer(state.folders, archived = false)
            .map { it.withPaneOrder(organization) }.paneGroupsByScope()
    }
    val naturalChatScopes = remember(state.unfilteredConversations) {
        state.unfilteredConversations.map { it.copy(isPinned = false, folderId = null) }
            .groupForDrawer(emptyList(), archived = false).paneGroupsByScope()
            .flatMap { (scope, group) -> group.items.map { it.id to scope } }.toMap()
    }
    val fullMeetingGroups = remember(meetingRooms, meetingFolders, organization, meetingArchiveViewOpen) {
        meetingPaneGroups(meetingRooms, organization, meetingArchiveViewOpen)
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
    val meetingGroups = remember(fullMeetingGroups, filteredMeetingRooms, meetingQuery) {
        val visibleIds = filteredMeetingRooms.mapTo(hashSetOf()) { it.id }
        fullMeetingGroups.map { it.copy(rooms = it.rooms.filter { room -> room.id in visibleIds }) }
            .filter { it.rooms.isNotEmpty() || (it.folder != null && meetingQuery.isBlank()) }
    }
    val destinations = remember(fullChatGroups, fullMeetingGroups) {
        buildMap<ConversationSubject, PaneDropDestination> {
            fullChatGroups.forEach { (scope, group) ->
                val folderId = (group.section as? ConversationDrawerSection.Folder)?.folder?.id
                group.items.forEach { row ->
                    put(ConversationSubject.chat(row.id), PaneDropDestination(scope, row.isPinned, folderId, row.id))
                }
            }
            fullMeetingGroups.forEach { group -> group.rooms.forEach { room ->
                put(ConversationSubject.meeting(room.id), PaneDropDestination(group.scope, group.pinned, group.folder?.id, room.id))
            } }
        }
    }
    val archiveOpen = if (paneMode == ConversationPaneMode.Chats) state.archiveViewOpen else meetingArchiveViewOpen
    SideEffect {
        dragSession.enabled = organization.ready && !archiveOpen
        dragSession.accepts = { subject, destination ->
            val prefix = "${subject.kind.value}:"
            dragSession.enabled && subject in destinations && destination.scope.startsWith(prefix) &&
                destination.neighborId != subject.id &&
                (destination.folderId == null || if (subject.kind == ConversationSubject.Kind.MEETING) {
                    meetingFolders.any { it.id == destination.folderId }
                } else state.folders.any { it.id == destination.folderId }) &&
                (destination.pinned || destination.folderId != null || destination.scope == "${prefix}root" ||
                    subject.kind == ConversationSubject.Kind.MEETING || naturalChatScopes[subject.id] == destination.scope)
        }
        dragSession.place = { subject, destination, after ->
            val scope = if (destination.scope == "chat:root") naturalChatScopes[subject.id] else destination.scope
            if (scope != null) {
                val members = if (subject.kind == ConversationSubject.Kind.CHAT) {
                    fullChatGroups[scope]?.items?.map { it.id }.orEmpty()
                } else fullMeetingGroups.firstOrNull { it.scope == scope }?.rooms?.map { it.id }.orEmpty()
                currentPlacement(ConversationPanePlacement(subject, destination.pinned, destination.folderId, scope,
                    members, destination.neighborId, after))
            }
        }
    }
    LaunchedEffect(paneMode, archiveOpen) { dragSession.finish() }
    val scrollSpeed = with(LocalDensity.current) { 420.dp.toPx() }
    LaunchedEffect(dragSession.subject, dragSession.scrollDirection, paneMode) {
        if (dragSession.subject != null && dragSession.scrollDirection != 0) {
            val scrollingList = if (paneMode == ConversationPaneMode.Chats) listState else meetingListState
            var previousFrame = withFrameNanos { it }
            while (dragSession.subject != null && dragSession.scrollDirection != 0) {
                val frame = withFrameNanos { it }
                dragSession.refreshTarget()
                val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.032f)
                scrollingList.scrollBy(scrollSpeed * seconds * dragSession.scrollDirection)
                previousFrame = frame
            }
        }
    }
    LaunchedEffect(state.archiveViewOpen) {
        listState.scrollToItem(0)
    }

    CompositionLocalProvider(
        LocalPaneDrag provides dragSession,
        LocalPaneDestinations provides destinations,
        LocalConversationActivityEnvironment provides activityEnvironment,
    ) {
    Surface(
        modifier = modifier
            .paneDragHost()
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
                        .paneDropTarget(PaneDropDestination(if (paneMode == ConversationPaneMode.Chats) "chat:pinned" else "meeting:pinned", pinned = true))
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
                            onCreateFolder = { meetingFolderDialogRequest = ConversationFolderDialogRequest.Create(null) },
                        )
                    }
                    Spacer(Modifier.height(ConversationPanelMetrics.AfterActionBar))
                    if (dragSession.subject != null) {
                        Text("拖到这里置顶", style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                    }
                }
            }
            if (paneMode == ConversationPaneMode.Chats) {
                LazyColumn(
                    state = listState,
                    modifier = conversationPaneListModifier().weight(1f).paneDragAutoScroll(),
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
                                val folderRows = mutableListOf<Triple<ConversationDrawerGroup, Int, Boolean>>()
                                fun appendVisibleFolders(node: ConversationDrawerGroup, depth: Int) {
                                    folderRows += Triple(node, depth, true)
                                    val nodeFolder = (node.section as ConversationDrawerSection.Folder).folder
                                    if (query.isNotBlank() || nodeFolder.id !in collapsedFolderIds) {
                                        node.children.forEach { appendVisibleFolders(it, depth + 1) }
                                        folderRows += Triple(node, depth, false)
                                    }
                                }
                                appendVisibleFolders(group, 0)
                                folderRows.forEach { (folderGroup, depth, heading) ->
                                val currentFolder = (folderGroup.section as ConversationDrawerSection.Folder).folder
                                if (heading) {
                                item(key = "folder-${currentFolder.id}") {
                                    ConversationFolderGroup(
                                        group = folderGroup,
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
                                        includeContents = false,
                                        modifier = Modifier.padding(start = (depth * 12).dp),
                                    )
                                }
                                } else {
                                    val groupIds = folderGroup.items.mapTo(hashSetOf(), ConversationSummaryUi::id)
                                    val branchParents = folderGroup.items.mapNotNullTo(hashSetOf()) { it.branchParentConversationId }
                                    items(folderGroup.items, key = { it.id }) { conversation ->
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
                                            hasBranchChildren = conversation.id in branchParents,
                                            isBranchChild = conversation.branchParentConversationId in groupIds,
                                            modifier = Modifier.padding(start = ((depth + 1) * 12).dp, end = ConversationPanelMetrics.RowOuterPadding),
                                        )
                                    }
                                }
                                }
                            } else {
                                val sectionKey = group.section.stableKey
                                val expanded = query.isNotBlank() || sectionKey !in collapsedSectionKeys
                                item(key = "section-$sectionKey") {
                                    ConversationSectionHeader(
                                        group = group,
                                        isFirstSection = groupIndex == 0,
                                        expanded = expanded,
                                        onToggleExpanded = {
                                            collapsedSectionKeys = if (sectionKey in collapsedSectionKeys) {
                                                collapsedSectionKeys - sectionKey
                                            } else {
                                                collapsedSectionKeys + sectionKey
                                            }
                                        },
                                    )
                                }
                                if (expanded) {
                                    val groupIds = group.items.mapTo(hashSetOf(), ConversationSummaryUi::id)
                                    val branchParents = group.items.mapNotNullTo(hashSetOf()) { it.branchParentConversationId }
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
                                            hasBranchChildren = conversation.id in branchParents,
                                            isBranchChild = conversation.branchParentConversationId in groupIds,
                                            modifier = Modifier.padding(
                                                start = 0.dp, end = ConversationPanelMetrics.RowOuterPadding,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = meetingListState,
                    modifier = conversationPaneListModifier().weight(1f).paneDragAutoScroll(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    overscrollEffect = null,
                ) {
                    if (meetingGroups.isEmpty()) {
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
                        meetingGroups.forEach { group ->
                            val folder = group.folder
                            // A collapsed parent hides its complete subtree in either list.
                            val hiddenByParent = folder != null && meetingQuery.isBlank() &&
                                folderHasCollapsedAncestor(folder, meetingFolders, collapsedFolderIds)
                            if (!hiddenByParent) {
                            val sectionKey = group.scope
                            val expanded = meetingQuery.isNotBlank() || if (folder != null) {
                                folder.id !in collapsedFolderIds
                            } else sectionKey !in collapsedSectionKeys
                            item(key = "meeting-heading:$sectionKey") {
                                if (folder != null) {
                                    PaneMeetingFolderHeader(folder, expanded, meetingQuery.isNotBlank(),
                                        onToggle = {
                                            collapsedFolderIds = if (folder.id in collapsedFolderIds) collapsedFolderIds - folder.id else collapsedFolderIds + folder.id
                                        },
                                        onActions = { meetingFolderDialogRequest = ConversationFolderDialogRequest.Menu(folder) },
                                        modifier = Modifier.padding(start = (group.depth * 12).dp))
                                } else {
                                    PaneSectionHeading(group.title, expanded,
                                        onToggle = {
                                            collapsedSectionKeys = if (sectionKey in collapsedSectionKeys) collapsedSectionKeys - sectionKey else collapsedSectionKeys + sectionKey
                                        },
                                        modifier = Modifier.paneDropTarget(PaneDropDestination(group.scope, group.pinned)))
                                }
                            }
                            if (expanded) items(group.rooms, key = { "meeting:${it.id}" }) { room ->
                            MeetingRoomPaneRow(
                                room = room,
                                activeRun = room.id in activeMeetingRoomIds,
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
                                modifier = Modifier.padding(start = (if (folder == null) 0 else (group.depth + 1) * 12).dp),
                            )
                        }
                            }
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
    ConversationFolderDialogs(
        request = meetingFolderDialogRequest,
        folders = meetingFolders,
        onRequestChange = { meetingFolderDialogRequest = it },
        onCreate = { name, parentId -> ConversationPaneOrganizationStore.createMeetingFolder(context, name, parentId) },
        onRename = { id, name -> ConversationPaneOrganizationStore.renameMeetingFolder(context, id, name) },
        onMoveFolder = { id, parentId -> ConversationPaneOrganizationStore.moveMeetingFolder(context, id, parentId) },
        // Meeting-room placement is handled by the pane's typed drag command, never a chat callback.
        onMoveConversation = { _, _ -> },
        onSetFolderPinned = { id, pinned -> ConversationPaneOrganizationStore.setMeetingFolderPinned(context, id, pinned) },
        onDelete = { id -> ConversationPaneOrganizationStore.deleteMeetingFolder(context, id) },
        deleteMessage = "删除此会议文件夹？其直接子文件夹和会议室（含归档会议室）将移到上一层，会议记录会保留。如果出现同名冲突，请先重命名或移动冲突的文件夹。",
    )
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
    onCreateFolder: () -> Unit,
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
            icon = Icons.Outlined.CreateNewFolder,
            contentDescription = "新建会议文件夹",
            onClick = onCreateFolder,
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
    activeRun: Boolean,
    selected: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onArchiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = room.status == MeetingRoomStatus.RUNNING
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
        modifier = modifier.fillMaxWidth().paneItemDropTarget(ConversationSubject.meeting(room.id)).then(
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
                .background(
                    if (swipeOffset < 0f) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.tertiaryContainer,
                    ConversationRowShape,
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
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .conversationRowSurface(
                    when {
                        selected -> MiuixTheme.colorScheme.primaryContainer
                        running -> MiuixTheme.colorScheme.secondaryContainer
                        else -> conversationPaneRowColor()
                    },
                    MiuixTheme.colorScheme.outline.copy(alpha = if (selected) .28f else .12f),
                )
                .clickable(onClickLabel = room.title, onClick = onClick)
                .padding(start = 14.dp, top = 4.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationActivityMarker(
                running = activeRun,
                activityAtMillis = room.updatedAt,
                modifier = Modifier.size(7.dp),
            )
            Spacer(Modifier.width(8.dp))
            AgentIdentityAvatar(displayName = room.title, seed = room.id, size = 28.dp, icon = null,
                palette = AgentIdentityPalette.MeetingVivid)
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f).paneDragSource(ConversationSubject.meeting(room.id), !archived, onClick)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (room.status) {
                        MeetingRoomStatus.RUNNING -> "讨论中"
                        MeetingRoomStatus.WAITING_FOR_USER -> "待你发言"
                        MeetingRoomStatus.FAILED -> "需处理"
                        else -> "空闲"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 38.dp, max = 56.dp),
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
                    minWidth = 32.dp,
                    minHeight = 32.dp,
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

private fun folderHasCollapsedAncestor(folder: ConversationFolderUi, folders: List<ConversationFolderUi>, collapsed: Set<String>): Boolean {
    var parent = folder.parentId
    val visited = hashSetOf(folder.id)
    while (parent != null && visited.add(parent)) {
        if (parent in collapsed) return true
        parent = folders.firstOrNull { it.id == parent }?.parentId
    }
    return false
}

@Composable
private fun Modifier.paneFolderSurface(destination: PaneDropDestination): Modifier {
    val colors = MiuixTheme.colorScheme
    val active = LocalPaneDrag.current?.hovered == destination
    return paneDropTarget(destination)
        .conversationRowSurface(if (active) colors.primaryContainer else if (colors.background.luminance() > .5f) {
            conversationPaneRowColor()
        } else colors.surfaceContainer,
            colors.outline.copy(alpha = .24f))
        .border(if (active) 1.2.dp else .5.dp,
            if (active) colors.primary else colors.outline.copy(alpha = .24f), ConversationRowShape)
        .clip(ConversationRowShape)
}

@Composable
private fun PaneMeetingFolderHeader(
    folder: ConversationFolderUi,
    expanded: Boolean,
    forceExpanded: Boolean,
    onToggle: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f,
        spring(dampingRatio = 1f, stiffness = ConversationPanelMetrics.SettleStiffness), label = "meeting-folder-chevron")
    Row(modifier.fillMaxWidth()
        .paneFolderSurface(PaneDropDestination("meeting:folder:${folder.id}", folderId = folder.id))
        .heightIn(min = 42.dp)
        .clickable(enabled = !forceExpanded, onClickLabel = folder.name, onClick = onToggle)
        .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(folder.name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onActions, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.folder_tree_manage), Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
        IconButton(onClick = onToggle, enabled = !forceExpanded, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Outlined.KeyboardArrowDown,
                stringResource(if (expanded) R.string.conversation_folder_collapse_action else R.string.conversation_folder_expand_action),
                Modifier.size(18.dp).rotate(rotation), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
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
    includeContents: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val folder = (group.section as ConversationDrawerSection.Folder).folder
    val expanded = forceExpanded || folder.id !in collapsedFolderIds
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
        modifier = modifier
            .fillMaxWidth()
            .paneFolderSurface(PaneDropDestination("chat:folder:${folder.id}", folderId = folder.id)),
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
            visible = expanded && includeContents,
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
                val groupIds = remember(group.items) { group.items.mapTo(hashSetOf(), ConversationSummaryUi::id) }
                val branchParents = remember(group.items) { group.items.mapNotNullTo(hashSetOf()) { it.branchParentConversationId } }
                group.items.forEach { conversation ->
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
                        hasBranchChildren = conversation.id in branchParents,
                        isBranchChild = conversation.branchParentConversationId in groupIds,
                        modifier = Modifier.padding(start = 0.dp, end = ConversationPanelMetrics.RowOuterPadding),
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
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    PaneSectionHeading(group.localizedLabel(), expanded, onToggleExpanded, isFirstSection,
        Modifier.paneDropTarget(PaneDropDestination("chat:${group.section.stableKey}", group.section == ConversationDrawerSection.Pinned)))
}

@Composable
private fun PaneSectionHeading(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    isFirstSection: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        SmallTitle(
            text = title,
            insideMargin = PaddingValues(
                start = ConversationPanelMetrics.RowHorizontalPadding,
                top = if (isFirstSection) {
                    ConversationPanelMetrics.FirstSectionTopPadding
                } else {
                    ConversationPanelMetrics.SectionTopPadding
                },
                end = 42.dp,
                bottom = ConversationPanelMetrics.SectionBottomPadding,
            ),
        )
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .size(34.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠此时间分组" else "展开此时间分组",
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 0f else -90f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@Composable
private fun ConversationStatusMarker(
    conversation: ConversationSummaryUi,
) {
    ConversationActivityMarker(
        running = conversation.isActiveRun,
        activityAtMillis = conversation.lastActivityAtMillis,
        modifier = Modifier.width(18.dp).height(28.dp),
    )
}

/** Keep the existing row fill and rim independent of the status dot's calendar colors. */
@Composable
private fun conversationRecencyAccent(conversation: ConversationSummaryUi): Color {
    val colors = MiuixTheme.colorScheme
    if (conversation.isActiveRun) return Color(0xFF28B36F)
    val activity = conversation.lastActivityAtMillis
    if (activity <= 0L) return colors.outline.copy(alpha = .58f)
    var now by remember(activity) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity) {
        val day = 86_400_000L
        for (boundary in listOf(activity + 3 * day + 1, activity + 7 * day + 1)) {
            now = System.currentTimeMillis()
            if (boundary > now) kotlinx.coroutines.delay(boundary - now)
            now = System.currentTimeMillis()
        }
    }
    val age = (now - activity).coerceAtLeast(0L)
    return when {
        age <= 3 * 86_400_000L -> Color(0xFFF19A32)
        age <= 7 * 86_400_000L -> Color(0xFFE75C65)
        else -> colors.outline.copy(alpha = .62f)
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
    var showProjectPicker by remember(conversation.id) { mutableStateOf(false) }
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
                .background(
                    color = if (swipeOffset < 0f) {
                        MiuixTheme.colorScheme.errorContainer
                    } else {
                        MiuixTheme.colorScheme.tertiaryContainer
                    },
                    shape = ConversationRowShape,
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
        val rowAccent = conversationRecencyAccent(conversation)
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
                .conversationRowSurface(
                    color = if (selected) {
                        if (MiuixTheme.colorScheme.background.luminance() > .5f) {
                            lerp(conversationPaneRowColor(), rowAccent, .14f)
                        } else rowAccent.copy(alpha = .14f)
                    } else {
                        conversationPaneRowColor()
                    },
                    rim = if (selected) rowAccent.copy(alpha = .48f) else rowAccent.copy(alpha = .16f),
                )
                .paneItemDropTarget(ConversationSubject.chat(conversation.id))
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
            Row(modifier = Modifier.weight(1f).paneDragSource(ConversationSubject.chat(conversation.id), !archived, onClick), verticalAlignment = Alignment.CenterVertically) {
                val title = conversation.title.ifBlank { conversation.preview }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
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
                conversation.runStatus?.takeIf(String::isNotBlank)?.let { value ->
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 60.dp).padding(start = 4.dp),
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
            val projectText = stringResource(R.string.project_add_conversation)
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
            val projectItem = remember(projectText) {
                DropdownItem(text = projectText, icon = { modifier ->
                    Icon(imageVector = Icons.Outlined.Folder, contentDescription = null,
                        modifier = modifier.size(ConversationPanelMetrics.ActionIconSize))
                })
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
                        optionSize = 7,
                        isSelected = false,
                        index = 0,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onTogglePin()
                        },
                    )
                    DropdownImpl(
                        item = renameItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 1,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onRename()
                        },
                    )
                    DropdownImpl(
                        item = moveItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 2,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onMoveToFolder()
                        },
                    )
                    DropdownImpl(
                        item = projectItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 3,
                        onSelectedIndexChange = { showActionMenu = false; showProjectPicker = true },
                    )
                    DropdownImpl(
                        item = exportItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 4,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onExport()
                        },
                    )
                    DropdownImpl(
                        item = archiveItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 5,
                        onSelectedIndexChange = {
                            showActionMenu = false
                            onArchive()
                        },
                    )
                    DropdownImpl(
                        item = deleteItem,
                        optionSize = 7,
                        isSelected = false,
                        index = 6,
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
    if (showProjectPicker) ConversationProjectDialog(conversation.id) { showProjectPicker = false }
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
            useConversationShape = true,
            selected = mode == ConversationPaneMode.Chats,
            onClick = { onModeChange(ConversationPaneMode.Chats) },
            modifier = Modifier.weight(1f).paneDropTarget(PaneDropDestination("chat:root")),
        )
        DockEntry(
            label = "会议室",
            useConversationShape = true,
            selected = mode == ConversationPaneMode.Meetings,
            onClick = { onModeChange(ConversationPaneMode.Meetings) },
            modifier = Modifier.weight(1f).paneDropTarget(PaneDropDestination("meeting:root")),
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
    useConversationShape: Boolean = false,
) {
    val fill = if (selected) MiuixTheme.colorScheme.tertiaryContainer
        else MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .58f)
    Box(
        modifier = modifier
            .height(36.dp)
            .then(if (useConversationShape) Modifier.background(fill, ConversationRowShape).clip(ConversationRowShape)
                else Modifier.squircleSurface(fill, 11.dp))
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
