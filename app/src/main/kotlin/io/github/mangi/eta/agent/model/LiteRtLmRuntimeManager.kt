package io.github.mangi.eta.agent.model

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.mangi.eta.data.repository.LocalModelPerformanceMonitor
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import io.github.mangi.eta.data.repository.LocalModelRuntimeSnapshot
import io.github.mangi.eta.data.repository.LocalModelRuntimeState
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import io.github.mangi.eta.data.repository.LocalRuntimePowerPolicy
import io.github.mangi.eta.data.repository.LocalRuntimeThermalPolicy
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.LocalRuntimeBackend
import io.github.mangi.eta.agent.runtime.LocalRuntimeMetrics
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class LiteRtLmTurn(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
)

/**
 * Process-wide owner of the bundled LiteRT-LM engine. Only one large model is resident at a
 * time; conversations are short-lived snapshots of the request history, while the expensive
 * engine remains loaded until the idle timer or the user stops it.
 */
internal object LiteRtLmRuntimeManager {
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
        QualcommNpuRuntimeManager.initialize(context.applicationContext)
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
            runCatching { current.close() }
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = current.recordId,
                    state = LocalModelRuntimeState.STOPPED,
                ),
            )
        }
    }

    /**
     * Runs one chat completion. The first call may take several seconds while the model is mapped
     * and initialized; callers receive pump events as soon as LiteRT-LM starts producing text.
     */
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
            val outputBudget = runtime.settings.liteRtOutputBudget()
            val rawLastUserIndex = parsedTurns.indexOfLast { it.role == "user" }
            val rawHistory = parsedTurns.take(rawLastUserIndex)
            val rawPrompt = parsedTurns[rawLastUserIndex]
            val reusable = runtime.conversation?.takeIf { conversation ->
                conversation.isAlive && continuesRuntimeHistory(rawHistory, runtime.history) &&
                    runCatching {
                        val used = conversation.getTokenCount()
                        val currentInput = LocalRuntimePromptBudget.estimateInputTokens(
                            listOf(rawPrompt),
                            LiteRtLmTurn::role,
                            LiteRtLmTurn::text,
                        ) + rawPrompt.images.size * VISION_INPUT_RESERVE_TOKENS
                        used > 0 && used + currentInput + outputBudget + CONTEXT_SAFETY_TOKENS <=
                            runtime.settings.contextWindow
                    }.getOrDefault(false)
            }
            val turns = if (reusable != null) {
                parsedTurns
            } else {
                LocalRuntimePromptBudget.fit(
                    turns = parsedTurns,
                    contextWindow = runtime.settings.contextWindow,
                    maxOutputTokens = outputBudget,
                    additionalInputTokens = parsedTurns.sumOf {
                        it.images.size * VISION_INPUT_RESERVE_TOKENS
                    },
                    roleOf = LiteRtLmTurn::role,
                    textOf = LiteRtLmTurn::text,
                    engine = "LiteRT-LM",
                    recordId = recordId,
                ).turns
            }
            require(turns.any { it.role == "user" }) { "本地模型请求中没有用户消息" }
            val lastUserIndex = turns.indexOfLast { it.role == "user" }
            val initial = turns.take(lastUserIndex).mapNotNull { turn ->
                when (turn.role) {
                    "user" -> turn.toUserMessage()
                    "assistant" -> Message.model(Contents.of(turn.text))
                    else -> null
                }
            }
            val prompt = turns[lastUserIndex].text.trim()
            val promptImages = turns[lastUserIndex].images
            require(prompt.isNotEmpty() || promptImages.isNotEmpty()) { "本地模型请求内容为空" }
            val record = requireNotNull(LocalModelRepository.builtInDescriptor(recordId)).record
            if (promptImages.isNotEmpty()) {
                if (!record.supportsVision || !runtime.settings.imageInputEnabled) {
                    throw AgentModelFailure(
                        code = "LOCAL_VISION_DISABLED",
                        retryable = false,
                        message = "当前本地模型未启用图片输入，请在本地模型参数中开启视觉能力。",
                    )
                }
            }
            val systemInstruction = turns
                .asSequence()
                .filter { it.role == "system" }
                .map { it.text.trim() }
                .filter(String::isNotEmpty)
                .joinToString("\n\n")
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = recordId,
                    state = LocalModelRuntimeState.GENERATING,
                    backend = runtime.backendName,
                    message = "正在生成",
                    loadedAt = runtime.loadedAt,
                ),
            )
            val requestedHistory = turns.take(lastUserIndex)
            val turnLease = LocalModelNativeCrashGuard.beginAttempt(
                record = record,
                engine = NativeModelEngine.LITERT_LM,
                backend = runtime.backendId,
                phase = "conversation-create",
                launchKind = NativeLaunchKind.USER,
            )
            val conversation = try {
                reusable ?: createConversation(runtime, systemInstruction, initial).also { replacement ->
                    runCatching { runtime.conversation?.close() }
                    runtime.conversation = replacement
                    runtime.history = emptyList()
                }
            } catch (failure: Throwable) {
                LocalModelNativeCrashGuard.failed(turnLease)
                throw failure
            }
            val cancelBinding = runController.register {
                runCatching { conversation.cancelProcess() }
            }
            var rendered = ""
            val generationStartedAt = SystemClock.elapsedRealtime()
            var firstTokenAt: Long? = null
            try {
                LocalModelNativeCrashGuard.phase(turnLease, "generation")
                LocalRuntimePromptBudget.started(
                    engine = "LiteRT-LM",
                    recordId = recordId,
                    messages = turns.size,
                    inputChars = turns.sumOf { it.text.length },
                    contextWindow = runtime.settings.contextWindow,
                    maxOutputTokens = runtime.settings.liteRtOutputBudget(),
                    thinking = runtime.settings.thinkingEnabled,
                )
                val thinking = if (runtime.settings.thinkingEnabled) {
                    ThinkingConfig(true, THINKING_BUDGET_TOKENS)
                } else {
                    ThinkingConfig(false)
                }
                val stream = if (runtime.backendId == "npu") {
                    // The AOT artifact contains its own graph. Qualcomm's dispatch can reject a
                    // second sampler, so NPU requests use the portable default sampler.
                    conversation.sendMessageAsync(
                        LiteRtLmTurn("user", prompt, promptImages).toUserMessage(),
                        mapOf("enable_thinking" to runtime.settings.thinkingEnabled),
                        maxOutputToken = outputBudget,
                        thinkingConfig = thinking,
                    )
                } else {
                    conversation.sendMessageAsync(
                        LiteRtLmTurn("user", prompt, promptImages).toUserMessage(),
                        mapOf("enable_thinking" to runtime.settings.thinkingEnabled),
                        RepetitionPenaltyConfig(
                            REPETITION_PENALTY,
                            PRESENCE_PENALTY,
                            FREQUENCY_PENALTY,
                            REPETITION_WINDOW,
                        ),
                        NoRepeatNgramConfig(NO_REPEAT_NGRAM_SIZE, NO_REPEAT_NGRAM_WINDOW),
                        maxOutputToken = outputBudget,
                        thinkingConfig = thinking,
                    )
                }
                stream.collect { message ->
                    runController.throwIfCancelled()
                    val snapshot = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (snapshot.isEmpty()) return@collect
                    // LiteRT-LM 0.19.1 emits incremental chunks. Repeated text must remain
                    // visible; treating a chunk as a cumulative snapshot corrupts long replies.
                    val delta = liteRtStreamDelta(rendered, snapshot)
                    if (delta.isNotEmpty()) {
                        if (firstTokenAt == null) {
                            firstTokenAt = SystemClock.elapsedRealtime()
                            LocalRuntimePromptBudget.firstToken(
                                "LiteRT-LM", recordId, firstTokenAt!! - generationStartedAt,
                            )
                        }
                        turnLease.firstTokenReceived()
                        rendered += delta
                        onDelta(delta)
                    }
                }
                runController.throwIfCancelled()
                val visible = LocalRuntimePromptBudget.visibleOutput(rendered)
                if (visible.isEmpty()) {
                    throw AgentModelFailure(
                        code = "EMPTY_MODEL_RESPONSE",
                        retryable = false,
                        message = "本地模型只返回了空代码块或空白内容，请重试或更换模型。",
                    )
                }
                turnLease.completed()
                runtime.history = requestedHistory + listOf(
                    LiteRtLmTurn("user", prompt, promptImages),
                    LiteRtLmTurn("assistant", visible),
                )
                scheduleIdleUnload(recordId, runtime)
                val benchmark = benchmarkInfo(conversation)
                val tokenCount = benchmark?.lastDecodeTokenCount?.takeIf { it > 0 }
                val contextTokenCount = runCatching<Int?> { conversation.getTokenCount() }
                    .getOrNull()?.takeIf { it > 0 }
                LocalRuntimePromptBudget.finished(
                    engine = "LiteRT-LM",
                    recordId = recordId,
                    outputChars = visible.length,
                    tokens = tokenCount,
                    elapsedMs = SystemClock.elapsedRealtime() - generationStartedAt,
                    stop = "eos",
                )
                LocalModelRepository.publishRuntimeSnapshot(
                    LocalModelRuntimeSnapshot(
                        recordId = recordId,
                        state = LocalModelRuntimeState.READY,
                        backend = runtime.backendName,
                        message = benchmark?.let {
                            "已就绪 · ${it.lastDecodeTokensPerSecond.formatRate()} tok/s"
                        } ?: "已就绪",
                        loadedAt = runtime.loadedAt,
                        lastTokenCount = tokenCount,
                    ),
                )
                val finishReason = if (tokenCount != null && tokenCount >= outputBudget) "length" else "stop"
                val promptTokens = benchmark?.lastPrefillTokenCount?.takeIf { it >= 0 }
                val prefillRate = benchmark?.lastPrefillTokensPerSecond?.takeIf { it > 0.0 }
                val decodeRate = benchmark?.lastDecodeTokensPerSecond?.takeIf { it > 0.0 }
                val completedAt = System.currentTimeMillis()
                val totalMs = SystemClock.elapsedRealtime() - generationStartedAt
                LocalRuntimeCompletion(
                    text = visible,
                    tokenCount = tokenCount,
                    contextTokenCount = contextTokenCount,
                    finishReason = finishReason,
                    metrics = LocalRuntimeMetrics(
                        backend = runtime.metricBackend(),
                        modelFormat = "LITERTLM",
                        modelId = recordId,
                        backendDetail = runtime.backendName,
                        loadMs = runtime.loadDurationMs,
                        firstTokenMs = benchmark?.timeToFirstTokenInSecond
                            ?.times(1_000.0)?.toLong()?.takeIf { it >= 0L },
                        promptTokens = promptTokens,
                        generatedTokens = tokenCount,
                        tokensPerSecond = decodeRate,
                        // LiteRT-LM does not expose an exact prompt-cache token count yet.
                        promptCacheHitTokens = null,
                        promptCacheReusedTokens = null,
                        kvCacheUsedTokens = contextTokenCount,
                        kvCacheCapacityTokens = runtime.settings.contextWindow,
                        contextWindow = runtime.settings.contextWindow,
                        prefillMs = promptTokens?.takeIf { it > 0 }?.let { tokens ->
                            prefillRate?.let { (tokens * 1_000.0 / it).toLong() }
                        },
                        decodeMs = tokenCount?.takeIf { it > 0 }?.let { tokens ->
                            decodeRate?.let { (tokens * 1_000.0 / it).toLong() }
                        },
                        totalMs = totalMs,
                        finishReason = finishReason,
                        startedAt = completedAt - totalMs,
                        completedAt = completedAt,
                    ),
                )
            } catch (failure: Throwable) {
                LocalModelNativeCrashGuard.failed(turnLease)
                // The flow wrapper may cancel while the native worker is still finishing. Stop it
                // first and close on the inference dispatcher instead of racing the callback.
                runCatching { conversation.cancelProcess() }
                if (runtime.conversation === conversation) runtime.conversation = null
                runtime.history = emptyList()
                scope.launch {
                    delay(100)
                    runCatching { conversation.close() }
                }
                val mapped = LocalRuntimePromptBudget.failure("LiteRT-LM", recordId, failure)
                val benchmark = benchmarkInfo(conversation)
                val contextTokenCount = runCatching<Int?> { conversation.getTokenCount() }
                    .getOrNull()?.takeIf { it > 0 }
                val promptTokens = benchmark?.lastPrefillTokenCount?.takeIf { it >= 0 }
                val generatedTokens = benchmark?.lastDecodeTokenCount?.takeIf { it >= 0 }
                val prefillRate = benchmark?.lastPrefillTokensPerSecond?.takeIf { it > 0.0 }
                val decodeRate = benchmark?.lastDecodeTokensPerSecond?.takeIf { it > 0.0 }
                val failureStats = JSONObject()
                    .put("promptTokens", promptTokens ?: -1)
                    .put("generatedTokens", generatedTokens ?: -1)
                    .put("kvUsed", contextTokenCount ?: -1)
                    .put("prefillUs", promptTokens?.takeIf { it > 0 }?.let { tokens ->
                        prefillRate?.let { (tokens * 1_000_000.0 / it).toLong() }
                    } ?: -1L)
                    .put("generateUs", generatedTokens?.takeIf { it > 0 }?.let { tokens ->
                        decodeRate?.let { (tokens * 1_000_000.0 / it).toLong() }
                    } ?: -1L)
                val failureMetrics = LocalRuntimeFailureTelemetry.metrics(
                    format = "LITERTLM",
                    modelId = recordId,
                    failure = mapped,
                    backend = runtime.metricBackend(),
                    backendDetail = runtime.backendName,
                    loadMs = runtime.loadDurationMs,
                    firstTokenMs = firstTokenAt?.let { it - generationStartedAt }
                        ?: benchmark?.timeToFirstTokenInSecond?.times(1_000.0)?.toLong(),
                    stats = failureStats,
                    contextWindow = runtime.settings.contextWindow,
                    totalMs = SystemClock.elapsedRealtime() - generationStartedAt,
                )
                throw LocalRuntimeFailureTelemetry.attach(mapped, failureMetrics)
            } finally {
                cancelBinding.close()
            }
        }
    }

    private fun createConversation(
        runtime: ActiveRuntime,
        systemInstruction: String,
        initial: List<Message>,
    ): Conversation {
        fun config(thinkingConfig: ThinkingConfig?) = ConversationConfig(
            systemInstruction = systemInstruction.takeIf(String::isNotBlank)?.let(Contents::of),
            initialMessages = initial,
            samplerConfig = if (runtime.backendId == "npu") null else SamplerConfig(
                topK = runtime.settings.topK,
                topP = runtime.settings.topP.toDouble(),
                temperature = runtime.settings.temperature.toDouble(),
                seed = -1,
            ),
            thinkingConfig = thinkingConfig,
        )

        val requestedThinking = if (runtime.settings.thinkingEnabled) {
            ThinkingConfig(true, THINKING_BUDGET_TOKENS)
        } else {
            ThinkingConfig(false)
        }
        return runCatching<Conversation> {
            runtime.engine.createConversation(config(requestedThinking))
        }.getOrElse { failure ->
            runCatching<Conversation> { runtime.engine.createConversation(config(null)) }
                .getOrElse { fallback ->
                    failure.addSuppressed(fallback)
                    throw failure
                }
        }
    }

    private suspend fun acquire(
        recordId: String,
        forceReload: Boolean,
        launchKind: NativeLaunchKind,
    ): ActiveRuntime {
        val context = requireNotNull(appContext) { "本地模型运行时尚未初始化" }
        val descriptor = requireNotNull(LocalModelRepository.builtInDescriptor(recordId)) {
            "本地模型记录不存在或不是 LiteRT-LM 文件"
        }
        val requestedSettings = descriptor.settings.validated()
        active?.let { current ->
            if (!forceReload && current.recordId == recordId &&
                !current.requestedSettings.requiresNativeEngineReload(requestedSettings) &&
                current.engine.isInitialized()
            ) {
                val effective = requestedSettings.withLoadedNativeConstraints(current.settings)
                if (current.settings != effective) {
                    // Sampling and prompt settings are attached to Conversation, not Engine.
                    runCatching { current.conversation?.close() }
                    current.conversation = null
                    current.history = emptyList()
                }
                current.settings = effective
                current.requestedSettings = requestedSettings
                idleJob?.cancel()
                idleJob = null
                return current
            }
            idleJob?.cancelAndJoin()
            idleJob = null
            active = null
            runCatching { current.close() }
        }
        require(descriptor.file.isFile) { "模型文件已丢失，请重新导入或下载" }
        LocalModelRepository.publishRuntimeSnapshot(
            LocalModelRuntimeSnapshot(
                recordId = recordId,
                state = LocalModelRuntimeState.STARTING,
                message = "正在加载模型",
            ),
        )
        val performance = LocalModelPerformanceMonitor.capture(context)
        if (descriptor.settings.powerPolicy == LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER &&
            performance.powerSaveMode
        ) {
            fail(recordId, "省电模式已启用；可在本地模型运行时中允许省电模式执行")
        }
        if (descriptor.settings.thermalPolicy == LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE &&
            performance.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        ) {
            fail(recordId, "设备温度过高，已暂停本地推理")
        }

        val npuTarget = NpuModelTargeting.targetOf(descriptor.record.fileName)
        val npuModel = npuTarget != null
        if (requestedSettings.backend == LocalRuntimeBackendPolicy.NPU && !npuModel) {
            fail(recordId, "此模型文件不是当前芯片的 NPU 编译版，请使用 CPU 或 GPU")
        }
        if (npuModel && !NpuModelTargeting.matches(npuTarget, Build.SOC_MODEL)) {
            fail(
                recordId,
                "此 NPU 模型面向 ${npuTarget?.uppercase()}，当前设备为 ${Build.SOC_MODEL.ifBlank { "未知芯片" }}，不能加载",
            )
        }
        val memoryPlan = LocalModelNativeCrashGuard.memoryPlan(
            context = context,
            record = descriptor.record,
            settings = if (npuModel) {
                requestedSettings.copy(
                    backend = LocalRuntimeBackendPolicy.NPU,
                    contextWindow = NpuModelTargeting.historyBudget(descriptor.record.fileName),
                )
            } else {
                requestedSettings
            },
            engine = NativeModelEngine.LITERT_LM,
        )
        val effectiveSettings = if (npuModel) {
            // AOT models embed a fixed KV allocation. Supplying a larger Java-side value would
            // look correct in the UI while failing inside QNN at the first prompt.
            memoryPlan.settings.copy(
                backend = LocalRuntimeBackendPolicy.NPU,
                contextWindow = minOf(
                    memoryPlan.settings.contextWindow,
                    NpuModelTargeting.historyBudget(descriptor.record.fileName),
                ),
            )
        } else {
            memoryPlan.settings
        }
        val npuRuntimeDirectory = if (npuModel) {
            LocalModelRepository.publishRuntimeSnapshot(
                LocalModelRuntimeSnapshot(
                    recordId = recordId,
                    state = LocalModelRuntimeState.STARTING,
                    backend = "NPU",
                    message = "正在准备 Qualcomm NPU 运行库",
                ),
            )
            QualcommNpuRuntimeManager.ensureInstalled(context)
        } else {
            null
        }
        var requestedBackend = when (effectiveSettings.backend) {
            LocalRuntimeBackendPolicy.CPU -> listOf(Backend.CPU(effectiveSettings.threads))
            LocalRuntimeBackendPolicy.GPU -> listOf(Backend.GPU(), Backend.CPU(effectiveSettings.threads))
            LocalRuntimeBackendPolicy.NPU -> listOf(
                Backend.NPU(requireNotNull(npuRuntimeDirectory).absolutePath),
            )
            LocalRuntimeBackendPolicy.AUTO -> {
                val preferCpu = effectiveSettings.thermalPolicy == LocalRuntimeThermalPolicy.STABILITY_FIRST ||
                    performance.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE ||
                    performance.powerSaveMode
                if (preferCpu) {
                    listOf(Backend.CPU(effectiveSettings.threads), Backend.GPU())
                } else {
                    listOf(Backend.GPU(), Backend.CPU(effectiveSettings.threads))
                }
            }
        }
        if (LocalModelNativeCrashGuard.gpuRequiresCpuFallback(descriptor.record, NativeModelEngine.LITERT_LM)) {
            requestedBackend = requestedBackend
                .filterNot { it is Backend.GPU }
                .ifEmpty { listOf(Backend.CPU(effectiveSettings.threads)) }
        }
        if (launchKind == NativeLaunchKind.AUTOMATIC) {
            requestedBackend = requestedBackend.filterNot { backend ->
                val backendId = when (backend) {
                    is Backend.NPU -> "npu"
                    is Backend.GPU -> "gpu"
                    else -> "cpu-${effectiveSettings.threads}"
                }
                LocalModelNativeCrashGuard.automaticStartBlocked(
                    record = descriptor.record,
                    engine = NativeModelEngine.LITERT_LM,
                    backend = backendId,
                )
            }
            check(requestedBackend.isNotEmpty()) {
                "此模型的所有候选后端都发生过连续原生崩溃；已停止自动重试，请手动启动、缩短上下文或更换模型"
            }
        }
        var lastFailure: Throwable? = null
        val loadStartedAt = SystemClock.elapsedRealtime()
        for (backend in requestedBackend) {
            var candidate: Engine? = null
            val backendKey = when (backend) {
                is Backend.NPU -> "npu"
                is Backend.GPU -> "gpu"
                else -> "cpu-${effectiveSettings.threads}"
            }
            val attempt = LocalModelNativeCrashGuard.beginAttempt(
                record = descriptor.record,
                engine = NativeModelEngine.LITERT_LM,
                backend = backendKey,
                phase = "engine-constructor",
                launchKind = launchKind,
            )
            try {
                // filesDir survives system cache eviction. Keep compiled graphs isolated by model
                // hash and backend so a failed GPU graph can never poison the CPU fallback.
                val backendCache = if (backend is Backend.NPU) null else context.filesDir.resolve(
                    "local_models/litert-cache/0.19.1/${descriptor.record.sha256.take(24)}/$backendKey",
                ).apply { mkdirs() }
                candidate = Engine(
                    EngineConfig(
                        modelPath = descriptor.file.absolutePath,
                        backend = backend,
                        visionBackend = backend.takeIf {
                            backend !is Backend.NPU &&
                            descriptor.record.supportsVision && effectiveSettings.imageInputEnabled
                        },
                        maxNumTokens = effectiveSettings.contextWindow,
                        maxNumImages = 1,
                        cacheDir = backendCache?.absolutePath,
                    ),
                )
                LocalModelNativeCrashGuard.phase(attempt, "engine-initialize")
                candidate.initialize()
                val backendName = when (backend) {
                    is Backend.NPU -> "NPU · Hexagon V${QualcommNpuRuntimeManager.arch ?: "?"} · LiteRT-LM"
                    is Backend.GPU -> "GPU"
                    else -> "CPU · ${effectiveSettings.threads} 线程"
                }
                val loaded = ActiveRuntime(
                    recordId = recordId,
                    engine = candidate,
                    settings = effectiveSettings,
                    requestedSettings = requestedSettings,
                    backendId = backendKey,
                    backendName = backendName,
                    loadedAt = System.currentTimeMillis(),
                    loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAt,
                )
                LocalModelNativeCrashGuard.phase(attempt, "conversation-create")
                val nativeContext = verifyConversationWarmUp(loaded, attempt)
                if (nativeContext > 0) {
                    loaded.settings = loaded.settings.copy(
                        contextWindow = minOf(loaded.settings.contextWindow, nativeContext),
                    )
                }
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
                runCatching { candidate?.close() }
                if (failure is CancellationException) throw failure
                lastFailure = failure
            }
        }
        fail(recordId, "模型加载失败：${lastFailure?.message ?: lastFailure?.javaClass?.simpleName.orEmpty()}")
    }

    /**
     * Engine initialisation alone does not exercise LiteRT-LM's JNI conversation contract. A
     * one-token disposable conversation catches the `createConversation` / sampler failures during
     * the explicit Start action instead of allowing the app to crash on the first user message.
     */
    private suspend fun verifyConversationWarmUp(runtime: ActiveRuntime, attempt: NativeAttemptLease): Int {
        LocalModelRepository.publishRuntimeSnapshot(
            LocalModelRuntimeSnapshot(
                recordId = runtime.recordId,
                state = LocalModelRuntimeState.STARTING,
                backend = runtime.backendName,
                message = "正在验证本地对话 · ${runtime.backendName}",
                loadedAt = runtime.loadedAt,
            ),
        )
        val conversation = runtime.engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of("Reply with OK."),
                samplerConfig = if (runtime.backendId == "npu") null else SamplerConfig(
                    topK = 1,
                    topP = 1.0,
                    temperature = 0.0,
                    seed = -1,
                ),
                thinkingConfig = ThinkingConfig(false),
            ),
        )
        var nativeContext = 0
        try {
            LocalModelNativeCrashGuard.phase(attempt, "warmup-generate")
            var receivedChunk = false
            withTimeout(WARM_UP_TIMEOUT_MS) {
                conversation.sendMessageAsync(
                    "Reply with OK.",
                    mapOf("enable_thinking" to false),
                    maxOutputToken = WARM_UP_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(false),
                ).collect { message ->
                    receivedChunk = true
                    val produced = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .any { it.text.isNotBlank() }
                    if (produced) attempt.firstTokenReceived()
                }
            }
            // A clean stream completion proves the JNI conversation contract even when a model
            // bundle does not expose a visible text chunk. Real engine crashes still propagate.
            if (!receivedChunk) attempt.firstTokenReceived()
            nativeContext = benchmarkInfo(conversation)?.maxNumTokens ?: 0
        } catch (failure: Throwable) {
            runCatching { conversation.cancelProcess() }
            throw IllegalStateException(
                "本地对话预热失败：${failure.message ?: failure.javaClass.simpleName}",
                failure,
            )
        } finally {
            runCatching { conversation.close() }
        }
        attempt.completed()
        return nativeContext
    }

    /** Releases an idle model under memory pressure; a running generation finishes first. */
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
                runCatching { current.close() }
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

    private fun scheduleIdleUnload(recordId: String, runtime: ActiveRuntime) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(runtime.settings.idleUnloadMinutes.coerceIn(1, 120) * 60_000L)
            // stop() cancels the public idle job. Clear our own reference first so the unload
            // path never tries to cancel and join the coroutine that is currently running it.
            idleJob = null
            stop(recordId)
        }
    }

    private fun checkResources(recordId: String) {
        val context = requireNotNull(appContext)
        val performance = LocalModelPerformanceMonitor.capture(context)
        val descriptor = LocalModelRepository.builtInDescriptor(recordId) ?: return
        if (descriptor.settings.thermalPolicy == LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE &&
            performance.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        ) {
            fail(recordId, "设备温度过高，已暂停本地推理")
        }
        if (descriptor.settings.powerPolicy == LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER &&
            performance.powerSaveMode
        ) {
            fail(recordId, "省电模式下已暂停本地推理")
        }
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

    private fun parseTurns(messages: JSONArray, fallbackSystem: String): List<LiteRtLmTurn> {
        val explicit = buildList {
            for (index in 0 until messages.length()) {
                val row = messages.optJSONObject(index) ?: continue
                val role = row.optString("role").lowercase().ifBlank { "user" }
                val text = contentText(row.opt("content")).trim()
                val images = contentImages(row.opt("content"))
                if (text.isNotEmpty() || images.isNotEmpty()) add(LiteRtLmTurn(role, text, images))
            }
        }
        if (explicit.any { it.role == "system" } || fallbackSystem.isBlank()) return explicit
        return listOf(LiteRtLmTurn("system", fallbackSystem)) + explicit
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

    private fun contentImages(raw: Any?): List<String> {
        val content = raw as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "image_url" -> part.optJSONObject("image_url")?.optString("url")
                        ?.takeIf(String::isNotBlank)?.let(::add)
                    "input_image" -> part.optString("image_url")
                        .takeIf(String::isNotBlank)?.let(::add)
                    "image" -> part.optString("url")
                        .takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }

    private fun LiteRtLmTurn.toUserMessage(): Message {
        if (images.isEmpty()) return Message.user(text)
        val content = images.map(::decodeImage) + Content.Text(text)
        return Message.user(Contents.of(content))
    }

    private fun decodeImage(reference: String): Content {
        if (reference.startsWith("data:image/", ignoreCase = true)) {
            val comma = reference.indexOf(',')
            require(comma > 0 && reference.substring(0, comma).endsWith(";base64", true)) {
                "本地视觉模型只支持 base64 data URL 图片"
            }
            val bytes = runCatching {
                Base64.decode(reference.substring(comma + 1), Base64.DEFAULT)
            }.getOrElse { failure ->
                throw AgentModelFailure(
                    code = "INVALID_IMAGE",
                    retryable = false,
                    message = "图片数据无效，请重新选择图片后重试。",
                    cause = failure,
                )
            }
            require(bytes.isNotEmpty()) { "图片数据为空" }
            return Content.ImageBytes(bytes)
        }
        val file = when {
            reference.startsWith("file://", ignoreCase = true) -> Uri.parse(reference).path?.let(::File)
            reference.startsWith('/') -> File(reference)
            else -> null
        }
        if (file?.isFile == true) return Content.ImageFile(file.absolutePath)
        throw AgentModelFailure(
            code = "UNSUPPORTED_IMAGE_REFERENCE",
            retryable = false,
            message = "本地视觉模型只支持本机图片或 data URL；远程图片请先下载后再附加。",
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun benchmarkInfo(conversation: Conversation): BenchmarkInfo? = runCatching {
        conversation.getBenchmarkInfo()
    }.getOrNull()

    private data class ActiveRuntime(
        val recordId: String,
        val engine: Engine,
        var settings: LocalModelRuntimeSettings,
        var requestedSettings: LocalModelRuntimeSettings,
        val backendId: String,
        val backendName: String,
        val loadedAt: Long,
        val loadDurationMs: Long,
    ) : Closeable {
        var conversation: Conversation? = null
        var history: List<LiteRtLmTurn> = emptyList()

        override fun close() {
            runCatching { conversation?.close() }
            conversation = null
            history = emptyList()
            engine.close()
        }

        fun metricBackend(): LocalRuntimeBackend = when (backendId) {
            "npu" -> LocalRuntimeBackend.NPU
            "gpu" -> LocalRuntimeBackend.GPU
            else -> LocalRuntimeBackend.CPU
        }
    }

    private const val WARM_UP_TIMEOUT_MS = 60_000L
    private const val WARM_UP_OUTPUT_TOKENS = 64
    private const val THINKING_BUDGET_TOKENS = 512
    private const val VISION_INPUT_RESERVE_TOKENS = 768
    private const val CONTEXT_SAFETY_TOKENS = 128
    private const val REPETITION_PENALTY = 1.08f
    private const val PRESENCE_PENALTY = 0.12f
    private const val FREQUENCY_PENALTY = 0.08f
    private const val REPETITION_WINDOW = 1_024
    private const val NO_REPEAT_NGRAM_SIZE = 8
    private const val NO_REPEAT_NGRAM_WINDOW = 0
}

private fun Double.formatRate(): String = String.format(java.util.Locale.US, "%.1f", this)

internal data class LocalRuntimeCompletion(
    val text: String,
    val tokenCount: Int?,
    val contextTokenCount: Int? = tokenCount,
    val finishReason: String = "stop",
    val metrics: io.github.mangi.eta.agent.runtime.LocalRuntimeMetrics? = null,
)

internal fun <T> continuesRuntimeHistory(requested: List<T>, represented: List<T>): Boolean =
    represented.isEmpty() || (
        requested.size >= represented.size && requested.takeLast(represented.size) == represented
    )

/** LiteRT-LM 0.19.1 reports deltas. Repeated text must remain visible. */
internal fun liteRtStreamDelta(@Suppress("UNUSED_PARAMETER") rendered: String, snapshot: String): String = snapshot
