package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class InsightsPaneAnchor { Closed, Open }

/** Full-width siblings share one displacement; gesture frames only invalidate placement. */
@Composable
internal fun ConversationInsightsPaneScaffold(
    subjectKey: String,
    enabled: Boolean,
    paneContent: @Composable (onBack: () -> Unit) -> Unit,
    content: @Composable (paneActive: Boolean) -> Unit,
) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val velocityThresholdPx = with(density) { 400.dp.toPx() }
            val anchors = remember(widthPx) {
                DraggableAnchors {
                    InsightsPaneAnchor.Closed at 0f
                    InsightsPaneAnchor.Open at -widthPx
                }
            }
            val dragState = remember(subjectKey) {
                AnchoredDraggableState(initialValue = InsightsPaneAnchor.Closed, anchors = anchors)
            }
            val animation = remember {
                spring<Float>(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.5f)
            }
            var paneActive by remember(subjectKey) { mutableStateOf(false) }
            var settleJob by remember(subjectKey) { mutableStateOf<Job?>(null) }
            val currentEnabled by rememberUpdatedState(enabled)
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
            val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
            val dismiss: () -> Unit = {
                settleJob?.cancel()
                settleJob = scope.launch {
                    dragState.animateTo(InsightsPaneAnchor.Closed, animation)
                    paneActive = false
                }
            }
            DisposableEffect(subjectKey) {
                onDispose { settleJob?.cancel() }
            }
            SideEffect { dragState.updateAnchors(anchors) }
            LaunchedEffect(enabled) {
                if (!enabled && paneActive) dismiss()
            }
            NavigationBackHandler(
                state = navigationState,
                isBackEnabled = enabled && paneActive && lifecycleState == Lifecycle.State.RESUMED,
                onBackCompleted = dismiss,
            )
            Layout(
                modifier = Modifier.fillMaxSize().pointerInput(subjectKey, enabled, widthPx, lifecycleState) {
                    if (!enabled || lifecycleState != Lifecycle.State.RESUMED) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOpen = paneActive
                        if (!startedOpen && (
                            down.position.x < size.width * HomeSwipeGesturePolicy.RIGHT_EDGE_START_FRACTION ||
                                down.position.y < size.height * 0.40f
                            )) return@awaitEachGesture
                        val velocity = VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var acquired = false
                        val active = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            // Children get first refusal, including horizontal content scrollers.
                            val offset = dragState.offset
                            val canReveal = overSlop < 0f && offset > -widthPx + 0.5f
                            val canClose = overSlop > 0f && offset < -0.5f
                            if (canReveal || canClose) {
                                settleJob?.cancel()
                                paneActive = true
                                focusManager.clearFocus()
                                keyboard?.hide()
                                dragState.dispatchRawDelta(overSlop)
                                velocity.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                acquired = true
                            }
                        } ?: return@awaitEachGesture
                        if (!acquired) return@awaitEachGesture
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == active.id } ?: break
                                if (change.isConsumed) break
                                velocity.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) {
                                    released = true
                                    break
                                }
                                val delta = change.positionChange().x
                                if (delta != 0f) {
                                    dragState.dispatchRawDelta(delta)
                                    change.consume()
                                }
                            }
                        } finally {
                            val velocityX = velocity.calculateVelocity().x
                            val shouldOpen = if (!currentEnabled) false else if (!released) startedOpen else when {
                                velocityX <= -velocityThresholdPx -> true
                                velocityX >= velocityThresholdPx -> false
                                else -> -dragState.offset >= widthPx * 0.5f
                            }
                            settleJob = scope.launch {
                                dragState.animateTo(
                                    if (shouldOpen) InsightsPaneAnchor.Open else InsightsPaneAnchor.Closed,
                                    animation,
                                )
                                if (!shouldOpen) paneActive = false
                            }
                        }
                    }
                },
                content = {
                    Box(
                        Modifier.fillMaxSize()
                            .focusProperties { onEnter = { if (paneActive) cancelFocusChange() } }
                            .then(if (paneActive) Modifier.clearAndSetSemantics {} else Modifier),
                    ) { content(paneActive) }
                    Box(Modifier.fillMaxSize()) {
                        // No hidden metrics/file query, and canceled gestures release the page.
                        if (paneActive) paneContent(dismiss)
                    }
                },
            ) { measurables, constraints ->
                val childConstraints = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
                val page = measurables[0].measure(childConstraints)
                val panel = measurables[1].measure(childConstraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val offset = dragState.offset.takeIf { it.isFinite() }
                        ?.coerceIn(-widthPx, 0f)?.roundToInt() ?: 0
                    page.placeWithLayer(offset, 0)
                    panel.placeWithLayer(offset + page.width, 0)
                }
            }
        }
}
