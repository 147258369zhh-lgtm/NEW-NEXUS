package io.github.mangi.eta.agent.model

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ModelType
import java.io.File
import io.github.mangi.eta.agent.runtime.LocalInferenceScheduling
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Dedicated LiteRT-LM embedding owner. Embedding artifacts never enter the chat providers. */
internal object LocalEmbeddingRuntime {
    private const val STOP_TIMEOUT_MS = 5_000L
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalEmbeddingRuntimeSnapshot())
    val state: StateFlow<LocalEmbeddingRuntimeSnapshot> = mutableState.asStateFlow()
    @Volatile private var engine: Engine? = null
    @Volatile private var recordId: String? = null
    @Volatile private var activeBackend: String = ""
    @Volatile private var dimension: Int? = null
    @Volatile private var loadedIdentity: EmbeddingIdentity? = null

    private data class EmbeddingIdentity(val modelId: String, val sha256: String, val path: String, val settings: io.github.mangi.eta.data.repository.LocalModelRuntimeSettings)

    internal interface Engine : AutoCloseable {
        fun initialize()
        fun compute(text: String): FloatArray
    }

    private fun interface EngineFactory {
        fun create(path: String, cacheDir: String, backend: Backend): Engine
    }

    private fun interface ModelInspector { fun dimension(path: String): Int? }
    private fun interface ArtifactResolver { fun path(context: Context, record: LocalModelRecord): File }
    @Volatile private var modelInspector = ModelInspector { path ->
        val info = ModelInfo.from(path)
        try {
            require(info.modelType == ModelType.EMBEDDING) { "所选 LiteRT-LM 文件不是向量模型" }
            (info as? ModelInfo.Embedding)?.dimension()
        } finally { info.close() }
    }
    @Volatile private var artifactResolver = ArtifactResolver { context, record -> LocalModelRepository.artifactFile(context, record) }

    @Volatile private var engineFactory = EngineFactory { path, cacheDir, backend ->
        val native = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
        object : Engine {
            override fun initialize() = native.initialize()
            override fun compute(text: String): FloatArray = native.computeEmbedding(
                listOf(InputData.Text(text)), EmbeddingOptions(normalize = true),
            ).embedding
            override fun close() = native.close()
        }
    }

    @VisibleForTesting
    internal fun setEngineFactoryForTesting(factory: ((String, String, Backend) -> Engine)?) {
        engineFactory = factory?.let { value -> EngineFactory(value) } ?: EngineFactory { path, cacheDir, backend ->
            val native = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
            object : Engine {
                override fun initialize() = native.initialize()
                override fun compute(text: String): FloatArray = native.computeEmbedding(
                    listOf(InputData.Text(text)), EmbeddingOptions(normalize = true),
                ).embedding
                override fun close() = native.close()
            }
        }
    }

    suspend fun warmUp(context: Context, record: LocalModelRecord) {
        requireEmbeddingRecord(record)
        val app = context.applicationContext
        LocalModelNativeCrashGuard.initialize(app)
        try {
            LocalInferenceScheduling.warmUp(record.id, automatic = false) {
                mutex.withLock { ensureLoadedLocked(app, record) }
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                mutableState.value = mutableState.value.copy(
                    phase = if (recordId == record.id) LocalEmbeddingRuntimePhase.READY else LocalEmbeddingRuntimePhase.STOPPED,
                    updatedAt = System.currentTimeMillis(),
                )
            } else if (mutableState.value.recordId == record.id && mutableState.value.phase != LocalEmbeddingRuntimePhase.FAILED) {
                mutableState.value = mutableState.value.copy(
                    phase = LocalEmbeddingRuntimePhase.FAILED,
                    message = failure.message?.take(180) ?: failure.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            throw failure
        }
    }

    @VisibleForTesting
    internal suspend fun withTestEngine(
        factory: (String, String, Backend) -> Engine,
        block: suspend () -> Unit,
        inspect: (String) -> Int? = { null },
        resolve: (Context, LocalModelRecord) -> File = { _, record -> File(record.fileName) },
    ) {
        val previousFactory = engineFactory
        val previousInspector = modelInspector
        val previousResolver = artifactResolver
        engineFactory = EngineFactory(factory)
        modelInspector = ModelInspector(inspect)
        artifactResolver = ArtifactResolver(resolve)
        try { block() } finally {
            runCatching { releaseCached() }
            engineFactory = previousFactory
            modelInspector = previousInspector
            artifactResolver = previousResolver
        }
    }

    private suspend fun ensureLoadedLocked(app: Context, record: LocalModelRecord) {
        val path = artifactResolver.path(app, record)
        val identity = EmbeddingIdentity(record.id, record.sha256, path.absolutePath, record.runtimeSettings.validated())
        if (engine != null && loadedIdentity == identity) return
        if (engine != null) releaseLocked(recordId)
        mutableState.value = LocalEmbeddingRuntimeSnapshot(
            recordId = record.id,
            phase = LocalEmbeddingRuntimePhase.STARTING,
            requestedBackend = record.runtimeSettings.backend.name,
            updatedAt = System.currentTimeMillis(),
        )
        require(record.format == LocalModelFormat.LITERT_LM && path.isFile) {
            "本地向量模型文件不存在或格式不受支持"
        }
        val modelDimension = modelInspector.dimension(path.absolutePath)
        LocalModelResourceOwner.prepareEmbedding()
        val candidates = embeddingBackends(record, app)
        var failure: Throwable? = null
        for ((backendName, backend) in candidates) {
                    val attempt = LocalModelNativeCrashGuard.beginAttempt(
                        record, NativeModelEngine.LITERT_LM, backendName, "embedding_load",
                    )
            var candidate: Engine? = null
            try {
                val created = engineFactory.create(path.absolutePath, app.cacheDir.absolutePath, backend)
                candidate = created
                created.initialize()
                currentCoroutineContext().ensureActive()
                attempt.completed()
                engine = candidate
                candidate = null
                recordId = record.id
                loadedIdentity = identity
                activeBackend = backendName
                dimension = modelDimension
                mutableState.value = LocalEmbeddingRuntimeSnapshot(
                    recordId = record.id,
                    phase = LocalEmbeddingRuntimePhase.READY,
                    requestedBackend = record.runtimeSettings.backend.name,
                    activeBackend = backendName,
                    dimension = modelDimension,
                    message = backendFallbackMessage(record.runtimeSettings.backend, backendName),
                    updatedAt = System.currentTimeMillis(),
                )
                return
            } catch (failureForBackend: Throwable) {
                LocalModelNativeCrashGuard.failed(attempt)
                runCatching { candidate?.close() }
                failure = failureForBackend
                if (failureForBackend is CancellationException) throw failureForBackend
            }
        }
        val cause = failure ?: IllegalStateException("没有可用的向量模型后端")
        mutableState.value = LocalEmbeddingRuntimeSnapshot(
            recordId = record.id,
            phase = LocalEmbeddingRuntimePhase.FAILED,
            requestedBackend = record.runtimeSettings.backend.name,
            message = cause.message?.take(180) ?: cause.javaClass.simpleName,
            updatedAt = System.currentTimeMillis(),
        )
        throw cause
    }

    suspend fun embed(context: Context, record: LocalModelRecord, text: String): LocalEmbeddingResult {
        require(text.isNotBlank() && text.length <= MAX_INPUT_CHARS) { "向量文本必须为非空且不超过 $MAX_INPUT_CHARS 字符" }
        requireEmbeddingRecord(record)
        val app = context.applicationContext
        LocalModelNativeCrashGuard.initialize(app)
        return LocalInferenceScheduling.warmUp(record.id, automatic = false) {
            mutex.withLock {
                ensureLoadedLocked(app, record)
                mutableState.value = mutableState.value.copy(phase = LocalEmbeddingRuntimePhase.GENERATING)
                val started = SystemClock.elapsedRealtime()
                val attempt = LocalModelNativeCrashGuard.beginAttempt(
                    record, NativeModelEngine.LITERT_LM, activeBackend, "embedding_compute",
                )
                try {
                    val values = requireNotNull(engine).compute(text).copyOf()
                    currentCoroutineContext().ensureActive()
                    check(values.isNotEmpty() && values.all(Float::isFinite)) { "向量模型返回了无效结果" }
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
                    attempt.completed()
                    dimension = values.size
                    val metrics = LocalEmbeddingMetrics(record.id, values.size, elapsed, activeBackend, true, null)
                    mutableState.value = mutableState.value.copy(
                        phase = LocalEmbeddingRuntimePhase.READY,
                        dimension = values.size,
                        metrics = metrics,
                        updatedAt = System.currentTimeMillis(),
                    )
                    LocalEmbeddingResult(values, values.size, elapsed, activeBackend)
                } catch (failure: Throwable) {
                    LocalModelNativeCrashGuard.failed(attempt)
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
                    if (failure is CancellationException) {
                        releaseLocked(record.id)
                        throw failure
                    }
                    val code = if (failure is TimeoutCancellationException) "TIMEOUT" else "EMBEDDING_FAILED"
                    mutableState.value = mutableState.value.copy(
                        phase = LocalEmbeddingRuntimePhase.FAILED,
                        metrics = LocalEmbeddingMetrics(record.id, dimension ?: 0, elapsed, activeBackend, false, code),
                        message = failure.message?.take(180) ?: failure.javaClass.simpleName,
                        updatedAt = System.currentTimeMillis(),
                    )
                    throw failure
                }
            }
        }
    }

    suspend fun stop(recordId: String? = null) {
        if (recordId == null) return
        LocalInferenceScheduling.stopModel(recordId).use {
            try {
                withTimeout(STOP_TIMEOUT_MS) { mutex.withLock { releaseLocked(recordId) } }
            } catch (failure: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(
                    phase = LocalEmbeddingRuntimePhase.FAILED,
                    message = "停止仍在等待向量推理退出",
                    updatedAt = System.currentTimeMillis(),
                )
                throw failure
            }
        }
    }

    /** Caller already owns the shared inference slot. */
    suspend fun releaseCached() = mutex.withLock { releaseLocked(null) }

    suspend fun releaseForMemory(level: Int) {
        if (level !in setOf(
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            )) return
        stop(recordId ?: state.value.recordId)
    }

    private fun releaseLocked(expectedRecordId: String?) {
        if (expectedRecordId != null && recordId != expectedRecordId && state.value.recordId != expectedRecordId) return
        val oldId = recordId ?: state.value.recordId
        mutableState.value = mutableState.value.copy(
            phase = LocalEmbeddingRuntimePhase.STOPPING,
            updatedAt = System.currentTimeMillis(),
        )
        val closing = engine
        engine = null
        recordId = null
        loadedIdentity = null
        activeBackend = ""
        dimension = null
        try {
            closing?.close()
        } catch (failure: Throwable) {
            mutableState.value = LocalEmbeddingRuntimeSnapshot(
                recordId = oldId,
                phase = LocalEmbeddingRuntimePhase.FAILED,
                message = "向量引擎释放失败：${failure.message?.take(120) ?: failure.javaClass.simpleName}",
                updatedAt = System.currentTimeMillis(),
            )
            throw failure
        }
        mutableState.value = LocalEmbeddingRuntimeSnapshot(
            recordId = oldId,
            phase = LocalEmbeddingRuntimePhase.STOPPED,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun requireEmbeddingRecord(record: LocalModelRecord) {
        require(record.format == LocalModelFormat.LITERT_LM && "embedding" in record.outputModalities && "text" in record.inputModalities) {
            "该模型不是受支持的本地向量模型"
        }
    }

    private fun embeddingBackends(record: LocalModelRecord, context: Context): List<Pair<String, Backend>> {
        val requested = record.runtimeSettings.backend
        val threads = record.runtimeSettings.threads
        val candidates = when (requested) {
        LocalRuntimeBackendPolicy.CPU -> listOf("CPU" to Backend.CPU(threads))
        LocalRuntimeBackendPolicy.GPU -> listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU(threads))
        LocalRuntimeBackendPolicy.NPU -> listOf(
            "NPU" to Backend.NPU(context.applicationInfo.nativeLibraryDir),
            "GPU" to Backend.GPU(),
            "CPU" to Backend.CPU(threads),
        )
        LocalRuntimeBackendPolicy.AUTO -> listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU(threads))
        }
        return candidates.filterNot { (name, _) ->
            name == "GPU" && LocalModelNativeCrashGuard.gpuRequiresCpuFallback(record, NativeModelEngine.LITERT_LM)
        }.ifEmpty { listOf("CPU" to Backend.CPU(threads)) }
    }

    private fun backendFallbackMessage(requested: LocalRuntimeBackendPolicy, active: String): String? =
        if (active == requested.name || requested == LocalRuntimeBackendPolicy.AUTO && active == "GPU") null
        else "请求 ${requested.name}，实际使用 $active"

    private const val MAX_INPUT_CHARS = 16_000
}

internal enum class LocalEmbeddingRuntimePhase { STOPPED, STARTING, READY, GENERATING, STOPPING, FAILED }

internal data class LocalEmbeddingMetrics(
    val modelId: String,
    val vectorDimension: Int,
    val elapsedMs: Long,
    val backend: String,
    val successful: Boolean,
    val failureCode: String?,
)

internal data class LocalEmbeddingRuntimeSnapshot(
    val recordId: String? = null,
    val phase: LocalEmbeddingRuntimePhase = LocalEmbeddingRuntimePhase.STOPPED,
    val requestedBackend: String = "",
    val activeBackend: String = "",
    val dimension: Int? = null,
    val message: String? = null,
    val metrics: LocalEmbeddingMetrics? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun json(): JSONObject = JSONObject()
        .put("recordId", recordId ?: JSONObject.NULL)
        .put("phase", phase.name)
        .put("requestedBackend", requestedBackend)
        .put("activeBackend", activeBackend)
        .put("message", message ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)
        .put("dimension", dimension ?: JSONObject.NULL)
        .put("metrics", metrics?.let { metric ->
            JSONObject().put("modelId", metric.modelId)
                .put("vectorDimension", metric.vectorDimension)
                .put("elapsedMs", metric.elapsedMs)
                .put("backend", metric.backend)
                .put("successful", metric.successful)
                .put("failureCode", metric.failureCode ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
}

internal data class LocalEmbeddingResult(
    val vector: FloatArray,
    val dimension: Int,
    val elapsedMs: Long,
    val backend: String,
)
