package io.github.mangi.eta.ui.components

import kotlin.math.abs

internal enum class HomeSwipeGestureAction {
    OpenUnifiedList,
    OpenInsights,
    SwitchConversationMeeting,
    None,
}

/**
 * Splits home gestures by vertical position. The top 40% switches the primary workspace, while
 * the bottom 60% belongs to side panels. In the lower zone, a left swipe from the right edge opens
 * insights; the unified list is owned by the left-edge drawer. Short gestures do nothing so
 * vertical scrolling and text selection remain predictable.
 */
internal object HomeSwipeGesturePolicy {
    const val LEFT_EDGE_END_FRACTION = 0.26f
    const val RIGHT_EDGE_START_FRACTION = 1f - LEFT_EDGE_END_FRACTION
    private const val LOWER_ZONE_START_FRACTION = 0.40f

    fun classify(
        startX: Float,
        width: Float,
        totalDragX: Float,
        thresholdPx: Float,
        startY: Float? = null,
        height: Float? = null,
    ): HomeSwipeGestureAction {
        if (!startX.isFinite() || !width.isFinite() || !totalDragX.isFinite() || !thresholdPx.isFinite()) {
            return HomeSwipeGestureAction.None
        }
        if (width <= 0f || thresholdPx <= 0f) return HomeSwipeGestureAction.None
        val horizontalFraction = (startX / width).coerceIn(0f, 1f)
        val verticalFraction = if (startY != null && height != null) {
            if (!startY.isFinite() || !height.isFinite() || height <= 0f) {
                return HomeSwipeGestureAction.None
            }
            (startY / height).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (verticalFraction < LOWER_ZONE_START_FRACTION) {
            return if (abs(totalDragX) >= thresholdPx) {
                HomeSwipeGestureAction.SwitchConversationMeeting
            } else {
                HomeSwipeGestureAction.None
            }
        }
        return when {
            horizontalFraction <= LEFT_EDGE_END_FRACTION && totalDragX >= thresholdPx ->
                HomeSwipeGestureAction.OpenUnifiedList
            horizontalFraction >= RIGHT_EDGE_START_FRACTION && totalDragX <= -thresholdPx ->
                HomeSwipeGestureAction.OpenInsights
            else -> HomeSwipeGestureAction.None
        }
    }
}
