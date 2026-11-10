package io.github.mangi.eta.agent.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure endpoint clock driven by captured audio rather than inference wall-clock time. */
internal class SpeechEndpointTracker(
    endpointSilenceMs: Int,
    private val sampleRateHz: Int = SpeechRecorder.RATE,
    private val voiceThreshold: Float = SPEECH_VOICE_ACTIVITY_RMS,
) {
    private val endpointSilenceMs = endpointSilenceMs.coerceAtLeast(MIN_ENDPOINT_SILENCE_MS)

    var frameCount: Long = 0L
        private set
    var sampleCount: Long = 0L
        private set
    var voicedFrameCount: Long = 0L
        private set
    var peakRms: Float = 0f
        private set

    private var lastVoiceAtMs = 0L
    private var heardSpeech = false

    val durationMs: Long
        get() = sampleCount * 1_000L / sampleRateHz

    fun consume(frame: ByteArray): Float {
        frameCount++
        val samples = frame.size / 2
        if (samples <= 0) return 0f
        sampleCount += samples
        val rms = pcm16Rms(frame)
        if (rms > peakRms) peakRms = rms
        if (rms >= voiceThreshold) {
            voicedFrameCount++
            heardSpeech = true
            lastVoiceAtMs = durationMs
        }
        return rms
    }

    fun markTranscript() {
        // Results are cumulative. Marking every non-empty result as fresh voice would prevent
        // endpointing forever while the model repeats the previous transcript on silent audio.
        if (!heardSpeech) lastVoiceAtMs = durationMs
        heardSpeech = true
    }

    fun reachedEndpoint(): Boolean =
        heardSpeech && durationMs - lastVoiceAtMs >= endpointSilenceMs

    companion object {
        const val MIN_ENDPOINT_SILENCE_MS = 600
    }
}

/** Deduplicates cumulative streaming results while preserving segments from earlier chunks. */
internal data class SpeechTranscriptSegment(
    val text: String,
    val isFinal: Boolean,
    val fromMs: Long,
    val toMs: Long,
)

internal class SpeechTranscriptAccumulator {
    private data class Entry(var segment: SpeechTranscriptSegment)
    private val entries = linkedMapOf<Long, Entry>()

    fun accept(segments: List<SpeechTranscriptSegment>): String {
        for (segment in segments) {
            val text = segment.text.trim()
            if (text.isEmpty()) continue
            val key = segment.fromMs
            val existing = entries[key]
            if (existing == null || segment.isFinal || !existing.segment.isFinal) {
                entries[key] = Entry(segment.copy(text = text))
            }
        }
        return entries.values
            .asSequence()
            .map { it.segment }
            .sortedWith(compareBy<SpeechTranscriptSegment> { it.fromMs }.thenBy { it.toMs })
            .joinToString(" ") { it.text }
            .trim()
    }
}

internal const val SPEECH_VOICE_ACTIVITY_RMS = 0.012f

internal fun pcm16Rms(bytes: ByteArray): Float {
    if (bytes.size < 2) return 0f
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var energy = 0.0
    var count = 0
    while (input.remaining() >= 2) {
        val sample = input.short / 32768.0
        energy += sample * sample
        count++
    }
    return if (count == 0) 0f else kotlin.math.sqrt(energy / count).toFloat()
}
