package io.github.mangi.eta.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputTouchTargetTest {
    @Test
    fun sharedComposerActionsMeetTheFortyEightDpAccessibilityTarget() {
        assertTrue("Composer action targets must be at least 48 dp", ChatInputActionSize.value >= 48f)
    }
}
