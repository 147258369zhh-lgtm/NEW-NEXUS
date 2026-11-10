package io.github.mangi.eta.ui.components

internal enum class HomeSwipeGestureAction {
    OpenInsights,
    SwitchConversationMeeting,
    None,
}

/**
 * Reserves the middle of the screen for a deliberate right swipe and the right edge for a
 * back-like left swipe. Starting outside those zones, reversing direction, or moving less than
 * the threshold does nothing so vertical scrolling and text selection remain predictable.
 */
internal object HomeSwipeGesturePolicy {
    private const val EDGE_START_FRACTION = 0.78f
    private const val MIDDLE_START_FRACTION = 0.18f
    private const val MIDDLE_END_FRACTION = 0.75f
    private const val GESTURE_START_Y_FRACTION = 0.10f
    private const val GESTURE_END_Y_FRACTION = 0.84f

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
        val fraction = (startX / width).coerceIn(0f, 1f)
        if (startY != null || height != null) {
            if (startY == null || height == null || !startY.isFinite() || !height.isFinite() || height <= 0f) {
                return HomeSwipeGestureAction.None
            }
            val verticalFraction = startY / height
            if (verticalFraction !in GESTURE_START_Y_FRACTION..GESTURE_END_Y_FRACTION) {
                return HomeSwipeGestureAction.None
            }
        }
        return when {
            fraction >= EDGE_START_FRACTION && totalDragX <= -thresholdPx ->
                HomeSwipeGestureAction.OpenInsights
            fraction in MIDDLE_START_FRACTION..MIDDLE_END_FRACTION && totalDragX >= thresholdPx ->
                HomeSwipeGestureAction.SwitchConversationMeeting
            else -> HomeSwipeGestureAction.None
        }
    }
}
