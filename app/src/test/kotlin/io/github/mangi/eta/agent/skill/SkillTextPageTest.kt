package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTextPageTest {
    @Test fun longInstructionsCanBeReassembledExactlyWithSmallPages() {
        val text = "A".repeat(16_383) + "😀" + "中".repeat(70_000) + "tail"
        val pages = mutableListOf<String>()
        var offset = 0
        do {
            val page = SkillTextPage.read(text, offset, 16_384)
            assertEquals(text.length, page.totalChars)
            assertFalse(page.text.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(page.text.firstOrNull()?.isLowSurrogate() == true)
            pages += page.text
            offset = page.nextOffset ?: break
            assertTrue(page.truncated)
        } while (true)
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun invalidOrSplitUnicodeOffsetsAreRejectedAndExactEndIsAnEmptyFinalPage() {
        val text = "a😀b"
        listOf(-1, text.length + 1, 2, Int.MAX_VALUE).forEach { offset ->
            assertThrows(IllegalArgumentException::class.java) { SkillTextPage.read(text, offset, 512) }
        }
        val end = SkillTextPage.read(text, text.length, 512)
        assertEquals("", end.text)
        assertFalse(end.truncated)
        assertNull(end.nextOffset)
        assertEquals("", SkillTextPage.read("", 0, 512).text)
    }
}
