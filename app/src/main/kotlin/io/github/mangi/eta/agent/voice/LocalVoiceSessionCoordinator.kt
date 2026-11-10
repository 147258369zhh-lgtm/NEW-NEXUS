package io.github.mangi.eta.agent.voice

import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.zhh_lgtm.nexus.application.api.AudioOwner
import io.github.zhh_lgtm.nexus.application.api.VoiceSessionSnapshot
import io.github.zhh_lgtm.nexus.application.api.VoiceState
import io.github.zhh_lgtm.nexus.application.api.VoiceStateMachine
import io.github.zhh_lgtm.nexus.application.api.VoiceTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 一轮本地语音对话的阶段。半双工：同一时刻只有一个方向在占用音频。 */
internal enum class VoiceSessionPhase { IDLE, LISTENING, THINKING, SPEAKING, STOPPING }

/** 打断来源：用于日志与后续调参，不参与界面文案。 */
internal enum class VoiceInterruptSource { VOICE, USER, OWNER }

internal data class VoiceSessionState(
    val phase: VoiceSessionPhase = VoiceSessionPhase.IDLE,
    /** Stable product-level state consumed by UI and remote surfaces. */
    val canonical: VoiceSessionSnapshot = VoiceSessionSnapshot(),
    /** 朗读期间是否真的能在麦克风里听见用户。 */
    val bargeInArmed: Boolean = false,
    /** 例如 "AEC+NS+AGC"，或无法开启时的原因。 */
    val bargeInDetail: String = "",
    val interruption: VoiceInterruptSource? = null,
)

/**
 * 助手浮窗的语音会话协调器：把“正在听 / 正在想 / 正在说 / 正在停”收敛成唯一的状态，
 * 并让打断只有一条路径——先平滑停止朗读、关掉监测麦克风，再启动识别。
 *
 * 线程约定：所有公开方法都在主线程调用。停止过程有硬超时，任何一步卡住都会回到 [VoiceSessionPhase.IDLE]，
 * 不会停在 STOPPING。
 */
internal class LocalVoiceSessionCoordinator(
    private val scope: CoroutineScope,
    private val stopSpeaking: suspend (fadeMs: Int) -> Unit,
    private val startListening: () -> Unit,
    private val monitorFactory: () -> BargeInMonitor? = { null },
    private val onMonitorLevel: (Float) -> Unit = {},
    private val onMonitoringChanged: (Boolean) -> Unit = {},
    private val fadeMs: Int = BARGE_IN_FADE_MS,
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = mutableState.asStateFlow()

    private var monitor: BargeInMonitor? = null
    private var interruptJob: Job? = null
    private var interruptToken = 0L
    private var interrupting = false
    private var bargeInEnabled = true
    private var monitoringReservations = 0

    /** 最近一次打断的时间戳，供日志与“刚打断过就别再自动追问”之类的判断使用。 */
    var lastInterruptAtMs: Long = 0L
        private set

    fun setBargeInEnabled(enabled: Boolean) {
        if (bargeInEnabled == enabled) return
        bargeInEnabled = enabled
        if (!enabled) releaseMonitor()
        else if (state.value.phase == VoiceSessionPhase.SPEAKING) armMonitor()
    }

    fun onListeningStarted() {
        releaseMonitor()
        val canonical = VoiceStateMachine.transition(
            VoiceStateMachine.transition(state.value.canonical, VoiceTransition.Reset),
            VoiceTransition.StartListening,
        )
        publish(phase = VoiceSessionPhase.LISTENING, canonical = canonical)
    }

    fun onListeningEnded() {
        if (state.value.phase == VoiceSessionPhase.LISTENING) {
            publish(
                phase = VoiceSessionPhase.IDLE,
                canonical = VoiceStateMachine.transition(state.value.canonical, VoiceTransition.Completed),
            )
        }
    }

    fun onThinking() {
        releaseMonitor()
        var canonical = when (state.value.canonical.state) {
            VoiceState.LISTENING -> VoiceStateMachine.transition(state.value.canonical, VoiceTransition.SpeechCaptured)
            VoiceState.TRANSCRIBING, VoiceState.THINKING -> state.value.canonical
            else -> VoiceSessionSnapshot(state = VoiceState.TRANSCRIBING)
        }
        if (canonical.state == VoiceState.TRANSCRIBING) {
            canonical = VoiceStateMachine.transition(canonical, VoiceTransition.ResponseStarted)
        }
        publish(phase = VoiceSessionPhase.THINKING, canonical = canonical)
    }

    fun onSpeakingStarted() {
        if (state.value.phase == VoiceSessionPhase.SPEAKING) return
        val canonical = if (state.value.canonical.state == VoiceState.THINKING) {
            VoiceStateMachine.transition(state.value.canonical, VoiceTransition.StartSpeaking)
        } else {
            VoiceSessionSnapshot(state = VoiceState.SPEAKING, audioOwner = AudioOwner.ASSISTANT_PLAYBACK)
        }
        publish(phase = VoiceSessionPhase.SPEAKING, canonical = canonical)
        armMonitor()
    }

    fun onSpeakingEnded() {
        if (state.value.phase != VoiceSessionPhase.SPEAKING) return
        releaseMonitor()
        publish(
            phase = VoiceSessionPhase.IDLE,
            canonical = VoiceStateMachine.transition(state.value.canonical, VoiceTransition.Completed),
        )
    }

    /**
     * 用户主动要说话（点麦克风、按住说话）。
     * 朗读中走与语音打断完全相同的收尾路径；空闲时直接开麦。
     */
    fun requestListening() {
        when (state.value.phase) {
            VoiceSessionPhase.SPEAKING -> interrupt(VoiceInterruptSource.USER, listenAfter = true)
            VoiceSessionPhase.LISTENING, VoiceSessionPhase.STOPPING -> Unit
            else -> startListening()
        }
    }

    /** 只要停下朗读，不进入识别。 */
    fun stopSpeakingNow() {
        if (state.value.phase == VoiceSessionPhase.SPEAKING) {
            interrupt(VoiceInterruptSource.USER, listenAfter = false)
        }
    }

    fun release() {
        interruptToken++
        interrupting = false
        interruptJob?.cancel()
        interruptJob = null
        releaseMonitor()
        mutableState.value = VoiceSessionState()
    }

    private fun interrupt(source: VoiceInterruptSource, listenAfter: Boolean) {
        if (state.value.phase != VoiceSessionPhase.SPEAKING) {
            if (listenAfter) startListening()
            return
        }
        // 同一次打断只跑一条收尾链，避免监测回调与界面点击叠加出两次识别。
        if (interrupting) return
        interrupting = true
        val token = ++interruptToken
        lastInterruptAtMs = SystemClock.elapsedRealtime()
        val monitorToClose = monitor
        monitor = null
        AndroidAgentLogger.info("Nexus speech interrupted: source=$source")
        mutableState.value = state.value.copy(
            phase = VoiceSessionPhase.STOPPING,
            canonical = if (state.value.canonical.state in setOf(VoiceState.SPEAKING, VoiceState.THINKING)) {
                VoiceStateMachine.transition(state.value.canonical, VoiceTransition.UserInterrupted)
            } else {
                VoiceSessionSnapshot(state = VoiceState.INTERRUPTED, audioOwner = AudioOwner.USER)
            },
            bargeInArmed = false,
            interruption = source,
        )
        interruptJob = scope.launch(Dispatchers.Main.immediate) {
            // 先把监测麦克风关干净，再收尾播放，最后才让识别去抢输入源。
            monitorToClose?.let { current ->
                try {
                    current.stop()
                    withTimeoutOrNull(MONITOR_CLOSE_TIMEOUT_MS) {
                        current.awaitStopped(MONITOR_CLOSE_TIMEOUT_MS)
                    }
                } finally {
                    releaseMonitoringReservation()
                }
            }
            // NonCancellable 的淡出只会多花固定几十毫秒；超时是防某一步卡死的兜底。
            val faded = withTimeoutOrNull(stopTimeoutMs) {
                runCatching { stopSpeaking(fadeMs) }
            }
            if (token != interruptToken) return@launch
            interrupting = false
            if (faded == null) {
                AndroidAgentLogger.warn("Nexus speech stop timed out, forcing idle")
            }
            mutableState.value = state.value.copy(
                phase = VoiceSessionPhase.IDLE,
                canonical = VoiceSessionSnapshot(),
                bargeInArmed = false,
                interruption = null,
            )
            if (listenAfter) startListening()
        }
    }

    private fun armMonitor() {
        if (!bargeInEnabled || monitor != null) return
        val created = monitorFactory() ?: return
        monitor = created
        try {
            // Android 15+ 要求麦克风前台类型先于 AudioRecord.startRecording() 生效。
            acquireMonitoringReservation()
            created.start(
                onArmed = armed@{ status ->
                    if (monitor !== created) return@armed
                    mutableState.value = state.value.copy(
                        bargeInArmed = status.canHearUser,
                        bargeInDetail = status.reason ?: status.effects,
                    )
                    if (status.canHearUser) {
                        AndroidAgentLogger.info("Nexus speech barge-in armed: effects=${status.effects}")
                    } else {
                        // All unavailable/no-AEC paths return before starting the AudioRecord. Release the
                        // foreground reservation immediately so the service does not claim a live microphone.
                        if (monitor === created) {
                            monitor = null
                            created.stop()
                            releaseMonitoringReservation()
                        }
                        AndroidAgentLogger.info("Nexus speech barge-in limited to tap: ${status.reason}")
                    }
                },
                onLevel = onMonitorLevel,
                onTriggered = triggered@{
                    if (monitor !== created) return@triggered
                    // Keep the monitor attached until interrupt() has stopped and awaited the capture
                    // thread. Otherwise the recognizer can acquire the microphone before it is released.
                    interrupt(VoiceInterruptSource.VOICE, listenAfter = true)
                },
            )
        } catch (error: Throwable) {
            if (monitor === created) monitor = null
            releaseMonitoringReservation()
            throw error
        }
    }

    private fun releaseMonitor() {
        val current = monitor ?: return
        monitor = null
        current.stop()
        scope.launch(Dispatchers.Main.immediate) {
            try {
                withTimeoutOrNull(MONITOR_CLOSE_TIMEOUT_MS) { current.awaitStopped(MONITOR_CLOSE_TIMEOUT_MS) }
            } finally {
                releaseMonitoringReservation()
            }
        }
    }

    /**
     * An old monitor may finish after a new speech turn has already armed. Track reservations as a
     * count so its late completion cannot demote the microphone foreground service for the new turn.
     */
    private fun acquireMonitoringReservation() {
        monitoringReservations++
        if (monitoringReservations == 1) onMonitoringChanged(true)
    }

    private fun releaseMonitoringReservation() {
        if (monitoringReservations <= 0) return
        monitoringReservations--
        if (monitoringReservations == 0) onMonitoringChanged(false)
    }

    private fun publish(
        phase: VoiceSessionPhase,
        canonical: VoiceSessionSnapshot = state.value.canonical,
    ) {
        mutableState.value = state.value.copy(
            phase = phase,
            canonical = canonical,
            interruption = null,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 1_200L
        const val MONITOR_CLOSE_TIMEOUT_MS = 600L
    }
}

/** 朗读被打断时的收尾淡出时长：短到像“立刻停下”，长到不产生爆音。 */
internal const val BARGE_IN_FADE_MS = 140
