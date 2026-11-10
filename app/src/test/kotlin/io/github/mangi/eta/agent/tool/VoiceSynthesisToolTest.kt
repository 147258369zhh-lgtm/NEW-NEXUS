package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.voice.pcmToWav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSynthesisToolTest {
    @Test
    fun outputNameCannotEscapeTheAudioDirectory() {
        assertEquals("safe.wav", VoiceSynthesisTool.outputFileName("../safe.wav", "正文"))
        assertEquals("safe.wav", VoiceSynthesisTool.outputFileName("C:\\temp\\safe.wav", "正文"))
        assertEquals("a-b.wav", VoiceSynthesisTool.outputFileName("a:b", "正文"))
    }

    @Test
    fun synthesizedWavUsesThePlaybackSampleRate() {
        val wav = pcmToWav(byteArrayOf(0, 0, 1, 0), sampleRate = 24_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24_000, header.getInt(24))
        assertEquals(48_000, header.getInt(28))
        assertTrue(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        assertTrue(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
    }
}
