package io.github.mangi.eta.agent.voice

/** Caps both online and offline recognition before passing more PCM to native code. */
internal class LocalSpeechInputBudget(private val maxBytes: Long = SpeechRecorder.MAX_BYTES.toLong()) {
    private var accepted = 0L
    fun accept(frame: ByteArray) {
        require(frame.size % 2 == 0) { "本地语音需要完整的 16 位 PCM 采样" }
        require(frame.size.toLong() <= maxBytes - accepted) { "录音超过长度限制" }
        accepted += frame.size
    }
}
