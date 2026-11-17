package io.github.mangi.eta.ui.components

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.ConversationSubject
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class PaneDropDestination(
    val scope: String,
    val pinned: Boolean = false,
    val folderId: String? = null,
    val neighborId: String? = null,
)

@Stable
internal class PaneDragSession {
    var enabled by mutableStateOf(false)
    var subject by mutableStateOf<ConversationSubject?>(null)
    var hovered by mutableStateOf<PaneDropDestination?>(null)
    var after by mutableStateOf(false)
    var scrollDirection by mutableIntStateOf(0)
    var accepts: (ConversationSubject, PaneDropDestination) -> Boolean = { _, _ -> false }
    var place: (ConversationSubject, PaneDropDestination, Boolean) -> Unit = { _, _, _ -> }
    var listBounds: Rect = Rect.Zero
    var scrollEdge: Float = 0f
    val targets = mutableMapOf<Any, PaneDropArea>()
    private var lastPointer: Offset? = null
    fun updateAt(x: Float, y: Float): PaneDropDestination? {
        val item = subject ?: return null
        lastPointer = Offset(x, y)
        val area = targets.values.filter { it.bounds.contains(Offset(x, y)) && accepts(item, it.destination) }
            .minByOrNull { it.bounds.width * it.bounds.height }
        hovered = area?.destination
        after = area != null && area.destination.neighborId != null && y >= area.bounds.center.y
        updateScroll(y)
        return area?.destination
    }
    fun refreshTarget() { lastPointer?.let { updateAt(it.x, it.y) } }
    fun dropAt(event: DragAndDropEvent): Boolean {
        val item = subject ?: return false
        val native = event.toAndroidDragEvent()
        val destination = updateAt(native.x, native.y) ?: return false
        place(item, destination, after)
        finish()
        return true
    }
    fun updateScroll(y: Float) {
        scrollDirection = when {
            y < listBounds.top + scrollEdge -> -1
            y > listBounds.bottom - scrollEdge -> 1
            else -> 0
        }
    }
    fun finish() { subject = null; hovered = null; scrollDirection = 0; lastPointer = null }
}

internal data class PaneDropArea(val destination: PaneDropDestination, val bounds: Rect)

internal val LocalPaneDrag = staticCompositionLocalOf<PaneDragSession?> { null }
internal val LocalPaneDestinations = staticCompositionLocalOf<Map<ConversationSubject, PaneDropDestination>> { emptyMap() }
private const val PaneDragLabel = "nexa-pane-item"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.paneDragSource(subject: ConversationSubject, enabled: Boolean, onClick: () -> Unit): Modifier {
    val session = LocalPaneDrag.current ?: return this
    if (!enabled || !session.enabled) return this
    val haptics = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    return dragAndDropSource { _: Offset ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        session.subject = subject
        DragAndDropTransferData(ClipData.newPlainText(PaneDragLabel, subject.key), localState = subject)
    }.pointerInput(subject) {
        detectPaneSourceTaps { currentOnClick() }
    }
}

/** Native drag sources consume taps even without an onTap; route short taps to the owning row. */
internal suspend fun PointerInputScope.detectPaneSourceTaps(onClick: () -> Unit) {
    awaitEachGesture {
        // Observe before the native drag detector consumes Main-pass down events. Keep the down
        // unconsumed so its existing long-press and mouse-drag detection remains available.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Mouse input already reaches the row unless dragging starts. Preserve its native
        // primary/secondary-button handling instead of turning right clicks into selection.
        if (down.type == PointerType.Mouse) return@awaitEachGesture
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            awaitPaneTapRelease(down)
        }
        if (up != null) {
            up.consume()
            onClick()
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitPaneTapRelease(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: return null
        if (change.id != down.id || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
            (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return null
        if (change.changedToUpIgnoreConsumed()) return change
        // Scroll and native mouse drag take ownership in Main; do not also select their row.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return null
    }
}

@Composable
internal fun Modifier.paneDropTarget(destination: PaneDropDestination): Modifier {
    val session = LocalPaneDrag.current ?: return this
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val targetKey = remember { Any() }
    val currentDestination by rememberUpdatedState(destination)
    val accent = MiuixTheme.colorScheme.primary
    SideEffect { session.targets[targetKey] = PaneDropArea(destination, bounds) }
    DisposableEffect(session, targetKey) { onDispose { session.targets.remove(targetKey) } }
    return onGloballyPositioned {
        bounds = it.boundsInRoot()
        session.targets[targetKey] = PaneDropArea(currentDestination, bounds)
    }
        .drawWithContent {
            drawContent()
            if (session.hovered == destination) {
                val y = if (session.after) size.height - 1.dp.toPx() else 1.dp.toPx()
                drawLine(accent, Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), 1.5.dp.toPx())
            }
        }
}

@Composable
internal fun Modifier.paneDragAutoScroll(): Modifier {
    val session = LocalPaneDrag.current ?: return this
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    return onGloballyPositioned {
        session.listBounds = it.boundsInRoot()
        session.scrollEdge = edge
    }
}

/** Only the stable pane is a native target. Lazy rows can recycle during a drag. */
@Composable
internal fun Modifier.paneDragHost(): Modifier {
    val session = LocalPaneDrag.current ?: return this
    return dragAndDropTarget(
        shouldStartDragAndDrop = { session.enabled && it.toAndroidDragEvent().clipDescription?.label == PaneDragLabel },
        target = remember(session) { object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) {
                val native = event.toAndroidDragEvent()
                session.updateAt(native.x, native.y)
            }
            override fun onEntered(event: DragAndDropEvent) = onMoved(event)
            override fun onExited(event: DragAndDropEvent) { session.scrollDirection = 0; session.hovered = null }
            override fun onEnded(event: DragAndDropEvent) = session.finish()
            // Re-resolve current bounds even if the finger stayed still while scrolling.
            override fun onDrop(event: DragAndDropEvent) = session.dropAt(event)
        } },
    )
}

@Composable
internal fun Modifier.paneItemDropTarget(subject: ConversationSubject): Modifier {
    val destination = LocalPaneDestinations.current[subject] ?: return this
    return paneDropTarget(destination)
}
