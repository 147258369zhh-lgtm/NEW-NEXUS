package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRuntimeSettingsTest {
    @Test
    fun `runtime settings clamp unsafe native values`() {
        val value = LocalModelRuntimeSettings(
            threads = 100,
            contextWindow = 1,
            maxOutputTokens = 99_999,
            temperature = 9f,
            topK = 0,
            topP = 0f,
            idleUnloadMinutes = 0,
        ).validated()

        assertEquals(8, value.threads)
        assertEquals(1_024, value.contextWindow)
        assertEquals(8_192, value.maxOutputTokens)
        assertEquals(2f, value.temperature)
        assertEquals(1, value.topK)
        assertEquals(0.01f, value.topP)
        assertEquals(1, value.idleUnloadMinutes)
    }

    @Test
    fun `new models auto connect and auto start without user provisioning`() {
        val value = LocalModelRuntimeSettings()
        assertTrue(value.autoConnect)
        assertTrue(value.autoStart)
        assertFalse(value.imageInputEnabled)
        assertEquals(4_096, value.contextWindow)
    }
}
