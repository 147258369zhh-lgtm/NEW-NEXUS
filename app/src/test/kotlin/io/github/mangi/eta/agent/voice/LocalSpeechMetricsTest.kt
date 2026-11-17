package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class LocalSpeechMetricsTest {
    @Test fun audioSpeedUsesRealSamplesAndDoesNotPretendToHaveTokenCacheCounters() {
        var time = 100L
        val meter = SpeechMetricsMeasurement { time }
        meter.input(ByteArray(32_000)); time += 120; meter.result(); time += 380
        val asr = meter.finish("asr", LocalSpeechKind.ASR, "CPU", "fixture", true, true, null)
        assertEquals(1_000L, asr.inputAudioMs); assertEquals(120L, asr.firstResultMs)
        assertEquals(.5, asr.realTimeFactor!!, .001)
        assertTrue(asr.json().isNull("tokenCacheHitRate"))
        assertTrue(asr.cachedModel)
        val ttsMeter = SpeechMetricsMeasurement { time }
        time += 80; ttsMeter.output(ByteArray(48_000)); time += 120
        val tts = ttsMeter.finish("tts", LocalSpeechKind.TTS, "CPU", "fixture", false, true, null)
        assertEquals(1_000L, tts.outputAudioMs); assertEquals(80L, tts.firstResultMs)
        assertEquals(.2, tts.realTimeFactor!!, .001)
    }

    @Test fun cancellationWithNoAudioHasNoMadeUpFirstResultOrSpeed() {
        val meter = SpeechMetricsMeasurement { 100 }
        val result = meter.finish("tts", LocalSpeechKind.TTS, "UNKNOWN", "fixture", false, false, "CANCELLED")
        assertNull(result.firstResultMs); assertNull(result.realTimeFactor)
        assertFalse(result.successful); assertEquals("CANCELLED", result.failureCode)
    }
}
