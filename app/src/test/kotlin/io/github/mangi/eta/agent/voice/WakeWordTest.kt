package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun normalizesAndMatchesMultipleWords() {
        val config = WakeWordConfig(enabled = true, words = listOf("小 Eta", "助手"))
        assertTrue(WakeWordMatcher.matches("你好，小 ETA，打开设置", config))
        assertTrue(WakeWordMatcher.matches("助手", config))
        assertFalse(WakeWordMatcher.matches("打开设置", config))
    }

    @Test
    fun disabledGateAllowsAnyTranscript() {
        assertTrue(WakeWordMatcher.matches("任意内容", WakeWordConfig(enabled = false)))
    }
}
