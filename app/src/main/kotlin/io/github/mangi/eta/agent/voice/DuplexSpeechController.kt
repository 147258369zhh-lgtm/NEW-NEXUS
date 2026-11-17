package io.github.mangi.eta.agent.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicBoolean

/** One utterance from an independently owned AEC microphone; ending ASR does not close the mic. */
internal class DuplexUtteranceCapture(private val finished: () -> Unit) : SpeechAudioCapture {
    private val source = speechFrameChannel()
    private val done = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private var bytes = 0
    override val frames: ReceiveChannel<ByteArray> get() = source
    @Synchronized fun offer(frame: ByteArray) {
        if (closed.get()) return
        if (bytes + frame.size > SpeechRecorder.MAX_BYTES) { finish(); return }
        bytes += frame.size; source.trySend(frame.copyOf())
    }
    override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) { onReady(); done.await() }
    override fun finish() { if (closed.compareAndSet(false, true)) { source.close(); done.complete(Unit); finished() } }
    override fun cancel() { finish(); source.cancel() }
}

/** Capture and playback are independent; actual ASR text decides whether speech is interrupted. */
internal class DuplexSpeechController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioGroup: Any,
    private val foreground: (Boolean) -> Unit,
    private val onUtterance: (SpeechAudioCapture) -> Unit,
    private val onInterrupted: suspend () -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val playbackActive: () -> Boolean,
    private val monitorFactory: (((ByteArray) -> Unit, () -> Unit, (String) -> Unit) -> BargeInMonitor)? = null,
    private val onCaptureStatus: (Boolean) -> Unit = {},
    private val endpointSilenceMs: () -> Int = { 2_000 },
) {
    private val preroll = SpeechPrerollBuffer()
    private val lock = Any()
    private var monitor: BargeInMonitor? = null
    private var lease: SpeechAudioLease? = null
    private var capture: DuplexUtteranceCapture? = null
    private var recognizing = false
    private var interrupted = false
    private var generation = 0L
    private var stopping: Job? = null
    private var foregroundClaimed = false
    @Volatile var active = false
        private set
    val userSpeaking get() = synchronized(lock) { capture != null || recognizing }

    fun start() {
        if (active) return
        val token = ++generation
        active = true
        interrupted = false
        try {
            lease = SpeechAudioLease(context, duplexGroup = audioGroup) { stop() }.also { it.acquire(false) }
            foregroundClaimed = true
            foreground(true)
            val frames: (ByteArray) -> Unit = { frame -> synchronized(lock) {
                if (active && token == generation) { val current = capture; if (current == null) preroll.add(frame) else current.offer(frame) }
            } }
            val ended = { scope.launch(Dispatchers.Main.immediate) { if (token == generation) endUtterance() }; Unit }
            val failure: (String) -> Unit = { message -> scope.launch(Dispatchers.Main.immediate) {
                if (token == generation) { stop(); onUnavailable(message) }
            }; Unit }
            val created = monitorFactory?.invoke(frames, ended, failure) ?: SpeechBargeInMonitor(context,
                continuousEdges = true, requireEchoCancellation = true, onSpeechEnded = ended,
                onFailure = failure, playbackActive = playbackActive, onPcm = frames, continuousSilenceMs = endpointSilenceMs)
            monitor = created
            created.start({ status ->
                if (token == generation) onCaptureStatus(status.canHearUser)
                if (token == generation && !status.canHearUser) { stop(); onUnavailable(status.reason ?: "设备无法开启回声消除") }
            }, {}, { if (active && token == generation) beginUtterance() })
        } catch (failure: Throwable) { stop(); onUnavailable(failure.message ?: "双工语音启动失败") }
    }

    private fun beginUtterance() {
        synchronized(lock) {
            if (!active || recognizing || capture != null) return
            interrupted = false; recognizing = true
            lateinit var segment: DuplexUtteranceCapture
            segment = DuplexUtteranceCapture { scope.launch(Dispatchers.Main.immediate) { synchronized(lock) { if (capture === segment) capture = null } } }
            capture = segment
            preroll.drain().forEach(segment::offer)
            onUtterance(segment)
        }
    }

    private fun endUtterance() = synchronized(lock) { capture?.finish(); capture = null }

    fun transcript(preview: String) {
        if (!active || interrupted || preview.isBlank() || !recognizing) return
        interrupted = true
        val token = generation
        scope.launch(Dispatchers.Main.immediate) { if (active && token == generation && playbackActive()) onInterrupted() }
    }

    fun recognitionFinished() = synchronized(lock) {
        capture?.finish(); capture = null; recognizing = false; interrupted = false; preroll.drain()
    }

    fun stop() {
        if (!active && monitor == null && lease == null) return
        generation++; active = false
        onCaptureStatus(false)
        synchronized(lock) { capture?.cancel(); capture = null; recognizing = false; interrupted = false; preroll.drain() }
        val old = monitor; monitor = null
        old?.stop()
        val oldLease = lease; lease = null
        val releaseForeground = foregroundClaimed; foregroundClaimed = false
        stopping = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            try { withTimeoutOrNull(800) { old?.awaitStopped(800) } }
            finally {
                try { runCatching { oldLease?.close() } }
                finally { if (releaseForeground) runCatching { foreground(false) } }
            }
        }
    }
    suspend fun awaitStopped() { stopping?.join() }
}
