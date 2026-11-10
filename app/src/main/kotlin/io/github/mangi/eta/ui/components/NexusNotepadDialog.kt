package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Conversation note drawer. The note belongs to one conversation and never enters context until
 * the user explicitly asks an Agent to read or operate on it.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NexusNotepadDialog(
    context: Context,
    scopeId: String,
    onDismiss: () -> Unit,
    onAgentAction: (String) -> Unit = {},
) {
    val store = remember(context, scopeId) { GoalPlanNotepadStore.get(context) }
    val scope = rememberCoroutineScope()
    var content by remember(scopeId) { mutableStateOf("") }
    var revision by remember(scopeId) { mutableLongStateOf(0L) }
    var error by remember(scopeId) { mutableStateOf<String?>(null) }
    var dirty by remember(scopeId) { mutableStateOf(false) }
    var saving by remember(scopeId) { mutableStateOf(false) }
    var expanded by remember(scopeId) { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val heightFraction by animateFloatAsState(
        targetValue = if (expanded) 0.96f else 0.62f,
        animationSpec = tween(220),
        label = "notepad_height",
    )

    fun reload() {
        val note = store.snapshot(scopeId).notepadRevision
        content = note?.content.orEmpty()
        revision = note?.revision ?: 0L
        dirty = false
        error = null
    }

    LaunchedEffect(scopeId) { reload() }

    LaunchedEffect(scopeId, content, revision, dirty, saving) {
        if (!dirty || saving) return@LaunchedEffect
        delay(900)
        val pendingContent = content
        val expectedRevision = revision
        saving = true
        val result = withContext(Dispatchers.IO) {
            store.notepadManage(scopeId, JSONObject()
                .put("action", "replace")
                .put("expected_revision", expectedRevision)
                .put("content", pendingContent))
        }
        saving = false
        if (result.optBoolean("ok")) {
            revision = result.optLong("revision", expectedRevision + 1)
            dirty = content != pendingContent
            error = null
        } else {
            error = result.optString("message").ifBlank { "自动保存失败" }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .background(
                        MiuixTheme.colorScheme.surface.copy(alpha = 0.98f),
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .border(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.34f),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
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
                                        dragDistance > 110f && !expanded -> onDismiss()
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
                            .height(4.dp)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("会话记事本", style = MiuixTheme.textStyles.title3)
                        Text(
                            "Revision $revision · 会话附件 · 按需交给 Agent",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(
                        text = if (expanded) "半屏" else "全屏",
                        onClick = { expanded = !expanded },
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf(
                        "整理" to "请整理当前会话记事本，保留事实、决定和待办，删除重复内容。",
                        "总结" to "请总结当前会话记事本，突出结论、风险与下一步。",
                        "生成计划" to "请根据当前会话记事本生成可执行计划，标明依赖、风险和验证方式。",
                        "执行" to "请阅读当前会话记事本，执行其中明确、可逆且低风险的待办；需要权限时先停止询问。",
                    ).forEach { (label, instruction) ->
                        TextButton(
                            text = label,
                            onClick = {
                                onAgentAction(
                                    "$instruction\n\n会话记事本（Revision $revision）：\n$content",
                                )
                                onDismiss()
                            },
                            enabled = content.isNotBlank() && !saving,
                        )
                    }
                }
                BasicTextField(
                    value = content,
                    onValueChange = { value ->
                        content = value.take(1_000_000)
                        dirty = true
                        error = null
                    },
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                            RoundedCornerShape(16.dp),
                        )
                        .border(
                            0.5.dp,
                            MiuixTheme.colorScheme.outline.copy(alpha = 0.3f),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(14.dp),
                )
                error?.let { message ->
                    Text(message, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "关闭", onClick = onDismiss)
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        text = if (saving) "保存中" else "保存",
                        onClick = {
                            saving = true
                            val pendingContent = content
                            val expectedRevision = revision
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    store.notepadManage(scopeId, JSONObject()
                                        .put("action", "replace")
                                        .put("expected_revision", expectedRevision)
                                        .put("content", pendingContent))
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    saving = false
                                    if (result.optBoolean("ok")) {
                                        revision = result.optLong("revision", expectedRevision + 1)
                                        dirty = content != pendingContent
                                        error = null
                                    } else {
                                        error = result.optString("message").ifBlank { "保存失败" }
                                    }
                                }
                            }
                        },
                        enabled = dirty && !saving,
                    )
                }
            }
        }
    }
}
