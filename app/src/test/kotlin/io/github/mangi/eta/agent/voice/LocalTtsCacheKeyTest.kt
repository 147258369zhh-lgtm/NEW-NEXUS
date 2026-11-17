package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalTtsCacheKeyTest {
    @Test
    fun changingVoiceOrLocaleInvalidatesCachedEngine() {
        val key = LocalTtsCacheKey("model", LocalRuntimeBackendPolicy.CPU, 1f, "voice-a", "en-US")

        assertEquals(key, key.copy())
        assertNotEquals(key, key.copy(voiceId = "voice-b"))
        assertNotEquals(key, key.copy(locale = "zh-CN"))
    }
}
