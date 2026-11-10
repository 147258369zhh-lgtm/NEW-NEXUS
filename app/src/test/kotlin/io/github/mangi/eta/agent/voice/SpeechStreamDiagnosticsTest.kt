package io.github.mangi.eta.agent.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechStreamDiagnosticsTest {
    @Test fun endpoint_uses_audio_timeline_instead_of_inference_wall_clock() {
        val tracker = SpeechEndpointTracker(endpointSilenceMs = 600)
        tracker.consume(frame(samples = 1_600, amplitude = 0.2f))

        tracker.consume(frame(samples = 6_400, amplitude = 0f))
        assertFalse(tracker.reachedEndpoint())

        tracker.consume(frame(samples = 3_200, amplitude = 0f))
        assertTrue(tracker.reachedEndpoint())
        assertEquals(700L, tracker.durationMs)
    }

    @Test fun endpoint_does_not_fire_on_leading_silence() {
        val tracker = SpeechEndpointTracker(endpointSilenceMs = 600)
        repeat(10) { tracker.consume(frame(samples = 1_600, amplitude = 0f)) }

        assertFalse(tracker.reachedEndpoint())
    }

    @Test fun repeated_cumulative_transcript_does_not_postpone_endpoint() {
        val tracker = SpeechEndpointTracker(endpointSilenceMs = 600)
        tracker.consume(frame(samples = 1_600, amplitude = 0.2f))
        tracker.markTranscript()
        repeat(6) {
            tracker.consume(frame(samples = 1_600, amplitude = 0f))
            tracker.markTranscript()
        }

        assertTrue(tracker.reachedEndpoint())
    }

    @Test fun transcript_accumulator_keeps_old_finals_and_replaces_interim_text() {
        val transcript = SpeechTranscriptAccumulator()
        transcript.accept(listOf(segment("第一段", final = true, from = 0, to = 800)))
        transcript.accept(listOf(segment("第", final = false, from = 900, to = 1200)))
        val replaced = transcript.accept(listOf(segment("第二段", final = false, from = 900, to = 1500)))
        val completed = transcript.accept(listOf(segment("第二段完成", final = true, from = 900, to = 1600)))

        assertEquals("第一段 第二段", replaced)
        assertEquals("第一段 第二段完成", completed)
    }

    @Test fun transcript_accumulator_does_not_duplicate_cumulative_results() {
        val transcript = SpeechTranscriptAccumulator()
        val segments = listOf(
            segment("一二三", final = true, from = 0, to = 600),
            segment("四五六", final = true, from = 600, to = 1200),
        )

        assertEquals("一二三 四五六", transcript.accept(segments))
        assertEquals("一二三 四五六", transcript.accept(segments))
    }

    @Test fun speech_frame_channel_accepts_a_long_slow_recognition_session() {
        val frames = speechFrameChannel()
        repeat(300) { assertTrue(frames.trySend(ByteArray(SpeechRecorder.FRAME_BYTES)).isSuccess) }
        frames.close()
    }

    private fun frame(samples: Int, amplitude: Float): ByteArray {
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { pcm.putShort((amplitude * Short.MAX_VALUE).toInt().toShort()) }
        return pcm.array()
    }

    private fun segment(text: String, final: Boolean, from: Long, to: Long) =
        SpeechTranscriptSegment(text, final, from, to)
}
