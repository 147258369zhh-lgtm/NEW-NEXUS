package io.github.mangi.eta.agent.voice

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SpeechPlaybackForegroundTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<Unit>()
    private var preparing = false
    private var cleanups = 0
    private var outputs = 0
    private var requests = 0
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))
    private val playback = SpeechPlaybackController(context, scope,
        beforePlayback = { preparing = true; ready.await() },
        afterPlayback = { cleanups++ },
        streamSynthesize = null,
        synthesize = { _, _, _, _ -> requests++; awaitCancellation() },
        createOutput = {
            outputs++
            object : SpeechAudioOutput {
                override fun write(bytes: ByteArray) = Unit
                override fun finish() = Unit
                override fun drained() = true
                override fun close() = Unit
            }
        },
    )

    @After fun close() { playback.stop(); scope.cancel() }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue(condition())
    }

    @Test fun serviceReadinessPrecedesFocusOutputAndSynthesis() {
        // 用入口标记而不是等待真实服务调度。
        playback.speak("answer", "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        idleUntil { preparing }
        assertNull(audio.lastAudioFocusRequest)
        assertEquals(0, outputs)
        assertEquals(0, requests)
        ready.complete(Unit)
        idleUntil { requests == 1 }
        assertNotNull(audio.lastAudioFocusRequest)
        assertEquals(1, outputs)
    }

    @Test fun foregroundFailureCleansUpWithoutFocusOrCloudRequest() {
        ready.completeExceptionally(SpeechFailure(SpeechErrorCode.PERMISSION, "foreground denied"))
        playback.speak("answer", "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        idleUntil { playback.state.value.error != null }
        assertEquals("foreground denied", playback.state.value.error)
        assertEquals(1, cleanups)
        assertNull(audio.lastAudioFocusRequest)
        assertEquals(0, outputs)
        assertEquals(0, requests)
    }

    @Test fun stopDuringStartupDiscardsLateAcknowledgement() {
        playback.speak("answer", "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        idleUntil { preparing }
        playback.stop()
        ready.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cleanups)
        assertNull(playback.state.value.messageId)
        assertNull(audio.lastAudioFocusRequest)
        assertEquals(0, outputs)
        assertEquals(0, requests)
    }

    @Test fun microphoneReplacementWhileWaitingDoesNotStartLatePlayback() {
        playback.speak("answer", "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        idleUntil { preparing }
        val microphone = SpeechAudioLease(context) {}
        try {
            microphone.acquire(playback = false)
            ready.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, cleanups)
            assertNull(audio.lastAudioFocusRequest)
            assertEquals(0, outputs)
        } finally { microphone.close() }
    }
}
