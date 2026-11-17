package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.repository.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LocalSpeechNativeCrashGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("eta_local_speech_native_crash_guard", Context.MODE_PRIVATE)
    private fun record(id: String) = LocalModelRecord(id = id, displayName = id, fileName = "$id.litertlm", format = LocalModelFormat.LITERT_LM,
        sizeBytes = 100, sha256 = "a".repeat(64), sourceUrl = null, installedAt = 1)

    @Test fun simultaneousAsrAndTtsKeepIndependentActualBackendReceipts() {
        LocalSpeechNativeCrashGuard.initialize(context)
        val asr = LocalSpeechNativeCrashGuard.beginAttempt(record("asr"), LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.CPU, "asr-load")
        val tts = LocalSpeechNativeCrashGuard.beginAttempt(record("tts"), LocalSpeechKind.TTS, LocalRuntimeBackendPolicy.GPU, "tts-load")
        asr.phase("asr-stream")
        assertEquals("cpu", JSONObject(prefs.getString("active_attempt_ASR", null)!!).getString("backend"))
        assertEquals("gpu", JSONObject(prefs.getString("active_attempt_TTS", null)!!).getString("backend"))
        assertEquals(android.os.Process.myPid(), JSONObject(prefs.getString("active_attempt_ASR", null)!!).getInt("processId"))
        tts.completed()
        assertFalse(prefs.contains("active_attempt_TTS"))
        assertEquals("asr-stream", JSONObject(prefs.getString("active_attempt_ASR", null)!!).getString("phase"))
        asr.completed()
        assertFalse(prefs.contains("active_attempt_ASR"))
    }

    @Test fun repeatedFramesDoNotRewriteTheJournalAndOldLeaseCannotEraseNewerAttempt() {
        LocalSpeechNativeCrashGuard.initialize(context)
        val first = LocalSpeechNativeCrashGuard.beginAttempt(record("asr"), LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.CPU, "load")
        first.phase("stream")
        var writes = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "active_attempt_ASR") writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            repeat(100) { first.phase("stream") }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, writes)
            val newer = LocalSpeechNativeCrashGuard.beginAttempt(record("newer"), LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.GPU, "load-new")
            first.phase("late-frame"); first.completed(); first.phase("even-later")
            assertEquals("newer", JSONObject(prefs.getString("active_attempt_ASR", null)!!).getString("recordId"))
            newer.completed()
            assertFalse(prefs.contains("active_attempt_ASR"))
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun onnxCpuCrashBlocksOnlyThatModelAndEngineUntilAnExplicitRetry() {
        LocalSpeechNativeCrashGuard.initialize(context)
        val model = record("asr")
        val attempt = LocalSpeechNativeCrashGuard.beginAttempt(model, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.CPU,
            "decode", engineId = "sherpa-onnx@2.0.1")
        prefs.edit().putString("failures", org.json.JSONArray().put(attempt.attempt.encode()).toString()).commit()
        attempt.failed()
        assertTrue(LocalSpeechNativeCrashGuard.hasNativeFailure(model, LocalSpeechKind.ASR, "sherpa-onnx@2.0.1"))
        assertFalse(LocalSpeechNativeCrashGuard.hasNativeFailure(model, LocalSpeechKind.TTS, "sherpa-onnx@2.0.1"))
        assertFalse(LocalSpeechNativeCrashGuard.hasNativeFailure(model, LocalSpeechKind.ASR, "other-engine"))
        LocalSpeechNativeCrashGuard.confirmRetry(model, LocalSpeechKind.ASR, "sherpa-onnx@2.0.1")
        assertFalse(LocalSpeechNativeCrashGuard.hasNativeFailure(model, LocalSpeechKind.ASR, "sherpa-onnx@2.0.1"))
    }
}
