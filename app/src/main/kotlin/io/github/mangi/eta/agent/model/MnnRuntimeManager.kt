package io.github.mangi.eta.agent.model

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.iris.localai.engine.mnn.MnnNative
import com.iris.localai.engine.mnn.MnnPieceSink
import com.iris.localai.engine.mnn.MnnSink
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.LocalRuntimeBackend
import io.github.mangi.eta.agent.runtime.LocalRuntimeMetrics
import io.github.mangi.eta.data.repository.LocalModelPerformanceMonitor
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import io.github.mangi.eta.data.repository.LocalModelRuntimeSnapshot
import io.github.mangi.eta.data.repository.LocalModelRuntimeState
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import io.github.mangi.eta.data.repository.LocalRuntimePowerPolicy
import io.github.mangi.eta.data.repository.LocalRuntimeThermalPolicy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide MNN-LLM runtime for complete model directories.
 *
 * The adapter is deliberately independent from LiteRT-LM and llama.cpp. It uses IRIDIS's
 * Apache-2.0 MNN JNI ABI, keeps one model resident, supports CPU/OpenCL, streams text, exposes a
 * native cancel path and keeps native loading behind the shared crash and memory guards.
 */
internal object MnnRuntimeManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutex = Mutex()

    @Volatile private var appContext: Context? = null
    private var active: ActiveRuntime? = null
    private var idleJob: Job? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        LocalModelRepository.init(context.applicationContext)
        LocalModelNativeCrashGuard.initialize(context.applicationContext)
    }

    suspend fun warmUp(
        recordId: String,
        launchKind: NativeLaunchKind = NativeLaunchKind.USER,
    ): LocalModelRuntimeSnapshot = withContext(inferenceDispatcher) {
        mutex.withLock {
            val runtime = acquire(recordId, forceReload = false, launchKind = launchKind)
            scheduleIdleUnload(recordId, runtime)
            LocalModelRepository.runtime.value
        }
    }

    suspend fun stop(recordId: String? = null) {
        mutex.withLock {
            val current = active ?: return@withLock
            if (recordId != null && current.recordId != recordId) return@withLock
            idleJob?.cancelAndJoin()
            idleJob = null
            active = null
            closeRuntime(current)
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = current.recordId,
                    state = LocalModelRuntimeState.STOPPED,
                ),
            )
        }
    }

    fun complete(
        recordId: String,
        systemPrompt: String,
        messages: JSONArray,
        runController: AgentRunController,
        onDelta: (String) -> Unit,
    ): LocalRuntimeCompletion = runBlocking(inferenceDispatcher) {
        mutex.withLock {
            checkResources(recordId)
            val runtime = acquire(recordId, forceReload = false, launchKind = NativeLaunchKind.USER)
            val parsedTurns = parseTurns(messages, systemPrompt)
            require(parsedTurns.any { it.role == "user" }) { "MNN 本地模型请求中没有用户消息" }
            val fitted = LocalRuntimePromptBudget.fit(
                turns = parsedTurns,
                contextWindow = runtime.settings.contextWindow,
                maxOutputTokens = runtime.settings.generationOutputBudget(),
                roleOf = MnnTurn::role,
                textOf = MnnTurn::text,
                engine = "MNN",
                recordId = recordId,
            )
            val turns = fitted.turns
            val roles = turns.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val contents = turns.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val settings = runtime.settings
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = recordId,
                    state = LocalModelRuntimeState.GENERATING,
                    backend = runtime.backendName,
                    message = "正在生成",
                    loadedAt = runtime.loadedAt,
                ),
            )
            val record = requireNotNull(LocalModelRepository.mnnDescriptor(recordId)).record
            val attempt = LocalModelNativeCrashGuard.beginAttempt(
                record = record,
                engine = NativeModelEngine.MNN,
                backend = runtime.backendId,
                phase = "generation",
                launchKind = NativeLaunchKind.USER,
            )
            val cancellation = runController.register { nativeCancel(runtime) }
            val rendered = StringBuilder()
            val generationStartedAt = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            var nativeStats: JSONObject? = null
            try {
                MnnNative.nativeClearCancel(runtime.handle)
                LocalRuntimePromptBudget.started(
                    engine = "MNN",
                    recordId = recordId,
                    messages = turns.size,
                    inputChars = turns.sumOf { it.text.length },
                    contextWindow = settings.contextWindow,
                    maxOutputTokens = settings.generationOutputBudget(),
                    thinking = settings.thinkingEnabled,
                )
                val sink = MnnSink { bytes ->
                    if (runController.isCancelled) {
                        false
                    } else {
                        val text = bytes.toString(Charsets.UTF_8)
                        if (text.isNotEmpty()) {
                            if (firstTokenAt == null) {
                                firstTokenAt = SystemClock.elapsedRealtime()
                                LocalRuntimePromptBudget.firstToken(
                                    "MNN", recordId, firstTokenAt!! - generationStartedAt,
                                )
                            }
                            attempt.firstTokenReceived()
                            rendered.append(text)
                            onDelta(text)
                        }
                        true
                    }
                }
                val generationArguments = settings.mnnGenerationArguments()
                val result = JSONObject(
                    MnnNative.nativeGenerate(
                        runtime.handle,
                        roles,
                        contents,
                        generationArguments.floats,
                        generationArguments.ints,
                        IntArray(0),
                        sink,
                    ),
                ).also { nativeStats = it }
                val stop = result.optInt("stop", 4)
                val error = result.optString("error").trim()
                if (runController.isCancelled || stop == 2) throw AgentRunCancelledException()
                if (error.isNotEmpty()) throw IllegalStateException(error)
                if (stop == 3) {
                    throw IllegalStateException("MNN 上下文已满，请缩短对话、压缩上下文或更换上下文设置")
                }
                if (stop == 4) throw IllegalStateException("MNN 引擎执行失败")
                val visible = LocalRuntimePromptBudget.visibleOutput(rendered.toString())
                if (visible.isEmpty()) {
                    throw AgentModelFailure(
                        code = "EMPTY_MODEL_RESPONSE",
                        retryable = false,
                        message = "MNN 模型只返回了空代码块或空白内容，请重试或更换模型。",
                    )
                }
                attempt.completed()
                val tokenCount = result.optInt("generatedTokens", 0).takeIf { it > 0 }
                val generateUs = result.optLong("generateUs", 0L)
                val rate = tokenCount?.takeIf { generateUs > 0 }?.let {
                    it.toDouble() * 1_000_000.0 / generateUs
                }
                LocalRuntimePromptBudget.finished(
                    engine = "MNN",
                    recordId = recordId,
                    outputChars = visible.length,
                    tokens = tokenCount,
                    elapsedMs = SystemClock.elapsedRealtime() - generationStartedAt,
                    stop = stop.toString(),
                )
                scheduleIdleUnload(recordId, runtime)
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = recordId,
                        state = LocalModelRuntimeState.READY,
                        backend = runtime.backendName,
                        message = rate?.let { "已就绪 · ${it.formatRate()} tok/s" } ?: "已就绪",
                        loadedAt = runtime.loadedAt,
                        lastTokenCount = tokenCount,
                    ),
                )
                val finishReason = if (
                    tokenCount != null && tokenCount >= settings.generationOutputBudget()
                ) "length" else "stop"
                val promptTokens = result.optInt("promptTokens", -1).takeIf { it >= 0 }
                val reusedTokens = result.optInt("reusedTokens", -1).takeIf { it >= 0 }
                val prefillUs = result.optLong("prefillUs", -1L).takeIf { it >= 0L }
                val completedAt = System.currentTimeMillis()
                val totalMs = SystemClock.elapsedRealtime() - generationStartedAt
                LocalRuntimeCompletion(
                    visible,
                    tokenCount,
                    contextTokenCount = promptTokens,
                    finishReason = finishReason,
                    metrics = LocalRuntimeMetrics(
                        backend = if (runtime.backendId == "gpu") LocalRuntimeBackend.GPU else LocalRuntimeBackend.CPU,
                        modelFormat = "MNN",
                        modelId = recordId,
                        backendDetail = runtime.backendName,
                        loadMs = runtime.loadDurationMs,
                        firstTokenMs = firstTokenAt?.let { it - generationStartedAt },
                        promptTokens = promptTokens,
                        generatedTokens = tokenCount,
                        tokensPerSecond = rate,
                        promptCacheHitTokens = reusedTokens,
                        promptCacheReusedTokens = reusedTokens,
                        kvCacheUsedTokens = promptTokens,
                        kvCacheCapacityTokens = runtime.settings.contextWindow,
                        contextWindow = runtime.settings.contextWindow,
                        prefillMs = prefillUs?.div(1_000L),
                        decodeMs = generateUs.takeIf { it > 0 }?.div(1_000L),
                        totalMs = totalMs,
                        finishReason = finishReason,
                        startedAt = completedAt - totalMs,
                        completedAt = completedAt,
                    ),
                )
            } catch (failure: Throwable) {
                LocalModelNativeCrashGuard.failed(attempt)
                val mapped = LocalRuntimePromptBudget.failure("MNN", recordId, failure)
                val failureMetrics = LocalRuntimeFailureTelemetry.metrics(
                    format = "MNN",
                    modelId = recordId,
                    failure = mapped,
                    backend = if (runtime.backendId == "gpu") LocalRuntimeBackend.GPU else LocalRuntimeBackend.CPU,
                    backendDetail = runtime.backendName,
                    loadMs = runtime.loadDurationMs,
                    firstTokenMs = firstTokenAt?.let { it - generationStartedAt },
                    stats = nativeStats,
                    contextWindow = runtime.settings.contextWindow,
                    totalMs = SystemClock.elapsedRealtime() - generationStartedAt,
                )
                throw LocalRuntimeFailureTelemetry.attach(mapped, failureMetrics)
            } finally {
                cancellation.close()
                runCatching { MnnNative.nativeClearCancel(runtime.handle) }
            }
        }
    }

    private suspend fun acquire(
        recordId: String,
        forceReload: Boolean,
        launchKind: NativeLaunchKind,
    ): ActiveRuntime {
        val context = requireNotNull(appContext) { "MNN 本地模型运行时尚未初始化" }
        val descriptor = requireNotNull(LocalModelRepository.mnnDescriptor(recordId)) {
            "本地模型记录不存在或不是 MNN 目录模型"
        }
        val requestedSettings = descriptor.settings.validated()
        active?.let { current ->
            if (!forceReload && current.recordId == recordId &&
                !current.requestedSettings.requiresNativeEngineReload(requestedSettings) && current.loaded
            ) {
                current.requestedSettings = requestedSettings
                current.settings = requestedSettings.withLoadedNativeConstraints(current.settings)
                idleJob?.cancel()
                idleJob = null
                return current
            }
            idleJob?.cancelAndJoin()
            idleJob = null
            active = null
            closeRuntime(current)
        }
        MnnNative.unavailableReason?.let { fail(recordId, it) }
        require(descriptor.directory.isDirectory) { "MNN 模型目录已丢失，请重新导入" }
        LocalModelRepository.publishRuntimeSnapshot(
            LocalModelRuntimeSnapshot(
                recordId = recordId,
                state = LocalModelRuntimeState.STARTING,
                message = "正在加载 MNN 模型",
            ),
        )
        val performance = LocalModelPerformanceMonitor.capture(context)
        enforcePolicies(recordId, requestedSettings, performance.powerSaveMode, performance.thermalStatus)
        val effectiveSettings = LocalModelNativeCrashGuard.memoryPlan(
            context = context,
            record = descriptor.record,
            settings = requestedSettings,
            engine = NativeModelEngine.MNN,
        ).settings
        var candidates = when (effectiveSettings.backend) {
            LocalRuntimeBackendPolicy.NPU -> fail(
                recordId,
                "MNN 运行时不支持 Qualcomm NPU；请选择 CPU 或 GPU，或改用芯片专用的 LiteRT-LM NPU 模型",
            )
            LocalRuntimeBackendPolicy.CPU -> listOf(LocalRuntimeBackendPolicy.CPU)
            LocalRuntimeBackendPolicy.GPU -> listOf(LocalRuntimeBackendPolicy.GPU, LocalRuntimeBackendPolicy.CPU)
            LocalRuntimeBackendPolicy.AUTO -> {
                val preferCpu = requestedSettings.thermalPolicy == LocalRuntimeThermalPolicy.STABILITY_FIRST ||
                    performance.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                    performance.powerSaveMode
                if (preferCpu) {
                    listOf(LocalRuntimeBackendPolicy.CPU, LocalRuntimeBackendPolicy.GPU)
                } else {
                    listOf(LocalRuntimeBackendPolicy.GPU, LocalRuntimeBackendPolicy.CPU)
                }
            }
        }.distinct()
        if (LocalModelNativeCrashGuard.gpuRequiresCpuFallback(descriptor.record, NativeModelEngine.MNN)) {
            candidates = candidates.filterNot { it == LocalRuntimeBackendPolicy.GPU }
                .ifEmpty { listOf(LocalRuntimeBackendPolicy.CPU) }
        }
        if (launchKind == NativeLaunchKind.AUTOMATIC) {
            candidates = candidates.filterNot { backend ->
                val backendId = if (backend == LocalRuntimeBackendPolicy.GPU) "gpu" else "cpu-${effectiveSettings.threads}"
                LocalModelNativeCrashGuard.automaticStartBlocked(
                    descriptor.record,
                    NativeModelEngine.MNN,
                    backendId,
                )
            }
            check(candidates.isNotEmpty()) {
                "MNN 模型所有候选后端都发生过连续原生崩溃；已停止自动重试，请手动启动、缩短上下文或更换模型"
            }
        }
        var lastFailure: Throwable? = null
        val loadStartedAt = SystemClock.elapsedRealtime()
        for (candidate in candidates) {
            val backendId = if (candidate == LocalRuntimeBackendPolicy.GPU) "gpu" else "cpu-${effectiveSettings.threads}"
            val attempt = LocalModelNativeCrashGuard.beginAttempt(
                record = descriptor.record,
                engine = NativeModelEngine.MNN,
                backend = backendId,
                phase = "native-create",
                launchKind = launchKind,
            )
            var handle = 0L
            try {
                handle = MnnNative.nativeCreate()
                check(handle != 0L) { "无法创建 MNN 引擎实例" }
                val gpu = candidate == LocalRuntimeBackendPolicy.GPU
                if (gpu && !MnnNative.nativeOpenClProbe().isNullOrBlank()) {
                    throw IllegalStateException("设备 OpenCL 不可用，准备回退 CPU")
                }
                val cache = context.filesDir.resolve(
                    "local_models/mnn-cache/${descriptor.record.sha256.take(24)}/$backendId",
                ).apply { mkdirs() }
                LocalModelNativeCrashGuard.phase(attempt, "native-load")
                val backend = if (gpu) "opencl|normal|high" else "cpu|normal|kleidi=0"
                val nativeThreads = if (gpu) 4 else effectiveSettings.threads
                val loadError = MnnNative.nativeLoad(
                    handle,
                    descriptor.directory.resolve("config.json").absolutePath.toByteArray(Charsets.UTF_8),
                    backend,
                    nativeThreads,
                    effectiveSettings.contextWindow,
                    cache.absolutePath.toByteArray(Charsets.UTF_8),
                    true,
                    IntArray(0),
                    0,
                    false,
                )
                if (!loadError.isNullOrBlank()) throw IllegalStateException(loadError)
                val loaded = ActiveRuntime(
                    recordId = recordId,
                    handle = handle,
                    settings = effectiveSettings,
                    requestedSettings = requestedSettings,
                    backendId = backendId,
                    backendName = if (gpu) "GPU · OpenCL · MNN" else "CPU · ${effectiveSettings.threads} 线程 · MNN",
                    loadedAt = System.currentTimeMillis(),
                    loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAt,
                )
                warmLoadedRuntime(loaded, attempt)
                active = loaded
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = recordId,
                        state = LocalModelRuntimeState.READY,
                        backend = loaded.backendName,
                        message = "已就绪",
                        loadedAt = loaded.loadedAt,
                    ),
                )
                scheduleIdleUnload(recordId, loaded)
                return loaded
            } catch (failure: Throwable) {
                LocalModelNativeCrashGuard.failed(attempt)
                if (handle != 0L) runCatching { MnnNative.nativeUnload(handle) }
                if (handle != 0L) runCatching { MnnNative.nativeDestroy(handle) }
                lastFailure = failure
            }
        }
        fail(
            recordId,
            "MNN 模型加载失败：${lastFailure?.message ?: lastFailure?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private suspend fun warmLoadedRuntime(runtime: ActiveRuntime, attempt: NativeAttemptLease) {
        val produced = AtomicBoolean(false)
        LocalModelNativeCrashGuard.phase(attempt, "warmup-generate")
        try {
            withTimeout(WARM_UP_TIMEOUT_MS) {
                MnnNative.nativeClearCancel(runtime.handle)
                val result = JSONObject(
                    MnnNative.nativeGenerate(
                        runtime.handle,
                        arrayOf("user".toByteArray(Charsets.UTF_8)),
                        arrayOf("你好".toByteArray(Charsets.UTF_8)),
                        floatArrayOf(0f, 1f, 0f, 1f),
                        intArrayOf(1, 1, 1, 0, 0),
                        IntArray(0),
                        MnnSink { bytes ->
                            if (bytes.isNotEmpty()) {
                                produced.set(true)
                                attempt.firstTokenReceived()
                            }
                            true
                        },
                    ),
                )
                val error = result.optString("error").trim()
                if (error.isNotEmpty()) throw IllegalStateException("MNN 预热失败：$error")
                if (result.optInt("stop", 4) in setOf(2, 4)) {
                    throw IllegalStateException("MNN 预热未完成")
                }
                check(produced.get() || result.optInt("generatedTokens", 0) > 0) {
                    "MNN 预热没有产生 token"
                }
            }
            attempt.completed()
        } finally {
            runCatching { MnnNative.nativeClearCancel(runtime.handle) }
            runCatching { MnnNative.nativeReset(runtime.handle) }
        }
    }

    private fun checkResources(recordId: String) {
        val context = requireNotNull(appContext)
        val descriptor = LocalModelRepository.mnnDescriptor(recordId) ?: return
        val performance = LocalModelPerformanceMonitor.capture(context)
        enforcePolicies(recordId, descriptor.settings, performance.powerSaveMode, performance.thermalStatus)
    }

    private fun enforcePolicies(
        recordId: String,
        settings: LocalModelRuntimeSettings,
        powerSaveMode: Boolean,
        thermalStatus: Int,
    ) {
        if (settings.powerPolicy == LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER && powerSaveMode) {
            fail(recordId, "省电模式已启用；可允许 MNN 在省电模式运行")
        }
        if (settings.thermalPolicy == LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE &&
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        ) {
            fail(recordId, "设备温度过高，已暂停 MNN 推理")
        }
    }

    private fun scheduleIdleUnload(recordId: String, runtime: ActiveRuntime) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(runtime.settings.idleUnloadMinutes.coerceIn(1, 120) * 60_000L)
            idleJob = null
            stop(recordId)
        }
    }

    fun requestMemoryRelief(critical: Boolean) {
        val recordId = active?.recordId ?: return
        if (!critical && idleJob == null) return
        scope.launch {
            mutex.withLock {
                val current = active ?: return@withLock
                if (current.recordId != recordId) return@withLock
                if (!critical && idleJob == null) return@withLock
                idleJob?.cancelAndJoin()
                idleJob = null
                active = null
                closeRuntime(current)
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = current.recordId,
                        state = LocalModelRuntimeState.STOPPED,
                        message = "系统内存不足，已释放 MNN 本地模型",
                    ),
                )
            }
        }
    }

    private fun nativeCancel(runtime: ActiveRuntime) {
        if (runtime.handle != 0L) runCatching { MnnNative.nativeCancel(runtime.handle) }
    }

    private fun closeRuntime(runtime: ActiveRuntime) {
        if (runtime.handle != 0L) runCatching { MnnNative.nativeUnload(runtime.handle) }
        if (runtime.handle != 0L) runCatching { MnnNative.nativeDestroy(runtime.handle) }
        runtime.handle = 0L
        runtime.loaded = false
    }

    private fun fail(recordId: String, message: String): Nothing {
        LocalModelRepository.publishRuntimeSnapshot(
            LocalModelRuntimeSnapshot(
                recordId = recordId,
                state = LocalModelRuntimeState.FAILED,
                message = message,
            ),
        )
        throw IllegalStateException(message)
    }

    private fun parseTurns(messages: JSONArray, fallbackSystem: String): List<MnnTurn> {
        val explicit = buildList {
            for (index in 0 until messages.length()) {
                val row = messages.optJSONObject(index) ?: continue
                val role = row.optString("role").lowercase().ifBlank { "user" }
                val text = contentText(row.opt("content")).trim()
                if (text.isNotEmpty()) add(MnnTurn(role, text))
            }
        }
        if (explicit.any { it.role == "system" } || fallbackSystem.isBlank()) return explicit
        return listOf(MnnTurn("system", fallbackSystem)) + explicit
    }

    private fun contentText(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> buildString {
            for (index in 0 until raw.length()) {
                val part = raw.optJSONObject(index) ?: continue
                if (part.optString("type") in setOf("text", "input_text", "output_text")) {
                    append(part.optString("text"))
                }
            }
        }
        is JSONObject -> raw.optString("text")
        else -> ""
    }

    private data class MnnTurn(val role: String, val text: String)

    private data class ActiveRuntime(
        val recordId: String,
        var handle: Long,
        /** Settings constrained by the native allocation; used for the active prompt/generation. */
        var settings: LocalModelRuntimeSettings,
        /** Last validated user request, used to decide whether a native reload is necessary. */
        var requestedSettings: LocalModelRuntimeSettings,
        val backendId: String,
        val backendName: String,
        val loadedAt: Long,
        val loadDurationMs: Long,
        var loaded: Boolean = true,
    )

    private const val WARM_UP_TIMEOUT_MS = 120_000L
}

private fun Double.formatRate(): String = String.format(Locale.US, "%.1f", this)
