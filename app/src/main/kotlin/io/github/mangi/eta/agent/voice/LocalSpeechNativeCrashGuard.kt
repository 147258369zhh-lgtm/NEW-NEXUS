package io.github.mangi.eta.agent.voice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * MediaPipe speech runs in native code. A GPU/NPU driver fault can terminate the process before
 * Kotlin can catch it, so persist a synchronous launch breadcrumb and reconcile it with Android's
 * exit history on the next process start. The normal in-process delegate probe still handles
 * ordinary Java exceptions; this guard covers the process-killing path.
 */
internal object LocalSpeechNativeCrashGuard {
    private const val PREFS = "eta_local_speech_native_crash_guard"
    private const val KEY_ACTIVE = "active_attempt"
    private const val KEY_ACTIVE_PREFIX = "active_attempt_"
    private const val KEY_FAILURES = "failures"
    private const val ENGINE_ID = "mediapipe-tasks-audio@1.2.2"
    private const val MAX_FAILURES = 16

    @Volatile private var context: Context? = null
    private val reconciled = AtomicBoolean(false)

    @Synchronized fun initialize(appContext: Context) {
        val application = appContext.applicationContext
        if (context !== application) reconciled.set(false)
        context = application
        if (reconciled.compareAndSet(false, true)) reconcile(application)
    }

    @Synchronized fun beginAttempt(
        record: LocalModelRecord,
        kind: LocalSpeechKind,
        policy: LocalRuntimeBackendPolicy,
        phase: String,
        engineId: String = ENGINE_ID,
    ): SpeechAttemptLease {
        val appContext = requireNotNull(context) { "本地语音崩溃保护尚未初始化" }
        if (reconciled.compareAndSet(false, true)) reconcile(appContext)
        val attempt = SpeechAttempt(
            recordId = record.id,
            modelSha256 = record.sha256,
            kind = kind,
            backend = normalize(policy),
            phase = phase.take(80),
            startedAt = System.currentTimeMillis(),
            processName = android.app.Application.getProcessName(),
            processId = android.os.Process.myPid(),
            runtimeId = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/$engineId",
            journalSlot = when { engineId.startsWith("sherpa-speaker") -> "SPEAKER"; engineId.startsWith("sherpa-wake") -> "WAKE"; else -> kind.name },
        )
        check(prefs(appContext).edit().putString(activeKey(attempt), attempt.encode().toString()).commit()) { "LOCAL_SPEECH_JOURNAL_WRITE_FAILED" }
        return SpeechAttemptLease(attempt)
    }

    fun safePolicy(
        record: LocalModelRecord,
        kind: LocalSpeechKind,
        requested: LocalRuntimeBackendPolicy,
    ): LocalRuntimeBackendPolicy {
        val failures = failures()
        fun crashed(backend: String) = failures.any {
            it.recordId == record.id &&
                it.modelSha256 == record.sha256 &&
                it.kind == kind &&
                it.backend == backend &&
                it.runtimeId == runtimeId()
        }
        return when (requested) {
            LocalRuntimeBackendPolicy.NPU -> when {
                !crashed("npu") -> LocalRuntimeBackendPolicy.NPU
                !crashed("gpu") -> LocalRuntimeBackendPolicy.GPU
                else -> LocalRuntimeBackendPolicy.CPU
            }
            LocalRuntimeBackendPolicy.GPU ->
                if (crashed("gpu")) LocalRuntimeBackendPolicy.CPU else LocalRuntimeBackendPolicy.GPU
            LocalRuntimeBackendPolicy.AUTO -> when {
                !crashed("gpu") -> LocalRuntimeBackendPolicy.GPU
                else -> LocalRuntimeBackendPolicy.CPU
            }
            LocalRuntimeBackendPolicy.CPU -> LocalRuntimeBackendPolicy.CPU
        }
    }

    fun fallbackReason(
        record: LocalModelRecord,
        kind: LocalSpeechKind,
        requested: LocalRuntimeBackendPolicy,
    ): String? {
        val safe = safePolicy(record, kind, requested)
        return if (safe != requested && requested != LocalRuntimeBackendPolicy.AUTO) {
            "上次在${requested.name}上发生原生崩溃，已安全切换到${safe.name}"
        } else null
    }

    fun hasNativeFailure(record: LocalModelRecord, kind: LocalSpeechKind, engineId: String): Boolean = failures().any {
        it.recordId == record.id && it.modelSha256 == record.sha256 && it.kind == kind && it.backend == "cpu" &&
            it.runtimeId == "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/$engineId"
    }

    @Synchronized fun confirmRetry(record: LocalModelRecord, kind: LocalSpeechKind, engineId: String) {
        val retained = failures().filterNot {
            it.recordId == record.id && it.modelSha256 == record.sha256 && it.kind == kind &&
                it.runtimeId == "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/$engineId"
        }
        val preferences = context?.let(::prefs) ?: return
        check(preferences.edit().putString(KEY_FAILURES, JSONArray(retained.map { it.encode() }).toString()).commit()) { "LOCAL_SPEECH_JOURNAL_WRITE_FAILED" }
    }

    private fun reconcile(appContext: Context) {
        val preferences = prefs(appContext)
        for (key in listOf(KEY_ACTIVE) + preferences.all.keys.filter { it.startsWith(KEY_ACTIVE_PREFIX) }) {
            val active = preferences.getString(key, null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
            try {
                if (active != null && findNativeExit(appContext, active) != null) recordFailure(preferences, active)
            } finally { preferences.edit().remove(key).commit() }
        }
    }

    private fun findNativeExit(appContext: Context, attempt: SpeechAttempt): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(appContext.packageName, attempt.processId, 24)
                .asSequence()
                .filter { it.timestamp >= attempt.startedAt - 2_000L }
                .filter { attempt.processName.isBlank() || it.processName == attempt.processName }
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
                .maxByOrNull(ApplicationExitInfo::getTimestamp)
        }.getOrNull()
    }

    private fun recordFailure(preferences: android.content.SharedPreferences, attempt: SpeechAttempt) {
        val all = failures().toMutableList()
        val existing = all.indexOfFirst {
            it.recordId == attempt.recordId &&
                it.modelSha256 == attempt.modelSha256 &&
                it.kind == attempt.kind &&
                it.backend == attempt.backend &&
                it.runtimeId == attempt.runtimeId
        }
        val updated = attempt.copy(phase = attempt.phase, crashedAt = System.currentTimeMillis())
        if (existing >= 0) all[existing] = updated else all += updated
        val array = JSONArray()
        all.sortedByDescending { it.crashedAt }.take(MAX_FAILURES).forEach { array.put(it.encode()) }
        preferences.edit().putString(KEY_FAILURES, array.toString()).commit()
    }

    private fun failures(): List<SpeechAttempt> {
        val appContext = context ?: return emptyList()
        val raw = prefs(appContext).getString(KEY_FAILURES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.let { add(decode(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(appContext: Context) = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun activeKey(attempt: SpeechAttempt) = "$KEY_ACTIVE_PREFIX${attempt.journalSlot}"

    @Synchronized private fun writePhase(attempt: SpeechAttempt, phase: String) {
        val preferences = context?.let(::prefs) ?: return
        val key = activeKey(attempt)
        val current = preferences.getString(key, null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
        if (current?.attemptId != attempt.attemptId || current.phase == phase) return
        check(preferences.edit().putString(key, current.copy(phase = phase).encode().toString()).commit()) { "LOCAL_SPEECH_JOURNAL_WRITE_FAILED" }
    }

    @Synchronized private fun clearAttempt(attempt: SpeechAttempt) {
        val preferences = context?.let(::prefs) ?: return
        val key = activeKey(attempt)
        val current = preferences.getString(key, null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
        if (current?.attemptId == attempt.attemptId) preferences.edit().remove(key).commit()
    }

    private fun normalize(policy: LocalRuntimeBackendPolicy): String = when (policy) {
        LocalRuntimeBackendPolicy.NPU -> "npu"
        LocalRuntimeBackendPolicy.GPU, LocalRuntimeBackendPolicy.AUTO -> "gpu"
        LocalRuntimeBackendPolicy.CPU -> "cpu"
    }

    private fun runtimeId(): String = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/$ENGINE_ID"

    internal class SpeechAttemptLease internal constructor(
        internal val attempt: SpeechAttempt,
    ) {
        private val finished = AtomicBoolean(false)

        fun phase(value: String) {
            if (!finished.get()) writePhase(attempt, value.take(80))
        }

        fun completed() {
            if (!finished.compareAndSet(false, true)) return
            clearAttempt(attempt)
        }

        /** A managed exception is not a native crash and must not poison the next launch. */
        fun failed() = completed()
    }

    internal data class SpeechAttempt(
        val recordId: String,
        val modelSha256: String,
        val kind: LocalSpeechKind,
        val backend: String,
        val phase: String,
        val startedAt: Long,
        val crashedAt: Long = 0L,
        val runtimeId: String = runtimeId(),
        val attemptId: String = java.util.UUID.randomUUID().toString(),
        val processName: String = "",
        val processId: Int = 0,
        val journalSlot: String = kind.name,
    ) {
        fun encode(): JSONObject = JSONObject()
            .put("recordId", recordId)
            .put("modelSha256", modelSha256)
            .put("kind", kind.name)
            .put("backend", backend)
            .put("phase", phase)
            .put("startedAt", startedAt)
            .put("crashedAt", crashedAt)
            .put("runtimeId", runtimeId)
            .put("attemptId", attemptId).put("processName", processName).put("processId", processId)
            .put("journalSlot", journalSlot)
    }

    private fun decode(json: JSONObject) = SpeechAttempt(
        recordId = json.getString("recordId"),
        modelSha256 = json.getString("modelSha256"),
        kind = LocalSpeechKind.valueOf(json.getString("kind")),
        backend = json.getString("backend"),
        phase = json.optString("phase"),
        startedAt = json.getLong("startedAt"),
        crashedAt = json.optLong("crashedAt"),
        runtimeId = json.getString("runtimeId"),
        attemptId = json.optString("attemptId", "legacy"),
        processName = json.optString("processName"), processId = json.optInt("processId", 0),
        journalSlot = json.optString("journalSlot", json.getString("kind")).also { require(it in setOf("ASR", "TTS", "SPEAKER", "WAKE")) },
    )
}
