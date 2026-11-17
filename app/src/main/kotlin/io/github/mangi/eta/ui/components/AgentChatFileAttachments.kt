package io.github.mangi.eta.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.SkillChoiceUi
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 48.dp
internal val ChatInputActionIconSize = 24.dp
internal val ChatInputPopupWidth = 224.dp

private enum class AttachmentPopupSection { MCP, SKILLS }

@Composable
internal fun AgentAttachmentPickerButton(
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onInsertText: (String) -> Unit = {},
    onRemoveReference: (String, String) -> Unit = { _, _ -> },
    onOpenMcp: (() -> Unit)? = null,
    onOpenTools: (() -> Unit)? = null,
    availableSkills: List<SkillChoiceUi> = emptyList(),
    selectedSkillIds: List<String> = emptyList(),
    onSelectedSkillIdsChange: (List<String>) -> Unit = {},
    skillChoicesLoading: Boolean = false,
    skillChoicesLoadFailed: Boolean = false,
    onRetrySkillChoices: () -> Unit = {},
    showSkillPicker: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPopup by remember { mutableStateOf(false) }
    var popupSection by remember { mutableStateOf<AttachmentPopupSection?>(null) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val mcpServers by remember { McpServerRepository.serversFlow() }
        .collectAsState(initial = emptyList())
    val activeMcpServers = remember(mcpServers) { mcpServers.filter { it.enabled } }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { popupSection = null; showPopup = true },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
                modifier = Modifier.size(ChatInputActionIconSize),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        val skillEntries = when {
            skillChoicesLoading -> listOf(DropdownItem(text = stringResource(R.string.chat_skill_choices_loading), enabled = false))
            skillChoicesLoadFailed -> listOf(DropdownItem(text = stringResource(R.string.action_retry), onClick = onRetrySkillChoices))
            availableSkills.isEmpty() -> listOf(DropdownItem(text = stringResource(R.string.chat_no_available_skills), enabled = false))
            else -> availableSkills.map { skill ->
                DropdownItem(
                    text = skill.name,
                    selected = skill.id in selectedSkillIds,
                    enabled = skill.id in selectedSkillIds || selectedSkillIds.size < 3,
                    onClick = {
                        val next = if (skill.id in selectedSkillIds) selectedSkillIds - skill.id else selectedSkillIds + skill.id
                        onSelectedSkillIdsChange(availableSkills.map { it.id }.filter(next::contains))
                        if (skill.id in selectedSkillIds) onRemoveReference("Skill", skill.id)
                        else onInsertText(io.github.mangi.eta.agent.model.AgentInvocationReferences.token("Skill", skill.id, skill.name) + " ")
                    },
                )
            }
        }
        val mcpEntries = activeMcpServers.map { server ->
            DropdownItem(text = server.name, onClick = {
                onInsertText(io.github.mangi.eta.agent.model.AgentInvocationReferences.token("MCP", server.id, server.name) + " ")
                showPopup = false
            })
        } + listOfNotNull(onOpenMcp?.let { manage ->
            DropdownItem(text = "管理 MCP", onClick = { showPopup = false; manage() })
        })
        ComposerPopup(
            show = showPopup,
            anchorTopPx = popupAnchorTopPx,
            onDismiss = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
            NavigationBackHandler(
                state = backState,
                isBackEnabled = popupSection != null,
                onBackCompleted = { popupSection = null },
            )
            when (popupSection) {
                null -> AttachmentPopupMain(
                    onClose = { showPopup = false },
                    onImage = {
                        showPopup = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFile = { showPopup = false; filePicker.launch(arrayOf("*/*")) },
                    onFolder = { showPopup = false; folderPicker.launch(null) },
                    onPath = { showPopup = false; pathInput = ""; showPathDialog = true },
                    onMcp = if (onOpenMcp != null) ({ popupSection = AttachmentPopupSection.MCP }) else null,
                    onTools = onOpenTools?.let { open -> { showPopup = false; open() } },
                    onSkills = if (showSkillPicker) ({ popupSection = AttachmentPopupSection.SKILLS }) else null,
                )
                AttachmentPopupSection.MCP -> AttachmentPopupChildren(
                    title = "MCP",
                    entries = mcpEntries,
                    onBack = { popupSection = null },
                )
                AttachmentPopupSection.SKILLS -> AttachmentPopupChildren(
                    title = "技能",
                    entries = skillEntries,
                    onBack = { popupSection = null },
                )
            }
        }
    }

    WindowDialog(
        show = showPathDialog,
        cornerRadius = DialogDefaults.CornerRadius,
        title = stringResource(R.string.ui_input_file_path_36d474),
        summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        onDismissRequest = { showPathDialog = false },
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onCancel = { showPathDialog = false },
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun AttachmentPopupMain(
    onClose: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onFolder: () -> Unit,
    onPath: () -> Unit,
    onMcp: (() -> Unit)?,
    onTools: (() -> Unit)?,
    onSkills: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.width(ChatInputPopupWidth)
            .verticalScroll(rememberScrollState())
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ui_add_attachment_dba9e8),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, minWidth = 30.dp, minHeight = 30.dp) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close),
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        AttachmentPopupOption("图片", Icons.Rounded.Image, EtaIconTone.DATA.accent, onClick = onImage)
        AttachmentPopupOption("文件", Icons.Rounded.Description, EtaIconTone.MODEL.accent, onClick = onFile)
        AttachmentPopupOption("文件夹", Icons.Rounded.FolderOpen, EtaIconTone.MEMORY.accent, onClick = onFolder)
        AttachmentPopupOption("路径", Icons.Rounded.Link, EtaIconTone.SYSTEM.accent, onClick = onPath)
        if (onMcp != null) AttachmentPopupOption("MCP", Icons.Rounded.Hub, EtaIconTone.AUTOMATION.accent, true, onMcp)
        if (onTools != null) AttachmentPopupOption("工具", Icons.Rounded.Build, EtaIconTone.TOOL.accent, onClick = onTools)
        if (onSkills != null) AttachmentPopupOption("技能", Icons.Rounded.Extension, EtaIconTone.SYSTEM.accent, true, onSkills)
    }
}

@Composable
private fun AttachmentPopupOption(
    title: String,
    icon: ImageVector,
    accent: Color,
    hasChildren: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 40.dp)
            .squircleSurface(composerPopupItemColor(), 13.dp)
            .squircleBorder(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.16f), 13.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier.size(26.dp).squircleSurface(accent.copy(alpha = 0.14f), 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasChildren) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun AttachmentPopupChildren(title: String, entries: List<DropdownItem>, onBack: () -> Unit) {
    Column(
        modifier = Modifier.width(ChatInputPopupWidth).verticalScroll(rememberScrollState()).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp)
                .clip(smoothShape(13.dp)).clickable(onClick = onBack).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.action_back),
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        entries.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .squircleSurface(if (item.selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else composerPopupItemColor(), 13.dp)
                    .semantics { selected = item.selected }
                    .clickable(enabled = item.enabled && item.onClick != null) { item.onClick?.invoke() }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = item.text,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (item.enabled) 1f else 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.selected) Icon(Icons.Rounded.Check, contentDescription = "已选择",
                    modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(references.size) }
    LaunchedEffect(references.size) {
        if (references.size > previousCount && references.isNotEmpty()) {
            listState.animateScrollToItem(references.lastIndex)
        }
        previousCount = references.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = references,
            key = { it.id },
        ) { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(durationMillis = 120),
                    )
                    .attachmentItemEntrance()
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 12.dp,
                    )
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

/**
 * 附件胶囊与缩略图的入场动效：在 graphicsLayer 阶段驱动缩放、纵向微移与透明度，避免引发组合期重组。
 */
internal fun Modifier.attachmentItemEntrance(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 420f,
            ),
        )
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.9f + 0.1f * p
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * 6.dp.toPx()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}
