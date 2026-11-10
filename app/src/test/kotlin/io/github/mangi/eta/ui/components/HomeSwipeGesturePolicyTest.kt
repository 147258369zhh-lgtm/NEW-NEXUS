package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSwipeGesturePolicyTest {
    @Test
    fun middleRightSwipeSwitchesMode() {
        assertEquals(
            HomeSwipeGestureAction.SwitchConversationMeeting,
            HomeSwipeGesturePolicy.classify(startX = 500f, width = 1_000f, totalDragX = 90f, thresholdPx = 72f),
        )
    }

    @Test
    fun rightEdgeLeftSwipeOpensInsights() {
        assertEquals(
            HomeSwipeGestureAction.OpenInsights,
            HomeSwipeGesturePolicy.classify(startX = 900f, width = 1_000f, totalDragX = -90f, thresholdPx = 72f),
        )
    }

    @Test
    fun oppositeDirectionsAndShortOrInvalidGesturesDoNothing() {
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(500f, 1_000f, -120f, 72f))
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(900f, 1_000f, 120f, 72f))
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(500f, 1_000f, 40f, 72f))
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(500f, 0f, 120f, 72f))
    }

    @Test
    fun gesturesStartingInTopBarOrComposerDoNothing() {
        assertEquals(
            HomeSwipeGestureAction.None,
            HomeSwipeGesturePolicy.classify(500f, 1_000f, 120f, 72f, startY = 60f, height = 1_000f),
        )
        assertEquals(
            HomeSwipeGestureAction.None,
            HomeSwipeGesturePolicy.classify(900f, 1_000f, -120f, 72f, startY = 900f, height = 1_000f),
        )
    }
}
