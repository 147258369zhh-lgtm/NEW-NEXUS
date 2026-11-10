package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class LocalVoiceBargeInTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        scope.cancel()
    }

    // ---- 人声检测 ----

    @Test
    fun steadyPlaybackEchoNeverCountsAsUserSpeech() {
        val detector = VoiceBargeInDetector()
        detector.reset(0L)
        var fired = false
        var at = 0L
        while (at <= 2_000L) {
            // 喇叭漏进麦克风的回声起伏不大，只是缓慢变化。
            val rms = 0.05f + 0.004f * ((at / 100) % 2)
            fired = fired || detector.observe(rms, at)
            at += 20
        }
        assertFalse(fired)
    }

    @Test
    fun sustainedVoiceAboveEchoFloorInterrupts() {
        val detector = VoiceBargeInDetector()
        detector.reset(0L)
        var at = 0L
        while (at < 1_000L) {
            assertFalse(detector.observe(0.05f, at))
            at += 20
        }
        var fired = false
        while (at < 1_600L && !fired) {
            fired = detector.observe(0.20f, at)
            at += 20
        }
        assertTrue(fired)
        // 同一段监测只触发一次。
        assertFalse(detector.observe(0.30f, at + 400))
    }

    @Test
    fun shortCoughDoesNotInterrupt() {
        val detector = VoiceBargeInDetector()
        detector.reset(0L)
        var at = 0L
        while (at < 1_000L) {
            detector.observe(0.05f, at)
            at += 20
        }
        var fired = false
        while (at < 1_100L) {
            fired = fired || detector.observe(0.30f, at)
            at += 20
        }
        while (at < 1_800L) {
            fired = fired || detector.observe(0.05f, at)
            at += 20
        }
        assertFalse(fired)
    }

    // ---- 会话协调 ----

    @Test
    fun voiceTriggerStopsSpeakingBeforeListening() {
        val harness = harness()
        harness.coordinator.onSpeakingStarted()
        assertEquals(VoiceSessionPhase.SPEAKING, harness.coordinator.state.value.phase)
        assertTrue(harness.coordinator.state.value.bargeInArmed)

        harness.monitor.trigger?.invoke()
        idle()

        assertEquals(1, harness.stopped)
        assertEquals(1, harness.listening)
        assertEquals(VoiceSessionPhase.LISTENING, harness.coordinator.state.value.phase)
        // ASR 只能在监测线程真正停止并完成 await 之后开麦。
        assertTrue(harness.monitor.stops >= 1)
        assertFalse(harness.coordinator.state.value.bargeInArmed)
    }

    @Test
    fun tappingMicrophoneUsesTheSameHandoff() {
        val harness = harness()
        harness.coordinator.onSpeakingStarted()
        harness.coordinator.requestListening()
        idle()
        assertEquals(1, harness.stopped)
        assertEquals(1, harness.listening)
        assertTrue(harness.monitor.stops >= 1)
    }

    @Test
    fun playbackEndDuringStopDoesNotSkipHandoff() {
        val harness = harness(stop = { delay(200) })
        harness.coordinator.onSpeakingStarted()
        harness.monitor.trigger?.invoke()
        assertEquals(VoiceSessionPhase.STOPPING, harness.coordinator.state.value.phase)
        // 播放器停止本身会回报一次“说完了”，不能因此把 STOPPING 提前放回空闲。
        harness.coordinator.onSpeakingEnded()
        assertEquals(VoiceSessionPhase.STOPPING, harness.coordinator.state.value.phase)
        idle(400)
        assertEquals(VoiceSessionPhase.LISTENING, harness.coordinator.state.value.phase)
        assertEquals(1, harness.listening)
    }

    @Test
    fun repeatedMicrophoneTapDuringStopDoesNotStartTwoRecognizers() {
        val harness = harness(stop = { delay(200) })
        harness.coordinator.onSpeakingStarted()
        harness.coordinator.requestListening()
        harness.coordinator.requestListening()
        idle(400)
        assertEquals(1, harness.stopped)
        assertEquals(1, harness.listening)
    }

    @Test
    fun stuckPlaybackStillLeavesStopping() {
        val harness = harness(stopTimeoutMs = 60, stop = { awaitCancellation() })
        harness.coordinator.onSpeakingStarted()
        harness.coordinator.stopSpeakingNow()
        idle(400)
        assertEquals(VoiceSessionPhase.IDLE, harness.coordinator.state.value.phase)
        assertFalse(harness.coordinator.state.value.bargeInArmed)
    }

    @Test
    fun monitorWithoutEchoCancellationOnlyAllowsTapInterrupt() {
        val harness = harness(status = BargeInMonitorStatus(canHearUser = false, reason = "设备未提供回声消除"))
        harness.coordinator.onSpeakingStarted()
        assertFalse(harness.coordinator.state.value.bargeInArmed)
        assertEquals("设备未提供回声消除", harness.coordinator.state.value.bargeInDetail)
        harness.coordinator.requestListening()
        idle()
        assertEquals(1, harness.listening)
    }

    @Test
    fun lateMonitorCompletionCannotDemoteANewSpeechTurn() {
        val harness = harness(monitorCloseDelayMs = 100)
        harness.coordinator.onSpeakingStarted()
        assertEquals(listOf(true), harness.monitoringEvents)
        harness.coordinator.onListeningStarted()
        harness.coordinator.onSpeakingStarted()
        idle(800)
        assertEquals(listOf(true), harness.monitoringEvents)
    }

    // ---- 资源优先级 ----

    @Test
    fun playbackIsReleasedBeforeRecognitionAndTextModelIsNeverTouched() {
        assertEquals(
            listOf(LocalSpeechKind.TTS),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, speaking = false, listening = false,
            ),
        )
        assertEquals(
            listOf(LocalSpeechKind.TTS, LocalSpeechKind.ASR),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, speaking = false, listening = false,
            ),
        )
        assertEquals(
            emptyList<LocalSpeechKind>(),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, speaking = true, listening = false,
            ),
        )
        assertEquals(
            emptyList<LocalSpeechKind>(),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, speaking = false, listening = false,
            ),
        )
        assertEquals(
            listOf(LocalSpeechKind.TTS, LocalSpeechKind.ASR),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, speaking = true, listening = false,
            ),
        )
        assertEquals(
            listOf(LocalSpeechKind.TTS),
            LocalVoiceResourcePolicy.releaseTargets(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, speaking = false, listening = true,
            ),
        )
        assertEquals(listOf(LocalSpeechKind.TTS, LocalSpeechKind.ASR), LocalVoiceResourcePolicy.evictionOrder)
    }

    private class Harness {
        val monitor = FakeMonitor()
        var stopped = 0
        var listening = 0
        val monitoringEvents = mutableListOf<Boolean>()
        lateinit var coordinator: LocalVoiceSessionCoordinator
    }

    private fun harness(
        stopTimeoutMs: Long = 1_200,
        status: BargeInMonitorStatus = BargeInMonitorStatus(canHearUser = true, effects = "AEC+NS+AGC"),
        monitorCloseDelayMs: Long = 0,
        stop: suspend () -> Unit = {},
    ): Harness {
        val harness = Harness()
        harness.monitor.status = status
        harness.monitor.awaitDelayMs = monitorCloseDelayMs
        harness.coordinator = LocalVoiceSessionCoordinator(
            scope = scope,
            stopSpeaking = { _ ->
                harness.stopped++
                stop()
            },
            startListening = {
                harness.listening++
                harness.coordinator.onListeningStarted()
            },
            monitorFactory = { harness.monitor },
            onMonitoringChanged = harness.monitoringEvents::add,
            stopTimeoutMs = stopTimeoutMs,
        )
        return harness
    }

    private class FakeMonitor : BargeInMonitor {
        var status = BargeInMonitorStatus(canHearUser = true, effects = "AEC")
        var trigger: (() -> Unit)? = null
        var stops = 0
        var awaitDelayMs = 0L

        override fun start(
            onArmed: (BargeInMonitorStatus) -> Unit,
            onLevel: (Float) -> Unit,
            onTriggered: () -> Unit,
        ) {
            trigger = onTriggered
            onArmed(status)
        }

        override fun stop() {
            stops++
        }

        override suspend fun awaitStopped(timeoutMs: Long) {
            if (awaitDelayMs > 0) delay(awaitDelayMs)
        }
    }

    private fun idle(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
