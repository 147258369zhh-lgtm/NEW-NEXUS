package io.github.mangi.eta.agent.model

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.iris.localai.engine.llama.LlamaNative
import com.iris.localai.engine.llama.LoadProgressListener
import com.iris.localai.engine.llama.PieceSink
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
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
import kotlin.math.roundToInt
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide owner of the bundled llama.cpp runtime for GGUF files.
 *
 * The native runtime retains the common token prefix between replies. Eta therefore keeps the
 * loaded handle alive until an explicit stop, an idle timeout, or a load-affecting setting change.
 */
internal object LlamaCppRuntimeManager {
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
            val runtime = acquire(
                recordId = recordId,
                forceReload = false,
                launchKind = NativeLaunchKind.USER,
            )
            val parsedTurns = parseTurns(messages, systemPrompt)
            require(parsedTurns.any { it.role == "user" }) { "本地模型请求中没有用户消息" }
            val fitted = LocalRuntimePromptBudget.fit(
                turns = parsedTurns,
                contextWindow = runtime.settings.contextWindow,
                maxOutputTokens = runtime.settings.generationOutputBudget(),
                roleOf = LlamaTurn::role,
                textOf = LlamaTurn::text,
                engine = "llama.cpp",
                recordId = recordId,
            )
            val turns = fitted.turns
            val roles = turns.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val contents = turns.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val settings = runtime.settings
            // Every user-visible sampling/output/thinking setting is resolved from the current
            // record for each request. Seed -1, KV shift and retained prefix are fixed correctness
            // safeguards rather than decorative UI options.
            val generationArguments = settings.llamaCppGenerationArguments()
            val floats = generationArguments.floats
            val ints = generationArguments.ints
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = recordId,
                    state = LocalModelRuntimeState.GENERATING,
                    backend = runtime.backendName,
                    message = "正在生成",
                    loadedAt = runtime.loadedAt,
                ),
            )
            val binding = runController.register {
                nativeCancel(runtime)
            }
            val rendered = StringBuilder()
            val generationStartedAt = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            try {
                LocalRuntimePromptBudget.started(
                    engine = "llama.cpp",
                    recordId = recordId,
                    messages = turns.size,
                    inputChars = turns.sumOf { it.text.length },
                    contextWindow = settings.contextWindow,
                    maxOutputTokens = settings.generationOutputBudget(),
                    thinking = settings.thinkingEnabled,
                )
                var firstFailure: Throwable? = null
                val result = try {
                    generate(runtime, roles, contents, floats, ints, runController) { delta ->
                        if (firstTokenAt == null) {
                            firstTokenAt = SystemClock.elapsedRealtime()
                            LocalRuntimePromptBudget.firstToken(
                                "llama.cpp", recordId, firstTokenAt!! - generationStartedAt,
                            )
                        }
                        rendered.append(delta)
                        onDelta(delta)
                    }
                } catch (failure: Throwable) {
                    if (failure is AgentRunCancelledException) throw failure
                    firstFailure = failure
                    null
                }
                val finalResult = if (result == null && rendered.isEmpty() && firstFailure != null &&
                    runtime.backendPolicy == LocalRuntimeBackendPolicy.AUTO && runtime.backendName.startsWith("GPU")
                ) {
                    // A driver can accept engine initialization and then fail on the first GPU graph.
                    // Retry once on CPU only when no token has been emitted, avoiding duplicated output.
                    active = null
                    closeRuntime(runtime)
                    val cpuSettings = runtime.requestedSettings.copy(backend = LocalRuntimeBackendPolicy.CPU)
                    val cpuRuntime = acquireWithSettings(
                        recordId = recordId,
                        overrideSettings = cpuSettings,
                        forceReload = true,
                        launchKind = NativeLaunchKind.USER,
                    )
                    generate(cpuRuntime, roles, contents, floats, ints, runController) { delta ->
                        if (firstTokenAt == null) {
                            firstTokenAt = SystemClock.elapsedRealtime()
                            LocalRuntimePromptBudget.firstToken(
                                "llama.cpp", recordId, firstTokenAt!! - generationStartedAt,
                            )
                        }
                        rendered.append(delta)
                        onDelta(delta)
                    }
                } else {
                    result ?: throw (firstFailure ?: IllegalStateException("GGUF 推理失败"))
                }
                runController.throwIfCancelled()
                val visible = LocalRuntimePromptBudget.visibleOutput(rendered.toString())
                if (visible.isEmpty()) {
                    throw AgentModelFailure(
                        code = "EMPTY_MODEL_RESPONSE",
                        retryable = false,
                        message = "GGUF 模型只返回了空代码块或空白内容，请重试或更换模型。",
                    )
                }
                val outputTokens = finalResult.optInt("generatedTokens", 0).takeIf { it > 0 }
                val generateUs = finalResult.optLong("generateUs", 0L)
                val rate = outputTokens?.takeIf { generateUs > 0 }?.let {
                    it.toDouble() * 1_000_000.0 / generateUs
                }
                LocalRuntimePromptBudget.finished(
                    engine = "llama.cpp",
                    recordId = recordId,
                    outputChars = visible.length,
                    tokens = outputTokens,
                    elapsedMs = SystemClock.elapsedRealtime() - generationStartedAt,
                    stop = finalResult.optInt("stop", 1).toString(),
                )
                scheduleIdleUnload(recordId, active ?: runtime)
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = recordId,
                        state = LocalModelRuntimeState.READY,
                        backend = (active ?: runtime).backendName,
                        message = rate?.let { "已就绪 · ${it.formatRate()} tok/s" } ?: "已就绪",
                        loadedAt = (active ?: runtime).loadedAt,
                        lastTokenCount = outputTokens,
                    ),
                )
                val finishReason = if (
                    outputTokens != null && outputTokens >= settings.generationOutputBudget()
                ) "length" else "stop"
                val activeRuntime = active ?: runtime
                val promptTokens = finalResult.optInt("promptTokens", -1).takeIf { it >= 0 }
                val reusedTokens = finalResult.optInt("reusedTokens", -1).takeIf { it >= 0 }
                val kvUsed = finalResult.optInt("kvUsed", -1).takeIf { it >= 0 }
                val prefillUs = finalResult.optLong("prefillUs", -1L).takeIf { it >= 0L }
                val completedAt = System.currentTimeMillis()
                val totalMs = SystemClock.elapsedRealtime() - generationStartedAt
                LocalRuntimeCompletion(
                    visible,
                    outputTokens,
                    contextTokenCount = kvUsed,
                    finishReason = finishReason,
                    metrics = LocalRuntimeMetrics(
                        backend = if (activeRuntime.backendPolicy == LocalRuntimeBackendPolicy.GPU) {
                            LocalRuntimeBackend.GPU
                        } else {
                            LocalRuntimeBackend.CPU
                        },
                        modelFormat = "GGUF",
                        modelId = recordId,
                        backendDetail = activeRuntime.backendName,
                        loadMs = activeRuntime.loadDurationMs,
                        firstTokenMs = firstTokenAt?.let { it - generationStartedAt },
                        promptTokens = promptTokens,
                        generatedTokens = outputTokens,
                        tokensPerSecond = rate,
                        promptCacheHitTokens = reusedTokens,
                        promptCacheReusedTokens = reusedTokens,
                        kvCacheUsedTokens = kvUsed,
                        kvCacheCapacityTokens = activeRuntime.settings.contextWindow,
                        contextWindow = activeRuntime.settings.contextWindow,
                        prefillMs = prefillUs?.div(1_000L),
                        decodeMs = generateUs.takeIf { it > 0 }?.div(1_000L),
                        totalMs = totalMs,
                        finishReason = finishReason,
                        startedAt = completedAt - totalMs,
                        completedAt = completedAt,
                    ),
                )
            } catch (failure: Throwable) {
                val nativeFailure = failure as? LlamaNativeGenerationFailure
                val cause = nativeFailure?.cause ?: failure
                val activeRuntime = active ?: runtime
                val failureMetrics = LocalRuntimeFailureTelemetry.metrics(
                    format = "GGUF",
                    modelId = recordId,
                    failure = cause,
                    backend = if (activeRuntime.backendPolicy == LocalRuntimeBackendPolicy.GPU) {
                        LocalRuntimeBackend.GPU
                    } else {
                        LocalRuntimeBackend.CPU
                    },
                    backendDetail = activeRuntime.backendName,
                    loadMs = activeRuntime.loadDurationMs,
                    firstTokenMs = firstTokenAt?.let { it - generationStartedAt },
                    stats = nativeFailure?.stats,
                    contextWindow = activeRuntime.settings.contextWindow,
                    totalMs = SystemClock.elapsedRealtime() - generationStartedAt,
                )
                throw LocalRuntimeFailureTelemetry.attach(cause, failureMetrics)
            } finally {
                binding.close()
            }
        }
    }

    private suspend fun acquire(
        recordId: String,
        forceReload: Boolean,
        launchKind: NativeLaunchKind,
    ): ActiveRuntime =
        acquireWithSettings(
            recordId = recordId,
            overrideSettings = null,
            forceReload = forceReload,
            launchKind = launchKind,
        )

    private suspend fun acquireWithSettings(
        recordId: String,
        overrideSettings: LocalModelRuntimeSettings?,
        forceReload: Boolean,
        launchKind: NativeLaunchKind,
    ): ActiveRuntime {
        val context = requireNotNull(appContext) { "本地模型运行时尚未初始化" }
        val descriptor = requireNotNull(LocalModelRepository.ggufDescriptor(recordId)) {
            "本地模型记录不存在或不是 GGUF 文件"
        }
        val requestedSettings = (overrideSettings ?: descriptor.settings).validated()
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
        LlamaNative.unavailableReason?.let { fail(recordId, it) }
        require(descriptor.file.isFile) { "模型文件已丢失，请重新导入或下载" }
        LocalModelRepository.publishRuntimeSnapshot(
            LocalModelRuntimeSnapshot(
                recordId = recordId,
                state = LocalModelRuntimeState.STARTING,
                message = "正在加载 GGUF 模型",
            ),
        )
        val performance = LocalModelPerformanceMonitor.capture(context)
        enforcePolicies(recordId, requestedSettings, performance.powerSaveMode, performance.thermalStatus)
        val effectiveSettings = LocalModelNativeCrashGuard.memoryPlan(
            context = context,
            record = descriptor.record,
            settings = requestedSettings,
            engine = NativeModelEngine.LLAMA_CPP,
        ).settings

        var candidates = when (effectiveSettings.backend) {
            LocalRuntimeBackendPolicy.NPU -> fail(
                recordId,
                "GGUF 运行时不支持 Qualcomm NPU；请选择 CPU 或 GPU，或改用芯片专用的 LiteRT-LM NPU 模型",
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
        if (LocalModelNativeCrashGuard.gpuRequiresCpuFallback(descriptor.record, NativeModelEngine.LLAMA_CPP)) {
            candidates = candidates.filterNot { it == LocalRuntimeBackendPolicy.GPU }
                .ifEmpty { listOf(LocalRuntimeBackendPolicy.CPU) }
        }
        if (launchKind == NativeLaunchKind.AUTOMATIC) {
            candidates = candidates.filterNot { backend ->
                val backendId = if (backend == LocalRuntimeBackendPolicy.GPU) "gpu" else "cpu-${effectiveSettings.threads}"
                LocalModelNativeCrashGuard.automaticStartBlocked(
                    record = descriptor.record,
                    engine = NativeModelEngine.LLAMA_CPP,
                    backend = backendId,
                )
            }
            check(candidates.isNotEmpty()) {
                "此模型的所有候选后端都发生过连续原生崩溃；已停止自动重试，请手动启动、缩短上下文或更换模型"
            }
        }
        var lastFailure: Throwable? = null
        val loadStartedAt = SystemClock.elapsedRealtime()
        for (candidate in candidates) {
            val backendId = if (candidate == LocalRuntimeBackendPolicy.GPU) "gpu" else "cpu-${effectiveSettings.threads}"
            val attempt = LocalModelNativeCrashGuard.beginAttempt(
                record = descriptor.record,
                engine = NativeModelEngine.LLAMA_CPP,
                backend = backendId,
                phase = "native-create",
                launchKind = launchKind,
            )
            var handle = 0L
            try {
                handle = LlamaNative.nativeCreate()
                check(handle != 0L) { "无法创建 GGUF 引擎实例" }
                val gpu = candidate == LocalRuntimeBackendPolicy.GPU
                var lastProgress = -1
                LocalModelNativeCrashGuard.phase(attempt, "native-load")
                val loadError = LlamaNative.nativeLoad(
                    handle = handle,
                    pathUtf8 = descriptor.file.absolutePath.toByteArray(Charsets.UTF_8),
                    nCtx = effectiveSettings.contextWindow,
                    nThreads = effectiveSettings.threads,
                    nBatch = 256,
                    flashAttn = true,
                    nGpuLayers = if (gpu) GPU_ALL_LAYERS else 0,
                    kvType = if (effectiveSettings.contextWindow >= 8_192) 1 else 0,
                    useMmap = true,
                    listener = LoadProgressListener { rawProgress ->
                        val percent = (rawProgress.coerceIn(0f, 1f) * 100f).roundToInt()
                        // Native loading can report very densely. Publish only visible 5% steps so
                        // Compose does not recompose faster than a person can read the state.
                        if (percent >= lastProgress + 5 || percent == 100) {
                            lastProgress = percent
                            LocalModelRepository.publishRuntimeSnapshot(
                                LocalModelRuntimeSnapshot(
                                    recordId = recordId,
                                    state = LocalModelRuntimeState.STARTING,
                                    message = "正在加载 GGUF 模型 · $percent%",
                                ),
                            )
                        }
                        true
                    },
                )
                if (!loadError.isNullOrBlank()) throw IllegalStateException(loadError)
                val meta = runCatching { JSONObject(LlamaNative.nativeMeta(handle)) }.getOrDefault(JSONObject())
                val gpuName = meta.optString("gpu").trim()
                if (gpu && gpuName.isBlank()) {
                    throw IllegalStateException("设备没有可用的 GPU 推理后端")
                }
                val backendName = if (gpu) {
                    "GPU · ${gpuName.ifBlank { "OpenCL" }}"
                } else {
                    "CPU · ${effectiveSettings.threads} 线程"
                }
                val loaded = ActiveRuntime(
                    recordId = recordId,
                    handle = handle,
                    settings = effectiveSettings,
                    requestedSettings = requestedSettings,
                    backendId = backendId,
                    backendPolicy = candidate,
                    backendName = backendName,
                    loadedAt = System.currentTimeMillis(),
                    loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAt,
                )
                // The first graph build is materially slower than later requests. Generate one
                // greedy token and discard it, matching the proven llama.cpp warm-up strategy.
                warmLoadedRuntime(loaded, attempt)
                active = loaded
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = recordId,
                        state = LocalModelRuntimeState.READY,
                        backend = backendName,
                        message = "已就绪",
                        loadedAt = loaded.loadedAt,
                    ),
                )
                scheduleIdleUnload(recordId, loaded)
                return loaded
            } catch (failure: Throwable) {
                LocalModelNativeCrashGuard.failed(attempt)
                runCatching { LlamaNative.nativeUnload(handle) }
                runCatching { LlamaNative.nativeDestroy(handle) }
                lastFailure = failure
            }
        }
        fail(
            recordId,
            "GGUF 模型加载失败：${lastFailure?.message ?: lastFailure?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private fun generate(
        runtime: ActiveRuntime,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        floats: FloatArray,
        ints: IntArray,
        runController: AgentRunController,
        onDelta: (String) -> Unit,
    ): JSONObject {
        require(runtime.handle != 0L && runtime.loaded) { "GGUF 引擎尚未加载" }
        LlamaNative.nativeClearCancel(runtime.handle)
        if (runController.isCancelled) throw AgentRunCancelledException()
        val record = requireNotNull(LocalModelRepository.ggufDescriptor(runtime.recordId)).record
        val attempt = LocalModelNativeCrashGuard.beginAttempt(
            record = record,
            engine = NativeModelEngine.LLAMA_CPP,
            backend = runtime.backendId,
            phase = "generation",
            launchKind = NativeLaunchKind.USER,
        )
        val sink = PieceSink { bytes ->
            if (runController.isCancelled) return@PieceSink false
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isNotEmpty()) {
                attempt.firstTokenReceived()
                onDelta(text)
            }
            true
        }
        var nativeStats: JSONObject? = null
        try {
            val result = JSONObject(
                LlamaNative.nativeGenerate(runtime.handle, roles, contents, floats, ints, sink),
            ).also { nativeStats = it }
            val stop = result.optInt("stop", 3)
            val error = result.optString("error").trim()
            if (runController.isCancelled || stop == 2) throw AgentRunCancelledException()
            if (error.isNotEmpty()) throw IllegalStateException(error)
            if (stop == 3) {
                throw IllegalStateException("GGUF 上下文已满，请缩短对话、压缩上下文或调大模型上下文长度")
            }
            if (stop == 4) throw IllegalStateException(error.ifBlank { "GGUF 引擎执行失败" })
            attempt.completed()
            return result
        } catch (failure: Throwable) {
            LocalModelNativeCrashGuard.failed(attempt)
            throw LlamaNativeGenerationFailure(
                stats = nativeStats,
                cause = LocalRuntimePromptBudget.failure("llama.cpp", runtime.recordId, failure),
            )
        }
    }

    /**
     * Performs one greedy token, then resets the conversation slot. The native call is blocking,
     * so a watchdog cancels it from a separate dispatcher when initialisation or the first graph
     * stalls. A failed warm-up is not allowed to destroy an otherwise usable model.
     */
    private fun warmLoadedRuntime(runtime: ActiveRuntime, attempt: NativeAttemptLease) {
        val timedOut = AtomicBoolean(false)
        val produced = AtomicBoolean(false)
        var healthy = false
        val watchdog = scope.launch {
            delay(WARM_UP_TIMEOUT_MS)
            timedOut.set(true)
            nativeCancel(runtime)
        }
        try {
            LlamaNative.nativeClearCancel(runtime.handle)
            LocalModelNativeCrashGuard.phase(attempt, "warmup-generate")
            val result = JSONObject(
                LlamaNative.nativeGenerate(
                    runtime.handle,
                    arrayOf("user".toByteArray(Charsets.UTF_8)),
                    arrayOf("你好".toByteArray(Charsets.UTF_8)),
                    floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f),
                    intArrayOf(1, 64, 1, -1, 1, 1, 0, 4),
                    PieceSink { bytes ->
                        if (bytes.isNotEmpty()) {
                            produced.set(true)
                            attempt.firstTokenReceived()
                        }
                        true
                    },
                ),
            )
            val error = result.optString("error").trim()
            if (error.isNotEmpty()) throw IllegalStateException("GGUF 预热失败：$error")
            if (timedOut.get() || result.optInt("stop", 3) == 2) {
                throw IllegalStateException("GGUF 预热超时")
            }
            check(produced.get()) { "GGUF 预热没有产生 token" }
            healthy = true
        } catch (failure: Throwable) {
            LocalModelNativeCrashGuard.failed(attempt)
            throw failure
        } finally {
            watchdog.cancel()
            runCatching { LlamaNative.nativeClearCancel(runtime.handle) }
            runCatching { LlamaNative.nativeReset(runtime.handle) }
        }
        if (healthy) attempt.completed()
    }

    private fun nativeCancel(runtime: ActiveRuntime) {
        if (runtime.handle != 0L) runCatching { LlamaNative.nativeCancel(runtime.handle) }
    }

    private fun checkResources(recordId: String) {
        val context = requireNotNull(appContext)
        val descriptor = LocalModelRepository.ggufDescriptor(recordId) ?: return
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
            fail(recordId, "省电模式已启用；可在本地模型运行设置中允许省电模式执行")
        }
        if (settings.thermalPolicy == LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE &&
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        ) {
            fail(recordId, "设备温度过高，已暂停本地推理")
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

    /** Releases an idle GGUF model under memory pressure; an active turn completes first. */
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
                        message = "系统内存不足，已释放本地模型",
                    ),
                )
            }
        }
    }

    private fun closeRuntime(runtime: ActiveRuntime) {
        if (runtime.handle != 0L) runCatching { LlamaNative.nativeUnload(runtime.handle) }
        if (runtime.handle != 0L) runCatching { LlamaNative.nativeDestroy(runtime.handle) }
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

    private fun parseTurns(messages: JSONArray, fallbackSystem: String): List<LlamaTurn> {
        val explicit = buildList {
            for (index in 0 until messages.length()) {
                val row = messages.optJSONObject(index) ?: continue
                val role = row.optString("role").lowercase().ifBlank { "user" }
                val text = contentText(row.opt("content")).trim()
                if (text.isNotEmpty()) add(LlamaTurn(role, text))
            }
        }
        if (explicit.any { it.role == "system" } || fallbackSystem.isBlank()) return explicit
        return listOf(LlamaTurn("system", fallbackSystem)) + explicit
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

    private data class LlamaTurn(val role: String, val text: String)
    private class LlamaNativeGenerationFailure(
        val stats: JSONObject?,
        override val cause: Throwable,
    ) : RuntimeException(cause)

    private data class ActiveRuntime(
        val recordId: String,
        var handle: Long,
        /** Settings constrained by the native allocation; used for the active prompt/generation. */
        var settings: LocalModelRuntimeSettings,
        /** Last validated user request, used to decide whether a native reload is necessary. */
        var requestedSettings: LocalModelRuntimeSettings,
        val backendId: String,
        val backendPolicy: LocalRuntimeBackendPolicy,
        val backendName: String,
        val loadedAt: Long,
        val loadDurationMs: Long,
        var loaded: Boolean = true,
    )

    private const val GPU_ALL_LAYERS = 999
    private const val WARM_UP_TIMEOUT_MS = 60_000L
}

private fun Double.formatRate(): String = String.format(Locale.US, "%.1f", this)
