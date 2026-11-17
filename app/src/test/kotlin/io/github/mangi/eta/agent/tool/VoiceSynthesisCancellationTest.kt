package io.github.mangi.eta.agent.tool

import android.app.Application
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceSynthesisCancellationTest {
    @Test fun runtimeCancellationStopsTheActualSynthesisCoroutineBeforePublishingAnAudioFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
        SpeechSettingsRepository.save(SpeechSettings(tts = TtsProvider.QWEN))
        val controller = AgentRunController()
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val result = async(Dispatchers.IO) { runCatching { RuntimeInvocationScope.withController(controller) {
            VoiceSynthesisTool.executeOwned(context, JSONObject().put("text", "fixture speech"),
                credentialsProvider = { SpeechCredentials(qwenTts = "fixture-key") }) { _, _, _, _ ->
                entered.countDown()
                try { awaitCancellation() } finally { released.countDown() }
            }
        } } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(withTimeout(2_000) { result.await() }.isFailure)
            assertTrue(released.await(1, TimeUnit.SECONDS))
            val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)!!.resolve("Nexus Voice")
            assertFalse(root.exists())
        } finally { controller.cancel(); result.cancelAndJoin() }
    }
}
