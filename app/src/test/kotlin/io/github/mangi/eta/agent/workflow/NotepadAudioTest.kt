package io.github.mangi.eta.agent.workflow

import io.github.mangi.eta.agent.voice.SpeechRecorder
import io.github.mangi.eta.agent.voice.pcmToWav
import org.junit.Assert.*
import org.junit.Test

class NotepadAudioTest {
    @Test fun recorderWavRoundTripsAndFramesPreserveAudio() {
        val pcm = ByteArray(SpeechRecorder.FRAME_BYTES * 2 + 8) { it.toByte() }
        val decoded = notepadWavPcm(pcmToWav(pcm))
        val frames = notepadPcmFrames(decoded)
        assertEquals(listOf(SpeechRecorder.FRAME_BYTES, SpeechRecorder.FRAME_BYTES, 8), frames.map { it.size })
        assertArrayEquals(pcm, frames.fold(ByteArray(0)) { all, frame -> all + frame })
    }

    @Test fun rejectsWrongFormatAndTruncatedAudio() {
        val wav = pcmToWav(ByteArray(100), sampleRate = 8000)
        assertThrows(IllegalArgumentException::class.java) { notepadWavPcm(wav) }
        val valid = pcmToWav(ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) { notepadWavPcm(valid.copyOf(valid.size - 1)) }
    }
}
