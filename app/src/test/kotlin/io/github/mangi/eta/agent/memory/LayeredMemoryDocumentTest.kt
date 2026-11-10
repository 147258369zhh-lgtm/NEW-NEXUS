package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredMemoryDocumentTest {
    @Test
    fun parsesAndRendersPortableSections() {
        val document = LayeredMemoryDocument.parse("# 核心记忆\n用户喜欢简洁\n\n# 项目记忆\nEta Android")
        assertEquals("用户喜欢简洁", document.sections[MemoryLayer.CORE])
        assertTrue(document.render().contains("# 项目记忆"))
        assertEquals("用户喜欢简洁\n\nEta Android", document.promptContent(200))
    }

    @Test
    fun legacyTextBecomesCoreLayer() {
        val document = LayeredMemoryDocument.fromLegacy("用户偏好深色主题")
        assertEquals("用户偏好深色主题", document.sections[MemoryLayer.CORE])
    }
}
