package io.github.mangi.eta.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runInterruptible

/** 打断监测是否真正具备“听见用户”的能力，以及为此挂上的系统音频效果。 */
internal data class BargeInMonitorStatus(
    val canHearUser: Boolean,
    val effects: String = "",
    val reason: String? = null,
)

/**
 * 播放期间的开麦监测。
 *
 * 半双工的打断检测依赖系统回声消除：喇叭正在放 Eta 自己的声音，只有 AEC 把这条回路压下去，
 * 麦克风里剩下的人声才有意义。拿不到 AEC 时不做“听见就停”的自动判断，只保留用户明确点击
 * 麦克风的打断入口，避免 TTS 声音把自己打断。
 */
internal interface BargeInMonitor {
    /** 异步启动；结果经 [onArmed] 回到调用方。重复调用前请先 [stop]。 */
    fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit)

    /** 请求停止，不阻塞调用线程。 */
    fun stop()

    /** 等待采集线程真正退出，最多 [timeoutMs]。 */
    suspend fun awaitStopped(timeoutMs: Long = 600)

    /** Non-null transfers the existing microphone; the new owner must finish/cancel it. */
    fun takeSpeechCapture(): SpeechAudioCapture? = null
}

/**
 * 人声检测：只看“明显高于当前回声底噪并持续一小段时间”的信号。
 *
 * 底噪跟踪刻意做成“慢涨慢落”：喇叭里漏进来的回声把基线抬上去后不会因为一个音节的间隙就塌掉，
 * 因此连续的朗读不会触发；而用户开口时电平会稳定越过基线并持续到 [holdMs]。
 * 候选期间暂停更新底噪——否则持续说话会把判定门槛一起抬高，越说越判不出来。
 */
internal class VoiceBargeInDetector(
    private val absoluteThreshold: Float = ABSOLUTE_THRESHOLD,
    private val floorRatio: Float = FLOOR_RATIO,
    private val holdMs: Long = HOLD_MS,
    private val warmupMs: Long = WARMUP_MS,
) {
    private var startedAtMs = -1L
    private var floor = 0f
    private var candidateSinceMs = -1L
    private var candidateGate = 0f
    private var fired = false

    fun reset(startedAtMs: Long) {
        this.startedAtMs = startedAtMs
        floor = 0f
        candidateSinceMs = -1L
        candidateGate = 0f
        fired = false
    }

    /** @return 首次判定为打断时返回 true；同一段监测只会返回一次。 */
    fun observe(rms: Float, nowMs: Long): Boolean {
        if (startedAtMs < 0 || fired) return false
        if (nowMs - startedAtMs < warmupMs) {
            updateFloor(rms)
            return false
        }
        if (candidateSinceMs < 0) {
            val gate = maxOf(absoluteThreshold, floor * floorRatio)
            if (rms >= gate) {
                candidateSinceMs = nowMs
                candidateGate = gate
            } else {
                updateFloor(rms)
            }
            return false
        }
        // 掉回底噪附近说明只是音节起伏，重新计时。
        if (rms < candidateGate * RELEASE_RATIO) {
            candidateSinceMs = -1L
            updateFloor(rms)
            return false
        }
        if (nowMs - candidateSinceMs < holdMs) return false
        fired = true
        return true
    }

    private fun updateFloor(rms: Float) {
        floor = if (rms > floor) {
            floor + (rms - floor) * FLOOR_RISE
        } else {
            floor + (rms - floor) * FLOOR_FALL
        }
    }

    private companion object {
        /** 归一化到 0..1 的 RMS；比这更低的气流声不值得打断朗读。 */
        const val ABSOLUTE_THRESHOLD = 0.010f
        /** 需要明显高于回声底噪，避免与朗读本身混淆。 */
        const val FLOOR_RATIO = 1.8f
        const val HOLD_MS = 180L
        const val WARMUP_MS = 320L
        const val FLOOR_RISE = 0.08f
        const val FLOOR_FALL = 0.004f
        /** 候选期间允许的回落幅度；低于此值视为重新开始判断。 */
        const val RELEASE_RATIO = 0.7f
    }
}

@SuppressLint("MissingPermission")
internal class SpeechBargeInMonitor(private val context: Context,
    private val requireEchoCancellation: Boolean = true,
    private val continuousEdges: Boolean = false,
    private val onSpeechEnded: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val conservativeWithoutEcho: Boolean = false,
    private val playbackActive: () -> Boolean = { true },
    private val onPcm: ((ByteArray) -> Unit)? = null,
    private val continuousSilenceMs: () -> Int = { 700 },
) : BargeInMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = VoiceBargeInDetector(warmupMs = if (continuousEdges) 400 else 320)
    private val conservativeDetector = VoiceBargeInDetector(absoluteThreshold = .035f, floorRatio = 3.4f, holdMs = 260, warmupMs = 400)
    @Volatile private var record: AudioRecord? = null
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private val handoffLock = Any()
    private var preroll = SpeechPrerollBuffer()
    @Volatile private var handoff: SpeechCaptureHandoff? = null
    @Volatile private var triggered = false

    override fun start(
        onArmed: (BargeInMonitorStatus) -> Unit,
        onLevel: (Float) -> Unit,
        onTriggered: () -> Unit,
    ) {
        if (worker?.isAlive == true) {
            postArmed(onArmed, BargeInMonitorStatus(false, reason = "上一次麦克风尚未退出")); return
        }
        stopping = false
        preroll = SpeechPrerollBuffer(); handoff = null; triggered = false
        val thread = Thread({ run(onArmed, onLevel, onTriggered) }, "NexusSpeechBargeIn")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    override fun stop() {
        stopping = true
        // stop 会解除阻塞中的 read，采集线程在 finally 里唯一释放实例。
        runCatching { record?.stop() }
    }

    override fun takeSpeechCapture(): SpeechAudioCapture? = synchronized(handoffLock) {
        if (stopping || record == null || worker?.isAlive != true || !triggered || handoff != null) return@synchronized null
        SpeechCaptureHandoff(::stop).also { capture ->
            preroll.drain().forEach(capture::offer)
            handoff = capture
        }
    }

    override suspend fun awaitStopped(timeoutMs: Long) {
        val thread = worker ?: return
        runCatching { runInterruptible { thread.join(timeoutMs) } }
        if (!thread.isAlive && worker === thread) worker = null
    }

    private fun run(
        onArmed: (BargeInMonitorStatus) -> Unit,
        onLevel: (Float) -> Unit,
        onTriggered: () -> Unit,
    ) {
        val admissionDeadline = SystemClock.elapsedRealtime() + 300
        while (!CAPTURING.compareAndSet(false, true)) {
            if (stopping) return
            if (SystemClock.elapsedRealtime() >= admissionDeadline) {
                postArmed(onArmed, BargeInMonitorStatus(false, reason = "已有语音检测正在使用麦克风")); return
            }
            try { Thread.sleep(10) } catch (_: InterruptedException) { return }
        }
        try { kotlinx.coroutines.runBlocking { BackgroundWakeWordRuntime.yieldMicrophone() } }
        catch (_: Exception) {
            CAPTURING.set(false)
            postArmed(onArmed, BargeInMonitorStatus(false, reason = "唤醒麦克风尚未释放")); return
        }
        val audio = createRecord()
        if (audio == null) {
            CAPTURING.set(false)
            postArmed(onArmed, BargeInMonitorStatus(canHearUser = false, reason = "麦克风不可用"))
            return
        }
        record = audio
        val attached = mutableListOf<AudioEffect>()
        val names = mutableListOf<String>()
        var frameIndex = 0
        var captureError: Throwable? = null
        try {
            if (stopping) return
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                postArmed(onArmed, BargeInMonitorStatus(canHearUser = false, reason = "麦克风初始化失败"))
                return
            }
            val session = runCatching { audio.audioSessionId }.getOrDefault(0)
            if (session != 0) {
                attach(AcousticEchoCanceler.create(session), "AEC", attached, names)
                attach(NoiseSuppressor.create(session), "NS", attached, names)
                attach(AutomaticGainControl.create(session), "AGC", attached, names)
            }
            // AEC 是自动打断的前提；缺了它宁可只保留手动打断。
            if (requireEchoCancellation && names.none { it == "AEC" } && !conservativeWithoutEcho) {
                postArmed(
                    onArmed,
                    BargeInMonitorStatus(
                        canHearUser = false,
                        effects = names.joinToString("+"),
                        reason = "设备未提供回声消除",
                    ),
                )
                return
            }
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                postArmed(onArmed, BargeInMonitorStatus(canHearUser = false, reason = "麦克风被占用"))
                return
            }
            detector.reset(SystemClock.elapsedRealtime())
            conservativeDetector.reset(SystemClock.elapsedRealtime())
            postArmed(
                onArmed,
                BargeInMonitorStatus(canHearUser = true, effects = names.joinToString("+"),
                    reason = if (requireEchoCancellation && names.none { it == "AEC" }) "使用增强门槛，设备未提供回声消除" else null),
            )
            val frame = ByteArray(FRAME_BYTES)
            var speech = false
            var silentAt = -1L
            var playing = playbackActive()
            var triggerDeadline = Long.MAX_VALUE
            while (!stopping) {
                val count = audio.read(frame, 0, frame.size)
                if (count <= 0) {
                    if (stopping) break
                    throw IllegalStateException("Microphone capture stopped")
                }
                if (count % 2 != 0) throw IllegalStateException("Unaligned microphone PCM")
                onPcm?.invoke(frame.copyOf(count))
                synchronized(handoffLock) {
                    val bytes = frame.copyOf(count)
                    val capture = handoff
                    if (capture == null) preroll.add(bytes) else capture.offer(bytes)
                }
                val rms = rms(frame, count)
                // 50 帧/秒的电平不必逐帧推给界面，三帧一次足够顺滑。
                if (frameIndex++ % LEVEL_STRIDE == 0) postLevel(onLevel, rms)
                val now = SystemClock.elapsedRealtime()
                if (triggered && !continuousEdges) {
                    if (handoff == null && now >= triggerDeadline) break
                    continue
                }
                val currentPlayback = playbackActive()
                if (continuousEdges && currentPlayback != playing && !speech) { detector.reset(now); conservativeDetector.reset(now) }
                playing = currentPlayback
                if (continuousEdges && speech) {
                    if (rms >= .006f) silentAt = -1 else if (silentAt < 0) silentAt = now
                    val silenceMs = continuousSilenceMs()
                    if (silenceMs > 0 && silentAt >= 0 && now - silentAt >= silenceMs.coerceAtLeast(600)) {
                        speech = false; silentAt = -1; detector.reset(now); conservativeDetector.reset(now)
                        mainHandler.post { if (!stopping) onSpeechEnded() }
                    }
                } else if ((if (conservativeWithoutEcho && playing && names.none { it == "AEC" }) conservativeDetector else detector).observe(rms, now)) {
                    AndroidAgentLogger.info("Nexus speech barge-in detected: rms=$rms")
                    triggered = true
                    triggerDeadline = now + 1_000
                    mainHandler.post { if (!stopping) onTriggered() }
                    if (continuousEdges) { speech = true; silentAt = -1 }
                }
            }
        } catch (failure: Throwable) {
            if (!stopping) captureError = failure
            if (!stopping) mainHandler.post { if (!stopping) onFailure(failure.javaClass.simpleName) }
            AndroidAgentLogger.warnThrottled("eta_speech_barge_in_failed") {
                "Nexus speech barge-in monitor failed: type=${failure.javaClass.simpleName}"
            }
        } finally {
            attached.forEach { effect -> runCatching { effect.release() } }
            runCatching { audio.stop() }
            runCatching { audio.release() }
            if (record === audio) record = null
            synchronized(handoffLock) {
                stopping = true
                handoff?.sourceClosed(captureError)
                preroll.drain()
            }
            CAPTURING.set(false)
        }
    }

    private fun createRecord(): AudioRecord? = runCatching {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        // VOICE_COMMUNICATION 才会启用通话链路的回声消除；与识别用的 VOICE_RECOGNITION 不同。
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, FRAME_BYTES * 4),
        )
    }.getOrNull()

    private fun attach(
        effect: AudioEffect?,
        name: String,
        attached: MutableList<AudioEffect>,
        names: MutableList<String>,
    ) {
        if (effect == null) return
        val enabled = runCatching {
            effect.enabled = true
            effect.enabled
        }.getOrDefault(false)
        if (enabled) {
            attached += effect
            names += name
        } else {
            runCatching { effect.release() }
        }
    }

    private fun postArmed(callback: (BargeInMonitorStatus) -> Unit, status: BargeInMonitorStatus) {
        mainHandler.post { callback(status) }
    }

    private fun postLevel(callback: (Float) -> Unit, rms: Float) {
        // 与录音入口保持同一量纲，面板波形可以直接复用。
        val level = (rms * 8f).coerceIn(0f, 1f)
        mainHandler.post { callback(level) }
    }

    private fun rms(bytes: ByteArray, count: Int): Float {
        var energy = 0.0
        var index = 0
        while (index + 1 < count) {
            val sample = ((bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)).toShort() / 32768.0
            energy += sample * sample
            index += 2
        }
        val samples = count / 2
        return if (samples == 0) 0f else sqrt(energy / samples).toFloat()
    }

    companion object {
        internal suspend fun awaitCaptureReleased(timeoutMs: Long = 1_200) {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                while (CAPTURING.get()) kotlinx.coroutines.delay(10)
            }
        }
        private val CAPTURING = AtomicBoolean(false)
        const val RATE = 16_000
        /** 20 ms 一帧，足够分辨句首，又不会让检测延迟明显。 */
        const val FRAME_BYTES = 640
        const val LEVEL_STRIDE = 3
    }
}
