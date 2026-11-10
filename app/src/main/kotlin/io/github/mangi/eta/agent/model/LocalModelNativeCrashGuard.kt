package io.github.mangi.eta.agent.model

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal enum class NativeModelEngine(val runtimeId: String) {
    LITERT_LM("litert-lm@0.19.1"),
    LLAMA_CPP("llama.cpp@iris-2.4.5"),
    MNN("mnn@iris-2.4.5"),
}

internal enum class NativeLaunchKind {
    USER,
    AUTOMATIC,
}

internal data class NativeMemoryPlan(
    val settings: LocalModelRuntimeSettings,
    val estimatedWorkingSetBytes: Long,
    val availableRamBytes: Long,
    val reserveBytes: Long,
    val reducedContextWindow: Boolean,
)

/**
 * Persists the narrow window in which a native model load can terminate the process. Java
 * exceptions cannot contain a SIGABRT/SIGSEGV, so the next process start reconciles this journal
 * with ApplicationExitInfo before another automatic launch can repeat the same dangerous path.
 *
 * A successful real conversation warm-up clears the active journal. A GPU native crash leaves the
 * same model on CPU for subsequent attempts, while two crashes of the exact model/backend/runtime
 * combination disable unattended automatic retries until the user chooses a different model.
 */
internal object LocalModelNativeCrashGuard {
    private const val PREFS = "eta_local_model_native_crash_guard"
    private const val KEY_ATTEMPT = "active_attempt"
    private const val KEY_FAILURES = "native_failures"
    private const val MAX_FAILURE_RECORDS = 16
    private const val DEFAULT_RESERVE_BYTES = 512L * 1024 * 1024
    private const val MIN_CONTEXT_WINDOW = 2_048

    @Volatile private var appContext: Context? = null
    private val reconciled = AtomicBoolean(false)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (reconciled.compareAndSet(false, true)) reconcilePendingCrash(context.applicationContext)
    }

    /**
     * Records a native phase before entering JNI. The write is committed synchronously because a
     * native crash can occur on the very next instruction and an async SharedPreferences write is
     * not guaranteed to reach disk.
     */
    fun beginAttempt(
        record: LocalModelRecord,
        engine: NativeModelEngine,
        backend: String,
        phase: String,
        launchKind: NativeLaunchKind = NativeLaunchKind.USER,
    ): NativeAttemptLease {
        val context = requireNotNull(appContext) { "本地模型崩溃保护尚未初始化" }
        if (reconciled.compareAndSet(false, true)) reconcilePendingCrash(context)
        val normalizedBackend = normalizeBackend(backend)
        val attempt = NativeAttempt(
            recordId = record.id,
            modelSha256 = record.sha256,
            engine = engine,
            backend = normalizedBackend,
            phase = phase.take(80),
            startedAt = System.currentTimeMillis(),
        )
        if (launchKind == NativeLaunchKind.AUTOMATIC) {
            val failures = consecutiveFailures()
            val repeated = failures.firstOrNull {
                it.matches(attempt, includeBackend = true) && it.consecutiveCount >= 2
            }
            check(repeated == null) {
                "上次自动启动在此模型、${attempt.backendLabel()}和当前运行库版本上连续崩溃两次；请手动选择 CPU、缩短上下文或更换模型后再试"
            }
        }
        preferences(context).edit().putString(KEY_ATTEMPT, attempt.toJson().toString()).commit()
        return NativeAttemptLease(attempt)
    }

    fun phase(attempt: NativeAttemptLease, phase: String) {
        val context = appContext ?: return
        preferences(context).edit()
            .putString(KEY_ATTEMPT, attempt.snapshot.copy(phase = phase.take(80)).toJson().toString())
            .commit()
    }

    /** Call only after a real token has been received from the selected backend. */
    fun healthy(attempt: NativeAttemptLease) {
        val context = appContext ?: return
        preferences(context).edit().remove(KEY_ATTEMPT).commit()
        clearConsecutiveFailure(context, attempt.snapshot)
    }

    /** A managed Kotlin failure is not a native process crash and must not permanently quarantine. */
    fun failed(attempt: NativeAttemptLease) {
        val context = appContext ?: return
        val active = readAttempt(preferences(context))
        if (active?.signature() == attempt.snapshot.signature()) {
            preferences(context).edit().remove(KEY_ATTEMPT).commit()
        }
    }

    fun gpuRequiresCpuFallback(record: LocalModelRecord, engine: NativeModelEngine): Boolean {
        val attempt = NativeAttempt(
            recordId = record.id,
            modelSha256 = record.sha256,
            engine = engine,
            backend = "gpu",
            phase = "probe",
            startedAt = 0L,
        )
        return consecutiveFailures().any {
            it.matches(attempt, includeBackend = true) && it.consecutiveCount > 0
        }
    }

    fun recentAutomaticBlockReason(record: LocalModelRecord, engine: NativeModelEngine): String? {
        val prefix = NativeAttempt(
            recordId = record.id,
            modelSha256 = record.sha256,
            engine = engine,
            backend = "",
            phase = "probe",
            startedAt = 0L,
        )
        return consecutiveFailures()
            .filter { it.matches(prefix, includeBackend = false) && it.consecutiveCount >= 2 }
            .maxByOrNull { it.lastCrashAt }
            ?.let {
                "上次自动启动在${it.backendLabel()}上连续发生原生崩溃；已停止自动重试，请改为手动启动或更换模型"
            }
    }

    fun automaticStartBlocked(
        record: LocalModelRecord,
        engine: NativeModelEngine,
        backend: String,
    ): Boolean {
        val probe = NativeAttempt(
            recordId = record.id,
            modelSha256 = record.sha256,
            engine = engine,
            backend = normalizeBackend(backend),
            phase = "probe",
            startedAt = 0L,
        )
        return consecutiveFailures().any {
            it.matches(probe, includeBackend = true) && it.consecutiveCount >= 2
        }
    }

    /**
     * Keeps large native allocations below the system pressure point. When a long context no
     * longer fits, the context is lowered to a safe floor before the native runtime is entered.
     */
    fun memoryPlan(
        context: Context,
        record: LocalModelRecord,
        settings: LocalModelRuntimeSettings,
        engine: NativeModelEngine,
    ): NativeMemoryPlan {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val available = memory.availMem.coerceAtLeast(0L)
        val reserve = DEFAULT_RESERVE_BYTES
        val weights = weightedRuntimeBytes(record.sizeBytes.coerceAtLeast(1L), engine)
        val fixed = fixedRuntimeOverheadBytes(engine)
        val perTokenKvBytes = perTokenKvBytes(engine)
        val maxContext = supportedContext(record.format, settings.contextWindow)
        var effective = settings.copy(contextWindow = maxContext)
        var estimate = weights + fixed + effective.contextWindow.coerceAtLeast(1) * perTokenKvBytes
        val fits = { current: LocalModelRuntimeSettings ->
            weights + fixed + current.contextWindow.coerceAtLeast(1) * perTokenKvBytes + reserve <= available
        }
        if (!fits(effective)) {
            val budgetForContext = (available - reserve - weights - fixed).coerceAtLeast(0L)
            val safe = (budgetForContext / perTokenKvBytes).toInt().coerceAtLeast(MIN_CONTEXT_WINDOW)
            effective = effective.copy(contextWindow = minOf(maxContext, safe))
            estimate = weights + fixed + effective.contextWindow * perTokenKvBytes
        }
        check(fits(effective)) {
            "当前可用内存不足：预计还需 ${formatMiB(weights + fixed + MIN_CONTEXT_WINDOW * perTokenKvBytes + reserve)}，" +
                "当前可用 ${formatMiB(available)}。请关闭大型应用、缩短上下文或更换更小模型"
        }
        return NativeMemoryPlan(
            settings = effective,
            estimatedWorkingSetBytes = estimate,
            availableRamBytes = available,
            reserveBytes = reserve,
            reducedContextWindow = effective.contextWindow < settings.contextWindow,
        )
    }

    fun exitReasonLabel(exit: ApplicationExitInfo?): String = when (exit?.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_CRASH -> "process-crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        null -> "unobserved"
        else -> "reason-${exit.reason}"
    }

    @Synchronized
    private fun reconcilePendingCrash(context: Context) {
        val prefs = preferences(context)
        val active = readAttempt(prefs) ?: return
        val nativeExit = findNativeExit(context, active.startedAt)
        if (nativeExit != null) {
            recordNativeFailure(prefs, active, nativeExit)
        }
        prefs.edit().remove(KEY_ATTEMPT).commit()
    }

    private fun findNativeExit(context: Context, startedAt: Long): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 20)
                .asSequence()
                .filter { it.timestamp >= startedAt - 2_000L }
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
                .maxByOrNull { it.timestamp }
        }.getOrNull()
    }

    @Synchronized
    private fun recordNativeFailure(
        prefs: android.content.SharedPreferences,
        attempt: NativeAttempt,
        exit: ApplicationExitInfo,
    ) {
        val records = consecutiveFailures().toMutableList()
        val index = records.indexOfFirst { it.matches(attempt, includeBackend = true) }
        val previous = records.getOrNull(index)
        val updated = NativeFailure(
            recordId = attempt.recordId,
            modelSha256 = attempt.modelSha256,
            engine = attempt.engine,
            backend = attempt.backend,
            runtimeId = attempt.runtimeId,
            consecutiveCount = ((previous?.consecutiveCount ?: 0) + 1).coerceAtMost(99),
            lastPhase = attempt.phase,
            lastCrashAt = System.currentTimeMillis(),
            lastDescription = exit.description?.take(180).orEmpty(),
        )
        if (index >= 0) records[index] = updated else records += updated
        val encoded = JSONArray()
        records.sortedByDescending { it.lastCrashAt }
            .take(MAX_FAILURE_RECORDS)
            .forEach { encoded.put(it.toJson()) }
        prefs.edit().putString(KEY_FAILURES, encoded.toString()).commit()
    }

    @Synchronized
    private fun clearConsecutiveFailure(context: Context, attempt: NativeAttempt) {
        val records = consecutiveFailures()
        val index = records.indexOfFirst { it.matches(attempt, includeBackend = true) }
        if (index < 0 || records[index].consecutiveCount == 0) return
        val encoded = JSONArray()
        records.forEachIndexed { current, record ->
            encoded.put(if (current == index) record.copy(consecutiveCount = 0).toJson() else record.toJson())
        }
        preferences(context).edit().putString(KEY_FAILURES, encoded.toString()).commit()
    }

    private fun consecutiveFailures(): List<NativeFailure> {
        val context = appContext ?: return emptyList()
        val raw = preferences(context).getString(KEY_FAILURES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::decodeFailure)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readAttempt(prefs: android.content.SharedPreferences): NativeAttempt? =
        prefs.getString(KEY_ATTEMPT, null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let(::decodeAttempt)
        }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun supportedContext(format: LocalModelFormat, requested: Int): Int = when (format) {
        LocalModelFormat.LITERT_LM -> requested.coerceIn(MIN_CONTEXT_WINDOW, 32_768)
        LocalModelFormat.GGUF -> requested.coerceIn(MIN_CONTEXT_WINDOW, 131_072)
        else -> requested.coerceIn(MIN_CONTEXT_WINDOW, 32_768)
    }

    private fun normalizeBackend(backend: String): String {
        val lower = backend.trim().lowercase()
        return when {
            "npu" in lower || "hexagon" in lower || "qnn" in lower -> "npu"
            "gpu" in lower || "opencl" in lower -> "gpu"
            lower.startsWith("cpu") -> lower
            else -> lower.ifBlank { "unknown" }
        }
    }

    private fun formatMiB(bytes: Long): String =
        String.format(java.util.Locale.US, "%.0f MiB", bytes / 1024.0 / 1024.0)

    @VisibleForTesting
    internal fun estimateWorkingSetBytes(
        recordSize: Long,
        contextWindow: Int,
        engine: NativeModelEngine,
    ): Long = weightedRuntimeBytes(recordSize, engine) +
        fixedRuntimeOverheadBytes(engine) +
        contextWindow * perTokenKvBytes(engine)

    private fun weightedRuntimeBytes(recordSize: Long, engine: NativeModelEngine): Long {
        val multiplier = when (engine) {
            // LiteRT and llama.cpp map most weights directly from the model file. The previous
            // 2.2x LiteRT multiplier counted mapped pages several times and rejected working
            // models before the native loader ever saw them.
            NativeModelEngine.LITERT_LM -> 1.18
            NativeModelEngine.LLAMA_CPP -> 1.05
            NativeModelEngine.MNN -> 1.35
        }
        return (recordSize * multiplier).toLong()
    }

    private fun fixedRuntimeOverheadBytes(engine: NativeModelEngine): Long = when (engine) {
        NativeModelEngine.LITERT_LM -> 128L * 1024 * 1024
        NativeModelEngine.LLAMA_CPP -> 256L * 1024 * 1024
        NativeModelEngine.MNN -> 256L * 1024 * 1024
    }

    private fun perTokenKvBytes(engine: NativeModelEngine): Long = when (engine) {
        NativeModelEngine.LITERT_LM -> 64L * 1024
        NativeModelEngine.LLAMA_CPP -> 80L * 1024
        NativeModelEngine.MNN -> 72L * 1024
    }
}

internal class NativeAttemptLease internal constructor(internal val snapshot: NativeAttempt) {
    private val completed = AtomicBoolean(false)

    /** Marks that native generation produced text. The breadcrumb intentionally stays armed. */
    fun firstTokenReceived() = Unit

    /** Disarms the crash breadcrumb only after the whole native turn completed successfully. */
    fun completed() {
        if (completed.compareAndSet(false, true)) LocalModelNativeCrashGuard.healthy(this)
    }

    internal fun markPhase(phase: String) = LocalModelNativeCrashGuard.phase(this, phase)
}

internal data class NativeAttempt(
    val recordId: String,
    val modelSha256: String,
    val engine: NativeModelEngine,
    val backend: String,
    val phase: String,
    val startedAt: Long,
    val runtimeId: String = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/${engine.runtimeId}",
) {
    fun signature(): String = "$modelSha256|${engine.runtimeId}|$runtimeId|$backend"

    fun backendLabel(): String = when {
        backend.startsWith("npu") -> "NPU"
        backend.startsWith("gpu") -> "GPU"
        else -> "CPU"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("recordId", recordId)
        .put("modelSha256", modelSha256)
        .put("engine", engine.name)
        .put("backend", backend)
        .put("phase", phase)
        .put("startedAt", startedAt)
        .put("runtimeId", runtimeId)
}

private data class NativeFailure(
    val recordId: String,
    val modelSha256: String,
    val engine: NativeModelEngine,
    val backend: String,
    val runtimeId: String,
    val consecutiveCount: Int,
    val lastPhase: String,
    val lastCrashAt: Long,
    val lastDescription: String,
) {
    fun matches(attempt: NativeAttempt, includeBackend: Boolean): Boolean =
        modelSha256 == attempt.modelSha256 &&
            engine == attempt.engine &&
            runtimeId == attempt.runtimeId &&
            (!includeBackend || backend == attempt.backend)

    fun backendLabel(): String = when {
        backend.startsWith("npu") -> " NPU "
        backend.startsWith("gpu") -> " GPU "
        else -> " CPU "
    }

    fun toJson(): JSONObject = JSONObject()
        .put("recordId", recordId)
        .put("modelSha256", modelSha256)
        .put("engine", engine.name)
        .put("backend", backend)
        .put("runtimeId", runtimeId)
        .put("consecutiveCount", consecutiveCount)
        .put("lastPhase", lastPhase)
        .put("lastCrashAt", lastCrashAt)
        .put("lastDescription", lastDescription)
}

private fun decodeAttempt(json: JSONObject): NativeAttempt? = runCatching {
    NativeAttempt(
        recordId = json.getString("recordId"),
        modelSha256 = json.getString("modelSha256"),
        engine = NativeModelEngine.valueOf(json.getString("engine")),
        backend = json.getString("backend"),
        phase = json.optString("phase"),
        startedAt = json.getLong("startedAt"),
        runtimeId = json.getString("runtimeId"),
    )
}.getOrNull()

private fun decodeFailure(json: JSONObject): NativeFailure? = runCatching {
    NativeFailure(
        recordId = json.getString("recordId"),
        modelSha256 = json.getString("modelSha256"),
        engine = NativeModelEngine.valueOf(json.getString("engine")),
        backend = json.getString("backend"),
        runtimeId = json.getString("runtimeId"),
        consecutiveCount = json.optInt("consecutiveCount"),
        lastPhase = json.optString("lastPhase"),
        lastCrashAt = json.optLong("lastCrashAt"),
        lastDescription = json.optString("lastDescription"),
    )
}.getOrNull()
