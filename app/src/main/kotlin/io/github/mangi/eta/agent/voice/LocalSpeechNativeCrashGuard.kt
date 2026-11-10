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
    private const val KEY_FAILURES = "failures"
    private const val ENGINE_ID = "mediapipe-tasks-audio@1.2.2"
    private const val MAX_FAILURES = 16

    @Volatile private var context: Context? = null
    private val reconciled = AtomicBoolean(false)

    fun initialize(appContext: Context) {
        val application = appContext.applicationContext
        context = application
        if (reconciled.compareAndSet(false, true)) reconcile(application)
    }

    fun beginAttempt(
        record: LocalModelRecord,
        kind: LocalSpeechKind,
        policy: LocalRuntimeBackendPolicy,
        phase: String,
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
        )
        prefs(appContext).edit().putString(KEY_ACTIVE, attempt.encode().toString()).commit()
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

    private fun reconcile(appContext: Context) {
        val preferences = prefs(appContext)
        val active = preferences.getString(KEY_ACTIVE, null)?.let { raw ->
            runCatching { decode(JSONObject(raw)) }.getOrNull()
        } ?: return
        try {
            if (findNativeExit(appContext, active.startedAt) != null) recordFailure(preferences, active)
        } finally {
            preferences.edit().remove(KEY_ACTIVE).commit()
        }
    }

    private fun findNativeExit(appContext: Context, startedAt: Long): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 24)
                .asSequence()
                .filter { it.timestamp >= startedAt - 2_000L }
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
            val appContext = context ?: return
            val next = attempt.copy(phase = value.take(80))
            prefs(appContext).edit().putString(KEY_ACTIVE, next.encode().toString()).commit()
        }

        fun completed() {
            if (!finished.compareAndSet(false, true)) return
            val appContext = context ?: return
            prefs(appContext).edit().remove(KEY_ACTIVE).commit()
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
    )
}
