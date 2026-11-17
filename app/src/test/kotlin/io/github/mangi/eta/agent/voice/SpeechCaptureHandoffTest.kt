package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SpeechCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
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
class SpeechCaptureHandoffTest {
    @Test fun prerollIsBoundedCopiesReusedNativeBuffersAndPreservesTheLatestPcmOrder() {
        val buffer = SpeechPrerollBuffer(8)
        val frame = byteArrayOf(1, 2, 3, 4)
        buffer.add(frame); frame.fill(9)
        buffer.add(byteArrayOf(5, 6, 7, 8)); buffer.add(byteArrayOf(9, 10))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10), buffer.drain().fold(byteArrayOf()) { a, b -> a + b })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun sourceAudioHasAFixedLimitAndRepeatedCancelStopsTheNativeOwnerOnlyOnce() = runBlocking {
        var stopped = 0
        val capture = SpeechCaptureHandoff { stopped++ }
        assertTrue(capture.offer(ByteArray(SpeechRecorder.MAX_BYTES)))
        assertFalse(capture.offer(byteArrayOf(1, 2)))
        capture.cancel(); capture.cancel()
        assertEquals(1, stopped)
    }

    @Test fun microphoneFailureIsDeliveredToTheRecognizingOwnerInsteadOfClosingAsSuccessfulAudio() = runBlocking {
        val capture = SpeechCaptureHandoff {}
        capture.offer(byteArrayOf(1, 2))
        capture.sourceClosed(IllegalStateException("capture failed"))
        assertArrayEquals(byteArrayOf(1, 2), capture.frames.receive())
        assertTrue(capture.frames.receiveCatching().exceptionOrNull() is IllegalStateException)
    }

    @Test fun voiceInterruptionReusesTheOriginalCaptureAndDeliversOnsetToTheRealInputController() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val received = mutableListOf<Byte>(); val results = mutableListOf<String>(); val order = mutableListOf<String>()
        lateinit var capture: SpeechCaptureHandoff
        capture = SpeechCaptureHandoff { order += "native-stop"; capture.sourceClosed() }
        capture.offer(byteArrayOf(1, 2, 3, 4))
        val input = SpeechInputController(RuntimeEnvironment.getApplication(), scope,
            beforeCapture = { order += "asr-capture" },
            createRecorder = { error("Interruption must not open a second microphone") },
            recognizeStream = { _, _, frames: ReceiveChannel<ByteArray>, ready, _, _, _ ->
                ready()
                for (frame in frames) received += frame.toList()
                "user command"
            }, onResult = results::add)
        val monitor = object : BargeInMonitor {
            var trigger: (() -> Unit)? = null
            override fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit) {
                trigger = onTriggered; onArmed(BargeInMonitorStatus(true, "AEC"))
            }
            override fun stop() { error("Transferred microphone belongs to ASR") }
            override suspend fun awaitStopped(timeoutMs: Long) { error("Transferred capture remains alive") }
            override fun takeSpeechCapture(): SpeechAudioCapture = capture
        }
        val coordinator = LocalVoiceSessionCoordinator(scope, stopSpeaking = { order += "fade"; delay(50) },
            startListening = { error("Capture must use the handoff path") }, monitorFactory = { monitor },
            onMonitoringChanged = { active -> order += "monitor-$active" },
            startListeningWithCapture = { audio ->
                order += "start-asr"
                input.start(SpeechSettings(), SpeechCredentials(qwenAsr = "test-key"), capturedAudio = audio)
            })
        try {
            coordinator.onSpeakingStarted(); monitor.trigger!!.invoke()
            capture.offer(byteArrayOf(5, 6))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            capture.finish()
            repeat(50) {
                shadowOf(Looper.getMainLooper()).idle()
                if (results.isEmpty()) Thread.sleep(5)
            }
            assertEquals(listOf("user command"), results)
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), received)
            assertTrue(order.indexOf("fade") < order.indexOf("start-asr"))
            assertTrue(order.indexOf("asr-capture") < order.indexOf("monitor-false"))
            assertEquals(1, order.count { it == "native-stop" })
        } finally { input.cancel(); coordinator.release(); scope.cancel() }
    }
}
