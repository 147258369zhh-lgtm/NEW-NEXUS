package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPcmResamplerTest {
    @Test
    fun `same rate remains unchanged`() {
        val input = floatArrayOf(-1f, -.5f, 0f, .5f, 1f)
        assertArrayEquals(input, StreamingPcmResampler(24_000).process(input), 0f)
    }

    @Test
    fun `downsampling preserves roughly the expected number of frames`() {
        val resampler = StreamingPcmResampler(sourceRate = 48_000, targetRate = 24_000)
        val first = resampler.process(FloatArray(96) { it / 96f })
        val second = resampler.process(FloatArray(48) { it / 48f })
        assertEquals(48, first.size)
        assertTrue(second.size in 23..24)
        assertTrue(first.all(Float::isFinite))
        assertTrue(second.all(Float::isFinite))
    }
}
