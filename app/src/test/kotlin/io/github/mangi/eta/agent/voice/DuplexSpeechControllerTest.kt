package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DuplexSpeechControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private class Monitor : BargeInMonitor {
        lateinit var frames: (ByteArray) -> Unit
        lateinit var ended: () -> Unit
        lateinit var trigger: () -> Unit
        var stopped = false
        var available = true
        override fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit) {
            trigger = onTriggered; onArmed(BargeInMonitorStatus(available, "AEC", if (available) null else "No AEC"))
        }
        override fun stop() { stopped = true }
        override suspend fun awaitStopped(timeoutMs: Long) = Unit
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(3) }
        assertTrue(condition())
    }

    @Test fun actualAsrCanListenWhilePlaybackKeepsItsLeaseAndInterruptsOnlyOnRecognizedText() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val group = Any(); val monitor = Monitor()
        var playing = true; var interrupted = 0; var playbackCanceled = false
        val results = mutableListOf<String>(); val received = mutableListOf<Byte>()
        val recognize = CompletableDeferred<Unit>()
        val foreground = mutableListOf<Boolean>()
        val playbackLease = SpeechAudioLease(app, duplexGroup = group) { playbackCanceled = true }
        lateinit var duplex: DuplexSpeechController
        val input = SpeechInputController(app, scope, duplexAudioGroup = group,
            createRecorder = { error("Duplex must share the AEC capture") },
            recognizeStream = { _, _, frames, ready, preview, _, _ ->
                ready()
                for (frame in frames) { received += frame.toList(); recognize.await(); preview("recognized user speech") }
                "recognized user speech"
            }, onResult = { text -> results += text; duplex.recognitionFinished() })
        duplex = DuplexSpeechController(app, scope, group, foreground::add,
            onUtterance = { capture -> input.start(SpeechSettings(), SpeechCredentials(qwenAsr = "fixture"), capturedAudio = capture) },
            onInterrupted = { interrupted++; playing = false }, onUnavailable = { fail(it) }, playbackActive = { playing },
            monitorFactory = { frames, ended, _ -> monitor.apply { this.frames = frames; this.ended = ended } })
        val observation = scope.launch { input.state.collect { if (it.preview.isNotBlank()) duplex.transcript(it.preview) } }
        try {
            duplex.start(); playbackLease.acquire(false)
            monitor.frames(byteArrayOf(1, 2, 3, 4)); monitor.trigger()
            assertFalse(playbackCanceled)
            assertEquals(0, interrupted)
            recognize.complete(Unit)
            monitor.frames(byteArrayOf(5, 6))
            until { interrupted == 1 }
            assertTrue(duplex.active); assertTrue(duplex.userSpeaking)
            assertFalse(monitor.stopped); assertFalse(playbackCanceled)
            monitor.ended()
            until { results.size == 1 }
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), received)
            assertTrue(duplex.active); assertFalse(duplex.userSpeaking)
            monitor.frames(byteArrayOf(7, 8)); monitor.trigger(); monitor.frames(byteArrayOf(9, 10)); monitor.ended()
            until { results.size == 2 }
            assertEquals(1, interrupted)
            assertEquals(listOf(true), foreground)
        } finally { input.cancel(); duplex.stop(); observation.cancel(); playbackLease.close(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900)); scope.cancel() }
        assertTrue(monitor.stopped); assertEquals(listOf(true, false), foreground)
        assertFalse(SpeechAudioLease.occupied.value)
    }

    @Test fun unrelatedAudioOwnerStopsTheDuplexCaptureAndUnavailableAecCannotPretendToListen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val monitor = Monitor(); val errors = mutableListOf<String>()
        val duplex = DuplexSpeechController(app, scope, Any(), {}, { fail("No speech expected") }, {}, errors::add, { false },
            monitorFactory = { frames, ended, _ -> monitor.apply { this.frames = frames; this.ended = ended } })
        val outside = SpeechAudioLease(app) {}
        try {
            duplex.start(); assertTrue(duplex.active)
            outside.acquire(false)
            until { !duplex.active && monitor.stopped }
            outside.close()
            monitor.available = false
            duplex.start()
            assertFalse(duplex.active); assertEquals(listOf("No AEC"), errors)
        } finally { outside.close(); duplex.stop(); shadowOf(Looper.getMainLooper()).idle(); scope.cancel() }
    }

    @Test fun restartingBeforeOldCaptureClosesReleasesEachForegroundReservationExactlyOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reservations = 0
        val duplex = DuplexSpeechController(app, scope, Any(), { active -> reservations += if (active) 1 else -1 },
            {}, {}, { fail(it) }, { false }, monitorFactory = { _, _, _ -> object : BargeInMonitor {
                override fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit) { onArmed(BargeInMonitorStatus(true)) }
                override fun stop() = Unit
                override suspend fun awaitStopped(timeoutMs: Long) { delay(100) }
            } })
        try {
            duplex.start(); assertEquals(1, reservations)
            duplex.stop(); duplex.start(); assertEquals(2, reservations)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(1, reservations)
            duplex.stop(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(0, reservations)
            assertFalse(SpeechAudioLease.occupied.value)
        } finally { duplex.stop(); scope.cancel() }
    }

    @Test fun leaseCleanupFailureStillReleasesForegroundReservation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val app = this.app
        var reservations = 0
        val duplex = DuplexSpeechController(app, scope, Any(), { active -> reservations += if (active) 1 else -1 },
            {}, {}, { fail(it) }, { false }, monitorFactory = { _, _, _ -> object : BargeInMonitor {
                override fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit) { onArmed(BargeInMonitorStatus(true)) }
                override fun stop() = Unit
                override suspend fun awaitStopped(timeoutMs: Long) = Unit
            } })
        try {
            duplex.start()
            val field = DuplexSpeechController::class.java.getDeclaredField("lease").apply { isAccessible = true }
            val lease = field.get(duplex) as SpeechAudioLease
            val receiverField = SpeechAudioLease::class.java.getDeclaredField("receiver").apply { isAccessible = true }
            app.unregisterReceiver(receiverField.get(lease) as android.content.BroadcastReceiver)
            duplex.stop()
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { duplex.awaitStopped() }
            assertEquals(0, reservations)
            assertFalse(duplex.active)
            assertFalse(SpeechAudioLease.occupied.value)
        } finally { duplex.stop(); scope.cancel() }
    }
}
