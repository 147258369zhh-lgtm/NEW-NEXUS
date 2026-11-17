package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSwipeGesturePolicyTest {
    @Test
    fun topZoneSwipesSwitchMode() {
        assertEquals(
            HomeSwipeGestureAction.SwitchConversationMeeting,
            HomeSwipeGesturePolicy.classify(500f, 1_000f, 90f, 72f, startY = 120f, height = 1_000f),
        )
        assertEquals(
            HomeSwipeGestureAction.SwitchConversationMeeting,
            HomeSwipeGesturePolicy.classify(500f, 1_000f, -90f, 72f, startY = 120f, height = 1_000f),
        )
    }

    @Test
    fun rightEdgeLeftSwipeOpensInsights() {
        assertEquals(
            HomeSwipeGestureAction.OpenInsights,
            HomeSwipeGesturePolicy.classify(900f, 1_000f, -90f, 72f, startY = 700f, height = 1_000f),
        )
    }

    @Test
    fun oppositeDirectionsAndShortOrInvalidGesturesDoNothing() {
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(900f, 1_000f, 120f, 72f, 700f, 1_000f))
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(500f, 1_000f, 40f, 72f, 120f, 1_000f))
        assertEquals(HomeSwipeGestureAction.None, HomeSwipeGesturePolicy.classify(500f, 0f, 120f, 72f, 120f, 1_000f))
    }

    @Test
    fun lowerMiddleGesturesDoNothing() {
        assertEquals(
            HomeSwipeGestureAction.None,
            HomeSwipeGesturePolicy.classify(500f, 1_000f, 120f, 72f, startY = 700f, height = 1_000f),
        )
    }
}
