package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPinSwipeTest {
    @Test
    fun dragIsClampedToTheRevealWidth() {
        assertEquals(24f, conversationPinDragOffset(0f, 24f, 72f), 0f)
        assertEquals(72f, conversationPinDragOffset(60f, 20f, 72f), 0f)
        assertEquals(0f, conversationPinDragOffset(16f, -40f, 72f), 0f)
        assertEquals(72f, conversationPinDragOffset(64f, 40f, 72f), 0f)
    }

    @Test
    fun invalidGestureValuesCannotProduceAnInvalidOffset() {
        assertEquals(0f, conversationPinDragOffset(Float.NaN, 20f, 72f), 0f)
        assertEquals(32f, conversationPinDragOffset(32f, Float.POSITIVE_INFINITY, 72f), 0f)
        assertEquals(0f, conversationPinDragOffset(32f, 20f, Float.NaN), 0f)
    }

    @Test
    fun commitRequiresAValidThresholdToBeCrossed() {
        assertFalse(shouldCommitConversationPin(63f, 64f))
        assertTrue(shouldCommitConversationPin(64f, 64f))
        assertTrue(shouldCommitConversationPin(72f, 64f))
        assertFalse(shouldCommitConversationPin(Float.NaN, 64f))
        assertFalse(shouldCommitConversationPin(72f, Float.NaN))
    }
}
