package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.voice.SpeechDictationButton
import io.github.mangi.eta.ui.voice.SpeechInputFeedback
import io.github.mangi.eta.ui.voice.rememberSpeechInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Conversation note drawer. The note belongs to one conversation and never enters context until
 * the user explicitly asks an Agent to read or operate on it.
 */
@Composable
internal fun NexusNotepadDialog(
    context: Context,
    scopeId: String,
    onDismiss: () -> Unit,
    onAgentAction: (String) -> Unit = {},
) {
    key(scopeId) {
        NexusNotepadDialogContent(context, scopeId, onDismiss, onAgentAction)
    }
}

@Composable
private fun NotepadStatusPill(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .squircleSurface(
                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.64f),
                9.dp,
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun NotepadPromptChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .squircleSurface(
                if (enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.42f),
                13.dp,
            )
            .squircleBorder(
                0.5.dp,
                if (enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.24f)
                else MiuixTheme.colorScheme.outline.copy(alpha = 0.16f),
                13.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (enabled) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (enabled) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun NotepadActionPill(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = if (primary) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .dropShadow(
                shape = RoundedCornerShape(17.dp),
                shadow = Shadow(radius = 6.dp, color = Color.Black, alpha = 0.08f),
            )
            .squircleSurface(
                if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MiuixTheme.colorScheme.surface.copy(alpha = 0.48f),
                17.dp,
            )
            .squircleBorder(
                0.5.dp,
                accent.copy(alpha = 0.28f),
                17.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = accent.copy(alpha = if (enabled) 1f else 0.42f),
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = accent.copy(alpha = if (enabled) 1f else 0.42f),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, FlowPreview::class)
private fun NexusNotepadDialogContent(
    context: Context,
    scopeId: String,
    onDismiss: () -> Unit,
    onAgentAction: (String) -> Unit,
) {
    val store = remember(context, scopeId) { GoalPlanNotepadStore.get(context) }
    val scope = rememberCoroutineScope()
    val initialNote = remember(store, scopeId) { store.snapshot(scopeId).notepadRevision }
    var content by remember { mutableStateOf(initialNote?.content.orEmpty()) }
    var revision by remember { mutableLongStateOf(initialNote?.revision ?: 0L) }
    var error by remember(scopeId) { mutableStateOf<String?>(null) }
    var dirty by remember(scopeId) { mutableStateOf(false) }
    var saving by remember(scopeId) { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var discardConfirmation by remember { mutableStateOf(false) }
    val failureFocus = remember { BringIntoViewRequester() }
    val saveMutex = remember { Mutex() }
    val saveFailedMessage = stringResource(R.string.notepad_save_failed)
    val dictation = rememberSpeechInput { recognized ->
        if (!dismissing && !discardConfirmation && recognized.isNotBlank()) {
            content = listOf(content, recognized).filter(String::isNotBlank).joinToString("\n\n").take(1_000_000)
            dirty = true
            error = null
        }
    }
    var expanded by remember(scopeId) { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val heightFraction by animateFloatAsState(
        targetValue = if (expanded) 0.96f else 0.68f,
        animationSpec = tween(220),
        label = "notepad_height",
    )

    suspend fun save(): Boolean = saveMutex.withLock {
        if (!dirty) return@withLock true
        val pendingContent = content
        val expectedRevision = revision
        saving = true
        try {
            val result = withContext(Dispatchers.IO) {
                store.notepadManage(scopeId, JSONObject()
                    .put("action", "replace")
                    .put("expected_revision", expectedRevision)
                    .put("content", pendingContent))
            }
            if (result.optBoolean("ok")) {
                revision = result.optLong("revision", expectedRevision + 1)
                dirty = content != pendingContent
                error = null
                true
            } else {
                error = result.optString("message").ifBlank { saveFailedMessage }
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = saveFailedMessage
            false
        } finally {
            saving = false
        }
    }

    fun requestDismiss(action: (() -> Unit)? = null) {
        if (dismissing || discardConfirmation) return
        dismissing = true
        dictation.cancel()
        scope.launch {
            try {
                if (save()) {
                    action?.invoke()
                    onDismiss()
                } else {
                    discardConfirmation = true
                }
            } finally {
                dismissing = false
            }
        }
    }

    LaunchedEffect(scopeId) {
        // Observing only edits keeps an in-flight write alive when its own busy state changes.
        snapshotFlow { content }.debounce(900).collect {
            if (dirty && error == null && !dismissing) save()
        }
    }
    LaunchedEffect(error, discardConfirmation) {
        if (error != null) failureFocus.bringIntoView()
    }

    Dialog(
        onDismissRequest = {
            if (discardConfirmation) discardConfirmation = false else requestDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .background(Color.Black.copy(alpha = 0.38f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .etaGlassSurface(cornerRadius = 30.dp)
                    .squircleSurface(
                        MiuixTheme.colorScheme.surface.copy(alpha = 0.96f),
                        30.dp,
                    )
                    .border(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                    )
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .pointerInput(expanded) {
                            detectVerticalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragDistance += amount
                                },
                                onDragEnd = {
                                    when {
                                        dragDistance < -56f -> expanded = true
                                        dragDistance > 56f && expanded -> expanded = false
                                        dragDistance > 110f && !expanded -> requestDismiss()
                                    }
                                    dragDistance = 0f
                                },
                                onDragCancel = { dragDistance = 0f },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(3.dp)
                            .background(
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.28f),
                                RoundedCornerShape(50),
                            ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .dropShadow(
                                shape = RoundedCornerShape(15.dp),
                                shadow = Shadow(radius = 7.dp, color = Color.Black, alpha = 0.08f),
                            )
                            .squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f), 15.dp)
                            .squircleBorder(
                                0.5.dp,
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.30f),
                                15.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("绘画记事本", style = MiuixTheme.textStyles.title3)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NotepadStatusPill(if (dirty) "待保存" else "已保存")
                            NotepadStatusPill("Rev $revision")
                        }
                    }
                    SpeechDictationButton(dictation, enabled = !dismissing && !discardConfirmation)
                    NotepadActionPill(
                        label = if (expanded) "半屏" else "全屏",
                        icon = if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        onClick = { expanded = !expanded },
                        enabled = !dismissing,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    listOf(
                        Triple("整理", Icons.Rounded.AutoAwesome, "请整理当前会话记事本，保留事实、决定和待办，删除重复内容。"),
                        Triple("总结", Icons.Rounded.Check, "请总结当前会话记事本，突出结论、风险与下一步。"),
                        Triple("生成计划", Icons.Rounded.AutoAwesome, "请根据当前会话记事本生成可执行计划，标明依赖、风险和验证方式。"),
                        Triple("执行", Icons.Rounded.PlayArrow, "请阅读当前会话记事本，执行其中明确、可逆且低风险的待办；需要权限时先停止询问。"),
                    ).forEach { (label, icon, instruction) ->
                        NotepadPromptChip(
                            label = label,
                            icon = icon,
                            onClick = {
                                requestDismiss {
                                    onAgentAction("$instruction\n\n会话记事本（Revision $revision）：\n$content")
                                }
                            },
                            enabled = content.isNotBlank() && !saving && !dismissing && !discardConfirmation,
                        )
                    }
                }
                SpeechInputFeedback(dictation)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                        .squircleSurface(
                            MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.46f),
                            20.dp,
                        )
                        .squircleBorder(
                            0.5.dp,
                            MiuixTheme.colorScheme.outline.copy(alpha = 0.26f),
                            20.dp,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "手写记录",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${content.length} / 1,000,000",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
                        )
                    }
                    BasicTextField(
                        value = content,
                        onValueChange = { value ->
                            if (!dismissing && !discardConfirmation) {
                                content = value.take(1_000_000)
                                dirty = true
                                error = null
                            }
                        },
                        enabled = !dismissing && !discardConfirmation,
                        textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(failureFocus)) {
                    error?.let { message ->
                        Text(message, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
                    }
                    if (discardConfirmation) {
                        Text(
                            stringResource(R.string.notepad_discard_unsaved_message),
                            style = MiuixTheme.textStyles.body2,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NotepadActionPill(
                                label = stringResource(R.string.notepad_keep_editing),
                                icon = Icons.Rounded.EditNote,
                                onClick = { discardConfirmation = false },
                            )
                            NotepadActionPill(
                                label = stringResource(R.string.notepad_discard_and_close),
                                icon = Icons.Rounded.Close,
                                onClick = onDismiss,
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NotepadActionPill(
                        label = "关闭",
                        icon = Icons.Rounded.Close,
                        onClick = { requestDismiss() },
                        enabled = !dismissing && !discardConfirmation,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    NotepadActionPill(
                        label = if (saving) "保存中" else "保存",
                        icon = Icons.Rounded.Check,
                        primary = true,
                        onClick = {
                            scope.launch { save() }
                        },
                        enabled = dirty && !saving && !dismissing && !discardConfirmation,
                    )
                }
            }
        }
    }
}
