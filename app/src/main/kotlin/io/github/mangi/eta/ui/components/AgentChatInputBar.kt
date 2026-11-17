package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.voice.SpeechDictationButton
import io.github.mangi.eta.ui.voice.SpeechInputFeedback
import io.github.mangi.eta.ui.voice.rememberSpeechInput
import io.github.mangi.eta.ui.voice.SpeechDictationOverlay
import io.github.mangi.eta.ui.voice.SpeechDraftInsertion
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.permission.AgentPermissionPolicy
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.zhh_lgtm.nexus.application.api.WorkflowMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

private val SendButtonVisualSize = 42.dp
private val SendIconSize = 19.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val ChatModeAccent = Color(0xFF3C8CFF)
private val PlanModeAccent = Color(0xFF8B6CFF)
private val GoalModeAccent = Color(0xFF4A7DFF)
private val FullAccessAccent = Color(0xFF27C47B)
private val SmartApprovalAccent = Color(0xFFFFB13B)
private val ManualApprovalAccent = Color(0xFFFF5C72)
@Composable
private fun AgentSessionStatusStrip(
    mode: WorkflowMode,
    permissionMode: AgentPermissionMode,
    enabled: Boolean,
    onModeClick: () -> Unit,
    onPermissionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(30.dp)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentSessionStatusBadge(
            text = mode.initial,
            contentDescription = mode.title,
            accent = mode.accent,
            enabled = enabled,
            onClick = onModeClick,
        )
        Box(
            modifier = Modifier
                .size(3.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.28f), CircleShape),
        )
        AgentSessionStatusBadge(
            text = permissionMode.initial,
            contentDescription = permissionMode.settingsLabel,
            accent = permissionMode.accent,
            enabled = enabled,
            onClick = onPermissionClick,
        )
    }
}

@Composable
private fun AgentSessionStatusBadge(
    text: String,
    contentDescription: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(0.75.dp, accent.copy(alpha = 0.68f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = accent,
        )
    }
}

private val WorkflowMode.accent: Color
    get() = when (this) {
        WorkflowMode.CHAT -> ChatModeAccent
        WorkflowMode.PLAN -> PlanModeAccent
        WorkflowMode.GOAL -> GoalModeAccent
    }

private val AgentPermissionMode.accent: Color
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> FullAccessAccent
        AgentPermissionMode.SMART_APPROVAL -> SmartApprovalAccent
        AgentPermissionMode.MANUAL_APPROVAL -> ManualApprovalAccent
    }

private val WorkflowMode.popupSummary: String
    get() = when (this) {
        WorkflowMode.CHAT -> "直接交流与执行"
        WorkflowMode.PLAN -> "先拆解步骤与验证方式"
        WorkflowMode.GOAL -> "围绕目标持续跟踪完成条件"
    }

private val AgentPermissionMode.popupSummary: String
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> "常规操作自动执行"
        AgentPermissionMode.SMART_APPROVAL -> "高风险操作转人工确认"
        AgentPermissionMode.MANUAL_APPROVAL -> "写入与敏感操作先确认"
    }

private val AgentPermissionMode.settingsLabel: String
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> "完全访问"
        AgentPermissionMode.SMART_APPROVAL -> "智能审批"
        AgentPermissionMode.MANUAL_APPROVAL -> "手动审批"
    }

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    workflowMode: WorkflowMode = WorkflowMode.CHAT,
    selectedSkillIds: List<String> = emptyList(),
    availableSkills: List<io.github.mangi.eta.ui.model.SkillChoiceUi> = emptyList(),
    onSelectedSkillIdsChange: (List<String>) -> Unit = {},
    skillChoicesLoading: Boolean = false,
    skillChoicesLoadFailed: Boolean = false,
    onRetrySkillChoices: () -> Unit = {},
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    onInputSyncApplied: (Int, String) -> Unit = { _, _ -> },
    focusInputOnSync: Boolean = true,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onWorkflowModeChange: (WorkflowMode) -> Unit = {},
    onCompactContext: () -> Unit,
    onConfigureContextBudget: () -> Unit = {},
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Boolean,
    onInterruptAndSubmit: (String) -> Boolean = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onNewConversation: () -> Unit = {},
    onOpenMcp: (() -> Unit)? = null,
    onOpenTools: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val draftEditor = rememberConversationEditor(initialText = if (isEditingMessage) "" else input)
    val draftStatus by draftEditor.status.collectAsState()
    // Editing a historical message never overwrites the unsent draft for this conversation.
    val editFieldState = rememberTextFieldState(initialText = input)
    val textFieldState = if (isEditingMessage) editFieldState else draftEditor.field
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val recognitionEnabled = speechSettings?.recognitionEnabled == true
    val permissionMode by AgentPermissionPolicy.modeFlow()
        .collectAsState(initial = AgentPermissionMode.SMART_APPROVAL)
    val settingsScope = rememberCoroutineScope()
    var showSessionControls by remember { mutableStateOf(false) }
    var voicePanelOpen by remember { mutableStateOf(false) }
    val speechInsertion = remember(textFieldState) { SpeechDraftInsertion(textFieldState) }
    val dictation = rememberSpeechInput(onPreview = { preview ->
        if (speechInsertion.update(preview) && !isEditingMessage) draftEditor.onUserEdit()
    }) { recognized ->
        if (speechInsertion.commit(recognized) && !isEditingMessage) draftEditor.onUserEdit()
    }
    val startDictation = { speechInsertion.detach(); speechInsertion.begin(); voicePanelOpen = true }
    val finishDictation = {
        voicePanelOpen = false
        if (dictation.state.value.active) dictation.finish() else speechInsertion.detach()
    }
    val cancelDictation = { voicePanelOpen = false; dictation.cancel(); speechInsertion.cancel() }
    LaunchedEffect(isEditingMessage, recognitionEnabled, inputSyncVersion) { cancelDictation() }
    DisposableEffect(dictation, speechInsertion) {
        onDispose { dictation.cancel(); speechInsertion.cancel() }
    }
    var syncedVersion by remember { mutableIntStateOf(inputSyncVersion) }
    val draftReady = isEditingMessage || draftStatus == ConversationDraftStatus.READY || draftStatus == ConversationDraftStatus.SAVE_FAILED
    val skillSelectionCallback by androidx.compose.runtime.rememberUpdatedState(onSelectedSkillIdsChange)
    LaunchedEffect(textFieldState, draftReady, availableSkills, selectedSkillIds, isEditingMessage) {
        if (!draftReady || isEditingMessage || availableSkills.isEmpty()) return@LaunchedEffect
        val codec = io.github.mangi.eta.agent.model.AgentInvocationReferences
        val references = codec.parse(textFieldState.text)
        val missing = availableSkills.filter { skill -> skill.id in selectedSkillIds && references.none { it.isSkill && it.id == skill.id } }
        if (missing.isNotEmpty()) {
            textFieldState.edit {
                missing.forEach { skill -> append(" " + codec.token("Skill", skill.id, skill.name) + " ") }
            }
            draftEditor.onUserEdit()
        }
        var previous = codec.parse(textFieldState.text).filter { it.isSkill }.mapNotNull { it.id }.toSet()
        androidx.compose.runtime.snapshotFlow { textFieldState.text.toString() }.collect { text ->
            val current = codec.parse(text).filter { it.isSkill }.mapNotNull { it.id }.toSet()
            val removed = previous - current
            if (removed.isNotEmpty()) skillSelectionCallback(selectedSkillIds.filterNot { it in removed })
            previous = current
        }
    }
    val canSend = draftReady && (textFieldState.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty())
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var popupAnchorY by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (popupAnchorY - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage, inputSyncVersion) {
        // 编辑态与排队消息撤回由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        val versionChanged = inputSyncVersion != syncedVersion
        if (isEditingMessage) editFieldState.setTextAndPlaceCursorAtEnd(input)
        else if (versionChanged) draftEditor.replace(input)
        if (isEditingMessage || (versionChanged && input.isNotEmpty() && focusInputOnSync)) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        syncedVersion = inputSyncVersion
        if (!isEditingMessage) {
            // Transfer external input once. A later remount restores the newer local draft instead
            // of replaying stale share/recall text kept in the business model.
            draftEditor.persistNow()
            onInputSyncApplied(inputSyncVersion, input)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (!isEditingMessage) ConversationDraftFeedback(draftEditor)
        if (!isEditingMessage) io.github.mangi.eta.ui.screens.capability.DshComposerPlugins(generating = isStreaming || isCompacting)
        if (!isEditingMessage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        popupAnchorY = coordinates.positionInWindow().y.roundToInt()
                    }
                    .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentSessionStatusStrip(
                    mode = workflowMode,
                    permissionMode = permissionMode,
                    enabled = !isStreaming,
                    onModeClick = {
                        keyboard?.hide()
                        showSessionControls = true
                    },
                    onPermissionClick = {
                        keyboard?.hide()
                        showSessionControls = true
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                AgentNewConversationFloatingButton(
                    onClick = onNewConversation,
                )
            }
        }
        if (recognitionEnabled) {
            SpeechInputFeedback(dictation, showActiveOverlay = false)
            SpeechDictationOverlay(dictation, voicePanelOpen, startDictation, cancelDictation, finishDictation)
        }
        AnimatedVisibility(
            visible = queuedMessages.isNotEmpty(),
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            AgentChatQueueStrip(
                queuedMessages = queuedMessages,
                isStreaming = isStreaming,
                onSteer = onSteerQueuedMessage,
                onRecall = { id -> onRecallQueuedMessage(id, textFieldState.text.toString()) },
                onRemove = onRemoveQueuedMessage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    stringResource(R.string.chat_edit_preserve_following)
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 与输入框内正文同一起点（容器 10dp + 文本 8dp）。
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            val glass = LocalEtaDesignTokens.current.skin.isGlass
            ConversationComposerSurface {
                val character = LocalRoleplayChat.current
                ConversationTextEditor(
                    state = textFieldState,
                    onUserEdit = {
                        // IME edits take over the visible transcript; do not mutate the field
                        // recursively from inside its InputTransformation.
                        if (dictation.state.value.active || voicePanelOpen) {
                            speechInsertion.detach()
                            dictation.cancel()
                            voicePanelOpen = false
                        }
                        if (!isEditingMessage) draftEditor.onUserEdit()
                    },
                    placeholder = when {
                        character != null && isStreaming -> stringResource(R.string.chat_character_typing, character.characterName)
                        character != null -> stringResource(R.string.chat_character_input_hint, character.characterName)
                        isStreaming -> stringResource(R.string.chat_eta_working)
                        else -> stringResource(R.string.chat_input_hint)
                    },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            AgentAttachmentPickerButton(
                                popupAnchorTopPx = popupAnchorY,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onAttachImage = onAttachImage,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                                onInsertText = { reference ->
                                    val current = textFieldState.text.toString()
                                    val range = textFieldState.selection
                                    val needsSpace = range.min > 0 &&
                                        !current[range.min - 1].isWhitespace()
                                    val insertion = (if (needsSpace) " " else "") + reference
                                    textFieldState.edit {
                                        replace(range.min, range.max, insertion)
                                        selection = TextRange(range.min + insertion.length)
                                    }
                                    if (!isEditingMessage) draftEditor.onUserEdit()
                                },
                                availableSkills = availableSkills,
                                onRemoveReference = { kind, id ->
                                    val references = io.github.mangi.eta.agent.model.AgentInvocationReferences.parse(textFieldState.text)
                                    textFieldState.edit {
                                        references.filter { it.kind == kind && it.id == id }.asReversed().forEach { ref ->
                                            replace(ref.start, ref.end, "")
                                        }
                                    }
                                    if (!isEditingMessage) draftEditor.onUserEdit()
                                },
                                selectedSkillIds = selectedSkillIds,
                                onSelectedSkillIdsChange = onSelectedSkillIdsChange,
                                skillChoicesLoading = skillChoicesLoading,
                                skillChoicesLoadFailed = skillChoicesLoadFailed,
                                onRetrySkillChoices = onRetrySkillChoices,
                                onOpenMcp = onOpenMcp,
                                onOpenTools = onOpenTools,
                            )

                            if (onOpenSettings != null) {
                                IconButton(
                                    onClick = {
                                        keyboard?.hide()
                                        showSessionControls = true
                                    },
                                    minWidth = ChatInputActionSize,
                                    minHeight = ChatInputActionSize,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = stringResource(R.string.action_settings),
                                        modifier = Modifier.size(ChatInputActionIconSize),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }

                            if (availableReasoningEfforts.isNotEmpty()) {
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    enabled = !isStreaming,
                                    popupAnchorTopPx = popupAnchorY,
                                    popupMaxHeight = thinkingPopupMaxHeight,
                                    onEffortChange = onReasoningEffortChange,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (showContextUsage) {
                            AgentContextUsageButton(
                                usage = contextUsage,
                                isCompacting = isCompacting,
                                onCompact = onCompactContext,
                                onConfigure = onConfigureContextBudget,
                                canCompact = canCompactContext && !isStreaming,
                            )

                        }

                        if (recognitionEnabled) {
                            SpeechDictationButton(dictation, enabled = !isCompacting && draftReady,
                                onStart = startDictation, onFinish = finishDictation)
                        }

                        AgentModelPickerButton(
                            state = modelPickerState,
                            isStreaming = isStreaming,
                            onModelSelected = onModelSelected,
                        )

                        val showStreamingSend = isStreaming && !isCompacting && canSend
                        val submitCurrentDraft: (Boolean) -> Unit = { interrupt ->
                            if (canSend) {
                                voicePanelOpen = false
                                speechInsertion.detach()
                                dictation.cancel()
                                val submitted = if (isEditingMessage) null else draftEditor.snapshot()
                                val submittedText = textFieldState.text.toString()
                                val accepted = if (interrupt) onInterruptAndSubmit(submittedText) else onSubmit(submittedText)
                                if (accepted) {
                                    if (submitted != null) draftEditor.clearSubmitted(submitted)
                                    else if (textFieldState.text.toString() == submittedText) textFieldState.clearText()
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showStreamingSend,
                            enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.78f) + expandHorizontally(tween(160)),
                            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f) + shrinkHorizontally(tween(140)),
                        ) {
                            IconButton(onClick = onStop, minWidth = ChatInputActionSize, minHeight = ChatInputActionSize) {
                                Box(Modifier.size(SendButtonVisualSize).clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.chat_stop),
                                        modifier = Modifier.size(StopIconSize), tint = MiuixTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        var showSubmitChoices by remember { mutableStateOf(false) }
                        LaunchedEffect(showStreamingSend) { if (!showStreamingSend) showSubmitChoices = false }
                        val showPrimaryStop = isStreaming && !showStreamingSend
                        val sendBrush = when {
                            showPrimaryStop -> Brush.linearGradient(listOf(Color(0xFF34414C), Color(0xFF111920)))
                            canSend && glass -> Brush.linearGradient(
                                listOf(Color(0xFF8FDFFF), MiuixTheme.colorScheme.primary, Color(0xFF806BFF)),
                            )
                            canSend -> Brush.linearGradient(listOf(Color(0xFF455561), Color(0xFF202A32), Color(0xFF151D24)))
                            else -> Brush.linearGradient(
                                listOf(MiuixTheme.colorScheme.surfaceContainerHigh, MiuixTheme.colorScheme.surfaceContainer),
                            )
                        }
                        Box(contentAlignment = Alignment.TopEnd) {
                            Box(
                                modifier = Modifier
                                    .size(ChatInputActionSize)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = isStreaming || canSend,
                                        onClick = {
                                            when {
                                                showPrimaryStop -> onStop()
                                                showStreamingSend -> showSubmitChoices = true
                                                else -> submitCurrentDraft(false)
                                            }
                                        },
                                        onLongClick = if (showStreamingSend) {
                                            {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showSubmitChoices = true
                                            }
                                        } else null,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                            // 保留统一的点击区域，仅让可见圆形与相邻操作图标保持同一尺寸。
                            Box(
                                modifier = Modifier
                                    .size(SendButtonVisualSize)
                                    .clip(CircleShape)
                                    .dropShadow(
                                        shape = CircleShape,
                                        shadow = Shadow(
                                            radius = 5.dp,
                                            color = Color.Black,
                                            alpha = if (canSend) 0.15f else 0.07f,
                                        ),
                                    )
                                    .background(sendBrush)
                                    .border(
                                        width = 0.75.dp,
                                        color = if (canSend) Color.White.copy(alpha = 0.30f)
                                        else MiuixTheme.colorScheme.outline.copy(alpha = 0.14f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = if (canSend) 0.26f else 0.10f),
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = if (canSend) 0.07f else 0.02f),
                                                ),
                                            ),
                                        ),
                                )
                                AnimatedContent(
                                    targetState = showPrimaryStop,
                                    transitionSpec = {
                                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                            .togetherWith(
                                                fadeOut(tween(90)) +
                                                    scaleOut(tween(110), targetScale = 0.72f)
                                            )
                                    },
                                    label = "send_stop_icon",
                                ) { stopIcon ->
                                    Icon(
                                        imageVector = if (stopIcon) {
                                            Icons.Rounded.Stop
                                        } else {
                                            Icons.Rounded.ArrowUpward
                                        },
                                        contentDescription = when {
                                            stopIcon -> stringResource(R.string.chat_stop)
                                            isEditingMessage && preserveFollowingMessages -> stringResource(R.string.chat_save_message)
                                            showStreamingSend -> stringResource(R.string.chat_queue_send)
                                            else -> stringResource(R.string.chat_send)
                                        },
                                        modifier = Modifier.size(
                                            if (stopIcon) StopIconSize else SendIconSize
                                        ),
                                        tint = when {
                                            stopIcon -> MiuixTheme.colorScheme.surface
                                            canSend -> MiuixTheme.colorScheme.onPrimary
                                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                        },
                                    )
                                }
                            }
                            }
                            WindowListPopup(
                                show = showStreamingSend && showSubmitChoices && popupAnchorY > 0,
                                onDismissRequest = { showSubmitChoices = false },
                                popupPositionProvider = remember(popupAnchorY) { InputPopupPositionProvider(popupAnchorY) },
                                alignment = PopupPositionProvider.Align.TopStart,
                            ) {
                                ListPopupColumn {
                                    DropdownImpl(text = stringResource(R.string.chat_queue_send), optionSize = 2,
                                        isSelected = false, index = 0, onSelectedIndexChange = {
                                        showSubmitChoices = false; submitCurrentDraft(false)
                                    })
                                    DropdownImpl(text = stringResource(R.string.chat_interrupt_send), optionSize = 2,
                                        isSelected = false, index = 1, onSelectedIndexChange = {
                                        showSubmitChoices = false; submitCurrentDraft(true)
                                    })
                                }
                            }
                        }
                }
            }
        }
    }

    SessionControlsPopup(
        show = showSessionControls,
        workflowMode = workflowMode,
        permissionMode = permissionMode,
        enabled = !isStreaming,
        popupAnchorTopPx = popupAnchorY,
        popupMaxHeight = thinkingPopupMaxHeight,
        onWorkflowModeChange = onWorkflowModeChange,
        onPermissionModeChange = { mode ->
            settingsScope.launch { AgentPermissionPolicy.setMode(mode) }
        },
        onDismiss = { showSessionControls = false },
    )
}

@Composable
private fun AgentNewConversationFloatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .widthIn(min = 108.dp, max = 150.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .border(0.75.dp, colors.primary.copy(alpha = 0.48f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.primary,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.action_new_conversation_pill),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionControlsPopup(
    show: Boolean,
    workflowMode: WorkflowMode,
    permissionMode: AgentPermissionMode,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onWorkflowModeChange: (WorkflowMode) -> Unit,
    onPermissionModeChange: (AgentPermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ComposerPopup(
        show = show,
        anchorTopPx = popupAnchorTopPx,
        onDismiss = onDismiss,
        maxHeight = popupMaxHeight,
    ) {
        Column(
            modifier = Modifier
                .width(ChatInputPopupWidth)
                .verticalScroll(rememberScrollState())
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "运行与审批",
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭会话设置",
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            SessionPopupSectionLabel("运行模式", workflowMode.accent)
            listOf(WorkflowMode.CHAT, WorkflowMode.PLAN).forEach { option ->
                SessionPopupOption(
                    initial = option.initial,
                    title = option.title,
                    selected = workflowMode == option || (option == WorkflowMode.CHAT && workflowMode == WorkflowMode.GOAL),
                    accent = option.accent,
                    enabled = enabled,
                    onClick = { onWorkflowModeChange(option) },
                )
            }
            SessionPopupSectionLabel("持久任务", GoalModeAccent)
            SessionPopupOption(
                initial = WorkflowMode.GOAL.initial,
                title = "持久目标",
                selected = workflowMode == WorkflowMode.GOAL,
                accent = GoalModeAccent,
                enabled = enabled,
                onClick = { onWorkflowModeChange(if (workflowMode == WorkflowMode.GOAL) WorkflowMode.CHAT else WorkflowMode.GOAL) },
            )
            SessionPopupSectionLabel("审批权限", permissionMode.accent)
            AgentPermissionMode.entries.forEach { option ->
                SessionPopupOption(
                    initial = option.initial,
                    title = option.settingsLabel,
                    selected = permissionMode == option,
                    accent = option.accent,
                    enabled = enabled,
                    onClick = { onPermissionModeChange(option) },
                )
            }
        }
    }
}

@Composable
private fun SessionPopupSectionLabel(
    text: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(5.dp).background(accent, CircleShape))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = accent,
        )
    }
}

@Composable
private fun SessionPopupOption(
    initial: String,
    title: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .squircleSurface(
                if (selected) accent.copy(alpha = 0.16f)
                else composerPopupItemColor(),
                13.dp,
            )
            .squircleBorder(
                0.5.dp,
                if (selected) accent.copy(alpha = 0.42f)
                else MiuixTheme.colorScheme.outline.copy(alpha = 0.16f),
                13.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .squircleSurface(accent.copy(alpha = if (selected) 0.94f else 0.14f), 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) Color.White else accent,
            )
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
        }
    }
}

/** 思考强度选择保持为单一图标，当前状态仅通过图标颜色表达。 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.size > 1
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showPopup = false
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            enabled = menuEnabled,
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = stringResource(R.string.chat_reasoning_effort, effort.displayName),
                modifier = Modifier.size(ThinkingIconSize),
                tint = contentColor,
            )
        }
        ComposerPopup(
            show = showPopup && menuEnabled,
            anchorTopPx = popupAnchorTopPx,
            onDismiss = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            Column(
                modifier = Modifier
                    .width(ChatInputPopupWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 7.dp, top = 2.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "思考强度",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = effort.thinkingLabel,
                        style = MiuixTheme.textStyles.footnote2,
                        color = effort.thinkingAccent,
                    )
                }
                options.forEach { option ->
                    SessionPopupOption(
                        initial = option.thinkingInitial,
                        title = option.thinkingLabel,
                        selected = option == effort,
                        accent = option.thinkingAccent,
                        enabled = true,
                        onClick = {
                            onEffortChange(option)
                            showPopup = false
                        },
                    )
                }
            }
        }
    }
}

private val ReasoningEffort.thinkingInitial: String
    get() = when (this) {
        ReasoningEffort.OFF -> "Z"
        ReasoningEffort.DEFAULT -> "D"
        ReasoningEffort.MINIMAL -> "L"
        ReasoningEffort.LOW -> "L"
        ReasoningEffort.MEDIUM -> "M"
        ReasoningEffort.HIGH -> "H"
        ReasoningEffort.XHIGH -> "X"
        ReasoningEffort.MAX -> "M"
    }

private val ReasoningEffort.thinkingLabel: String
    get() = when (this) {
        ReasoningEffort.OFF -> "关闭"
        ReasoningEffort.DEFAULT -> "标准"
        ReasoningEffort.MINIMAL -> "极简"
        ReasoningEffort.LOW -> "轻量"
        ReasoningEffort.MEDIUM -> "平衡"
        ReasoningEffort.HIGH -> "深入"
        ReasoningEffort.XHIGH -> "深入+"
        ReasoningEffort.MAX -> "最大"
    }

private val ReasoningEffort.thinkingPopupSummary: String
    get() = when (this) {
        ReasoningEffort.OFF -> "不启用额外推理"
        ReasoningEffort.DEFAULT -> "由模型决定推理深度"
        ReasoningEffort.MINIMAL -> "优先速度，只做必要推理"
        ReasoningEffort.LOW -> "适合日常问答与快速处理"
        ReasoningEffort.MEDIUM -> "速度与推理质量平衡"
        ReasoningEffort.HIGH -> "适合复杂分析与规划"
        ReasoningEffort.XHIGH -> "用于高难任务与长链路推理"
        ReasoningEffort.MAX -> "消耗更多时间与上下文"
    }

private val ReasoningEffort.thinkingAccent: Color
    get() = when (this) {
        ReasoningEffort.OFF -> Color(0xFF8D939E)
        ReasoningEffort.DEFAULT -> Color(0xFF38A9B7)
        ReasoningEffort.MINIMAL -> Color(0xFF4F86FF)
        ReasoningEffort.LOW -> Color(0xFF6C76E8)
        ReasoningEffort.MEDIUM -> Color(0xFF9471E8)
        ReasoningEffort.HIGH -> Color(0xFFC46ED2)
        ReasoningEffort.XHIGH -> Color(0xFFE2649C)
        ReasoningEffort.MAX -> Color(0xFFF06C5B)
    }

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
internal fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(images.size) }
    LaunchedEffect(images.size) {
        if (images.size > previousCount && images.isNotEmpty()) {
            listState.animateScrollToItem(images.lastIndex)
        }
        previousCount = images.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = images,
            key = { it.id },
        ) { image ->
            Box(
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
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 可见圆点保持小巧，触控区放大到右上角 32dp，避免误触缩略图。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clickable(interactionSource = null, indication = null) { onRemoveImage(image.id) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ui_remove_image_089db3),
                            modifier = Modifier.size(11.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
