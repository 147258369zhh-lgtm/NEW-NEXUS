package io.github.mangi.eta.ui.pages.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineImageKeyReplacementTest {
    @Test
    fun blankOrUnchangedInputPreservesSavedKey() {
        assertNull(onlineImageKeyReplacement("", false))
        assertNull(onlineImageKeyReplacement("", true))
        assertNull(onlineImageKeyReplacement("   ", true))
        assertNull(onlineImageKeyReplacement("old", false))
    }

    @Test
    fun nonblankChangedInputReplacesSavedKey() {
        assertEquals("new-key", onlineImageKeyReplacement("new-key", true))
    }
}
