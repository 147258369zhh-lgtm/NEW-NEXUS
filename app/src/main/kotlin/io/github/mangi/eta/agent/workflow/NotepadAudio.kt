package io.github.mangi.eta.agent.workflow

import io.github.mangi.eta.agent.voice.SpeechRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun notepadWavPcm(wav: ByteArray): ByteArray {
    fun invalid(): Nothing = throw IllegalArgumentException("仅支持 16 kHz、16 位单声道 PCM 格式的录音")
    if (wav.size < 12 || wav.size > SpeechRecorder.MAX_BYTES + 65536) invalid()
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(offset: Int, value: String) = value.indices.all { wav[offset + it] == value[it].code.toByte() }
    if (!tag(0, "RIFF") || !tag(8, "WAVE")) invalid()
    val end = buffer.getInt(4).toLong() + 8
    if (end < 12 || end > wav.size) invalid()
    var offset = 12
    var formatValid = false
    var dataStart = -1
    var dataSize = 0
    while (offset.toLong() + 8 <= end) {
        val size = buffer.getInt(offset + 4).toLong() and 0xffffffffL
        val start = offset.toLong() + 8
        val next = start + size + (size and 1)
        if (start + size > end || next > end) invalid()
        if (tag(offset, "fmt ")) {
            if (size < 16) invalid()
            formatValid = buffer.getShort(start.toInt()).toInt() == 1 &&
                buffer.getShort(start.toInt() + 2).toInt() == 1 &&
                buffer.getInt(start.toInt() + 4) == SpeechRecorder.RATE &&
                buffer.getShort(start.toInt() + 12).toInt() == 2 &&
                buffer.getShort(start.toInt() + 14).toInt() == 16
        } else if (tag(offset, "data")) {
            if (dataStart != -1 || size == 0L || size > SpeechRecorder.MAX_BYTES || size % 2 != 0L) invalid()
            dataStart = start.toInt(); dataSize = size.toInt()
        }
        offset = next.toInt()
    }
    if (!formatValid || dataStart < 0 || offset.toLong() != end) invalid()
    return wav.copyOfRange(dataStart, dataStart + dataSize)
}

internal fun notepadPcmFrames(pcm: ByteArray): List<ByteArray> =
    (pcm.indices step SpeechRecorder.FRAME_BYTES).map { start ->
        pcm.copyOfRange(start, minOf(start + SpeechRecorder.FRAME_BYTES, pcm.size))
    }
