package io.github.mangi.eta.ui.screens.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityAccessibilityLabelTest {
    @Test
    fun sourceDeleteLabelKeepsLocalizedActionAndSourceName() {
        assertEquals("Delete OpenAI Skills", capabilitySourceDeleteDescription("Delete", "OpenAI Skills"))
        assertEquals("删除 OpenAI Skills", capabilitySourceDeleteDescription("删除", "OpenAI Skills"))
        assertEquals("刪除 OpenAI Skills", capabilitySourceDeleteDescription("刪除", "OpenAI Skills"))
    }
}
