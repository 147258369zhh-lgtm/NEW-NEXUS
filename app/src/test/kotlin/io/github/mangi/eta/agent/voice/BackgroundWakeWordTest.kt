package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Intent
import android.os.Looper
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundWakeWordTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun customChinesePhrasesCompileIntoNativeVocabularyAndCannotInjectKeywordControls() {
        val file = temp.newFile("tokens.txt")
        file.writeText("<blk> 0\nx 1\niǎo 2\nài 3\nt 4\nóng 5\nué 6\n")
        val compiled = WakeKeywordCompiler.compile(listOf("小爱同学"), file)
        assertEquals("x iǎo ài t óng x ué @小爱同学", compiled)
        assertThrows(IllegalArgumentException::class.java) { WakeKeywordCompiler.compile(listOf("小爱@其他"), file) }
        assertThrows(IllegalStateException::class.java) { WakeKeywordCompiler.compile(listOf("missing"), file) }
    }

    @Test fun trainedSpeakerFeaturesRequireTheExactModelIdentityAndDoNotAcceptLegacyVectors() {
        val store = VoiceprintProfileStore(context)
        val vector = LocalSpeakerModel.quantize(FloatArray(192) { (it - 90).toFloat() })!!
        store.enroll("owner", vector, "trained-model-one")
        assertEquals(VoiceprintVerification.VERIFIED, store.verify(vector, .72f, "trained-model-one"))
        assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(vector, .72f, "trained-model-two"))
        assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(vector, .72f))
        assertNull(LocalSpeakerModel.quantize(FloatArray(192)))
        assertNull(LocalSpeakerModel.quantize(FloatArray(192) { Float.NaN }))
        assertArrayEquals(vector, LocalSpeakerModel.quantize(FloatArray(192) { (it - 90) * 3f }))
    }

    private fun setup() = runBlocking {
        SettingsDataStore.init(context)
        SpeechSettingsRepository.save(SpeechSettings())
        val root = context.filesDir.resolve("local_models").apply { mkdirs() }
        root.resolve("index-v1.json").writeText(JSONObject().put("version", 2).put("models", JSONArray().put(JSONObject()
            .put("id", "wake-fixture").put("displayName", "Wake fixture").put("fileName", "wake-fixture")
            .put("format", "ONNX").put("sizeBytes", 100).put("sha256", "a".repeat(64))
            .put("inputModalities", JSONArray(listOf("audio"))).put("outputModalities", JSONArray(listOf("keyword"))))).toString())
        LocalSpeechRuntime.initialize(context)
        WakeWordStore(context).write(WakeWordConfig(enabled = true, modelId = "wake-fixture", words = listOf("小爱同学")))
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(BackgroundWakeWordRuntime.state.value.toString(), condition())
    }
    private class Capture : SpeechAudioCapture {
        val source = speechFrameChannel()
        val stopped = CompletableDeferred<Unit>()
        @Volatile var canceled = false
        override val frames: ReceiveChannel<ByteArray> get() = source
        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) { onReady(); stopped.await() }
        override fun finish() { source.close(); stopped.complete(Unit) }
        override fun cancel() { canceled = true; source.cancel(); stopped.complete(Unit) }
    }

    @Test fun actualForegroundServiceYieldsMicrophoneResumesAndSendsWakeToTheExistingAssistant() {
        setup()
        val captures = CopyOnWriteArrayList<Capture>()
        val trigger = AtomicBoolean(false); var closed = 0
        val owner = Robolectric.buildService(BackgroundWakeWordService::class.java).create()
        val service = owner.get()
        service.captureFactory = { Capture().also(captures::add) }
        service.engineFactory = { _, _, _ -> object : WakeWordEngine {
            override fun accept(frame: ByteArray) = if (trigger.compareAndSet(true, false)) "小爱同学" else null
            override fun close() { closed++ }
        } }
        val lease = SpeechAudioLease(context) {}
        try {
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent(context, BackgroundWakeWordService::class.java).setAction(BackgroundWakeWordService.START), 0, 1))
            await { BackgroundWakeWordRuntime.state.value.phase == WakeListeningPhase.LISTENING }
            lease.acquire(false)
            val released = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { BackgroundWakeWordRuntime.yieldMicrophone() }
            await { released.isCompleted }
            runBlocking { released.await() }
            await { captures.first().canceled && BackgroundWakeWordRuntime.state.value.phase == WakeListeningPhase.PAUSED }
            lease.close()
            await { captures.size >= 2 && BackgroundWakeWordRuntime.state.value.phase == WakeListeningPhase.LISTENING }
            trigger.set(true); captures.last().source.trySend(ByteArray(6400))
            await { BackgroundWakeWordRuntime.state.value.keyword != null }
            val next = shadowOf(context).nextStartedService
            assertEquals(EtaAssistantOverlayService::class.java.name, next.component!!.className)
            assertEquals("io.github.zhh_lgtm.nexus.WAKE_ASSISTANT", next.action)
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally {
            lease.close(); owner.destroy()
            await { captures.all { it.canceled } && closed >= 1 && BackgroundWakeWordRuntime.state.value.phase == WakeListeningPhase.STOPPED }
        }
        assertEquals(WakeListeningPhase.STOPPED, BackgroundWakeWordRuntime.state.value.phase)
    }

    @Test fun nullRestartDoesNotAutomaticallyReopenTheMicrophone() {
        setup()
        val owner = Robolectric.buildService(BackgroundWakeWordService::class.java).create()
        try {
            owner.get().captureFactory = { error("A reboot/restart must not open microphone") }
            assertEquals(android.app.Service.START_NOT_STICKY, owner.get().onStartCommand(null, 0, 1))
            assertTrue(shadowOf(owner.get()).isStoppedBySelf)
        } finally { owner.destroy() }
    }

    @Test fun cancelingEnrollmentReleasesARecorderThatIsWaitingForInputAndCannotSaveALateProfile() {
        setup()
        val captured = Capture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val enrollment = VoiceprintEnrollmentController(context, scope, recorderFactory = { captured }, onProfile = { fail("Canceled enrollment must not save") })
        try {
            enrollment.start()
            await { SpeechAudioLease.occupied.value }
            enrollment.cancel()
            await { captured.canceled && !SpeechAudioLease.occupied.value }
            assertFalse(enrollment.state.value.active)
            assertNull(VoiceprintProfileStore(context).profile())
        } finally { enrollment.cancel(); scope.cancel() }
    }
}
