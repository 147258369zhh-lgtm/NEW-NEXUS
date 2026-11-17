package io.github.mangi.eta.agent.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryDocumentSectionsTest {
    @Test fun editingOneSectionPreservesPreambleUnknownHeadingsAndOtherSectionsExactly() {
        val source = "原始前言\r\n保留空格  \r\n\r\n# 核心记忆\r\n中文回答\r\n\r\n# 自定义章节\r\n不要丢失  \r\n# 任务记忆\r\n当前任务\r\n"
        val core = MemoryDocumentSections.parts(source).first { it.title == "核心记忆" }
        val updated = MemoryDocumentSections.replace(source, core, "简短中文回答\r\n\r\n")
        assertEquals(source.substring(0, core.start) + "简短中文回答\r\n\r\n" + source.substring(core.end), updated)
        assertTrue(updated.contains("# 自定义章节\r\n不要丢失  \r\n"))
    }

    @Test fun viewingMissingCoreDoesNotCreateAnythingAndFirstEditAppendsOnlyThatSection() {
        val source = "旧格式前言，没有标题。"
        val core = MemoryDocumentSections.parts(source).first { it.title == "核心记忆" }
        assertTrue(core.missing)
        assertEquals("", MemoryDocumentSections.content(source, core))
        assertEquals(source, MemoryDocumentSections.replace(source, core, ""))
        assertEquals("$source\n\n# 核心记忆\n偏好中文", MemoryDocumentSections.replace(source, core, "偏好中文"))
        assertEquals(source, MemoryDocumentSections.content(source, MemoryDocumentSections.parts(source).first()))
    }
}
