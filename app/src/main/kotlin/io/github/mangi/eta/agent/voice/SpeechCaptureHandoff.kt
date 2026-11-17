package io.github.mangi.eta.agent.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the beginning of an interruption and transfers the same AEC microphone to ASR. */
internal class SpeechCaptureHandoff(private val stop: () -> Unit) : SpeechAudioCapture {
    private val channel = speechFrameChannel()
    override val frames: ReceiveChannel<ByteArray> get() = channel
    private val done = CompletableDeferred<Unit>()
    private val finishing = AtomicBoolean(false)
    private var bytes = 0

    @Synchronized fun offer(frame: ByteArray): Boolean {
        if (finishing.get()) return false
        if (bytes + frame.size > SpeechRecorder.MAX_BYTES) { finish(); return false }
        bytes += frame.size
        return channel.trySend(frame.copyOf()).isSuccess
    }

    fun sourceClosed(failure: Throwable? = null) { channel.close(failure); done.complete(Unit) }

    override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
        try { onReady(); done.await() } finally { finish() }
    }

    override fun finish() { if (finishing.compareAndSet(false, true)) stop() }
    override fun cancel() { finish(); channel.cancel(); done.complete(Unit) }
}

internal class SpeechPrerollBuffer(private val limitBytes: Int = SpeechRecorder.RATE * 2 * 4 / 5) {
    init { require(limitBytes > 0 && limitBytes % 2 == 0) }
    private val frames = ArrayDeque<ByteArray>()
    private var bytes = 0
    @Synchronized fun add(frame: ByteArray) {
        val copy = if (frame.size > limitBytes) frame.copyOfRange(frame.size - limitBytes, frame.size) else frame.copyOf()
        frames.addLast(copy); bytes += copy.size
        while (bytes > limitBytes) { bytes -= frames.removeFirst().size }
    }
    @Synchronized fun drain(): List<ByteArray> = frames.toList().also { frames.clear(); bytes = 0 }
}

/** The monitor keeps its microphone foreground claim until ASR has acquired its own. */
internal class ReservedSpeechCapture(private val capture: SpeechAudioCapture, private val released: () -> Unit) : SpeechAudioCapture {
    private val releaseOnce = AtomicBoolean(false)
    override val frames get() = capture.frames
    override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
        try { capture.record({ release(); onReady() }, onLevel) } finally { release() }
    }
    override fun finish() = capture.finish()
    override fun cancel() { try { capture.cancel() } finally { release() } }
    private fun release() { if (releaseOnce.compareAndSet(false, true)) released() }
}
