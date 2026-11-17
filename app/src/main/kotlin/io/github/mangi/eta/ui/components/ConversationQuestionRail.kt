package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ConversationQuestionNode(val id: String, val label: String)
internal data class ConversationQuestionNavigation(
    val key: String?,
    val nodes: List<ConversationQuestionNode>,
    val loadWindow: suspend (String, Int) -> List<io.github.mangi.eta.ui.model.AgentChatMessageUi>,
    val enabled: Boolean,
)
internal val LocalConversationQuestionNavigation = staticCompositionLocalOf<ConversationQuestionNavigation?> { null }

/** The gesture is confined to its own narrow handle; normal content and side-swipes are untouched. */
@Composable
internal fun ConversationQuestionRail(
    nodes: List<ConversationQuestionNode>,
    currentIndex: Int,
    onSelect: (ConversationQuestionNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nodes.isEmpty()) return
    var dragging by remember(nodes) { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val latestSelect by rememberUpdatedState(onSelect)
    val reveal by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = .78f, stiffness = 560f), label = "question-rail")
    val colors = MiuixTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        AnimatedVisibility(dragging) {
            Column(Modifier.widthIn(max = 224.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("问题 ${selected + 1} / ${nodes.size}", style = MiuixTheme.textStyles.footnote2, color = colors.primary)
                Text(nodes.getOrNull(selected)?.label.orEmpty().ifBlank { "图片或附件" },
                    style = MiuixTheme.textStyles.footnote1, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Canvas(Modifier.width(38.dp).height(174.dp)
            .semantics {
                contentDescription = "聊天时间轴，长按滑动按问题定位"
                progressBarRangeInfo = ProgressBarRangeInfo(currentIndex.coerceIn(0, nodes.lastIndex).toFloat(), 0f..nodes.lastIndex.toFloat(), (nodes.lastIndex - 1).coerceAtLeast(0))
                setProgress { value -> latestSelect(nodes[value.roundToInt().coerceIn(0, nodes.lastIndex)]); true }
            }
            .pointerInput(nodes) {
                fun update(y: Float) {
                    val next = ((y / size.height).coerceIn(0f, 1f) * nodes.lastIndex).roundToInt()
                    if (next != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selected = next
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; update(it.y) },
                    onDrag = { change, _ -> change.consume(); update(change.position.y) },
                    onDragEnd = { dragging = false; latestSelect(nodes[selected.coerceIn(0, nodes.lastIndex)]) },
                    onDragCancel = { dragging = false },
                )
            }) {
            val center = size.width * .72f
            drawLine(colors.onSurfaceVariantSummary.copy(alpha = .26f), Offset(center, 9.dp.toPx()), Offset(center, size.height - 9.dp.toPx()),
                2.5.dp.toPx(), cap = StrokeCap.Round)
            if (reveal > .01f) {
                val pivot = if (dragging) selected else currentIndex
                repeat(17) { tick ->
                    val y = size.height * tick / 16f
                    val position = tick / 16f * nodes.lastIndex
                    val proximity = (1f - abs(position - pivot) / (nodes.size / 6f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val width = (5 + proximity * 18) * reveal * density
                    drawLine(colors.primary.copy(alpha = (.3f + proximity * .7f) * reveal), Offset(center - width, y), Offset(center, y), 1.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}
