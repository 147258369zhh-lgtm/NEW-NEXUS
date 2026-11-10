package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MediaPipe-backed local speech adapters. TTS and ASR deliberately stay outside the chat runtime:
 * their `.litertlm` bundles have audio interfaces and cannot be loaded as text LLMs.
 */
internal object LocalSpeechRuntime {
    private val ttsMutex = Mutex()
    private val asrMutex = Mutex()
    private var tts: CachedTts? = null
    private var asr: CachedAsr? = null
    private val mutableStates = MutableStateFlow<Map<LocalSpeechKind, LocalSpeechRuntimeSnapshot>>(emptyMap())
    val states: StateFlow<Map<LocalSpeechKind, LocalSpeechRuntimeSnapshot>> = mutableStates.asStateFlow()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        LocalModelRepository.init(context)
        LocalSpeechNativeCrashGuard.initialize(context)
    }

    fun isLocalTtsReady(settings: SpeechSettings): Boolean {
        val record = LocalModelRepository.record(settings.localTtsModelId) ?: return false
        val context = appContext ?: return false
        return localSpeechKind(record) == LocalSpeechKind.TTS &&
            record.format == LocalModelFormat.LITERT_LM &&
            LocalModelRepository.artifactFile(context, record).isFile
    }

    fun isLocalAsrReady(settings: SpeechSettings): Boolean {
        val record = LocalModelRepository.record(settings.localAsrModelId) ?: return false
        val context = appContext ?: return false
        return localSpeechKind(record) == LocalSpeechKind.ASR &&
            record.format == LocalModelFormat.LITERT_LM &&
            LocalModelRepository.artifactFile(context, record).isFile
    }

    suspend fun release(recordId: String) = withContext(Dispatchers.Default) {
        ttsMutex.withLock {
            if (tts?.recordId == recordId) {
                tts?.engine?.close()
                tts = null
                updateState(LocalSpeechKind.TTS, recordId = null, phase = LocalSpeechRuntimePhase.STOPPED)
            }
        }
        asrMutex.withLock {
            if (asr?.recordId == recordId) {
                asr?.engine?.close()
                asr = null
                updateState(LocalSpeechKind.ASR, recordId = null, phase = LocalSpeechRuntimePhase.STOPPED)
            }
        }
    }

    /** Loads and prewarms the selected local speech model without routing it through chat. */
    suspend fun warmUp(settings: SpeechSettings, kind: LocalSpeechKind) = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val id = if (kind == LocalSpeechKind.TTS) settings.localTtsModelId else settings.localAsrModelId
        val record = requireLocal(id, kind)
        updateState(
            kind = kind,
            recordId = record.id,
            phase = LocalSpeechRuntimePhase.STARTING,
            requestedBackend = record.runtimeSettings.backend.name,
            message = "正在加载",
        )
        try {
            val cached = when (kind) {
                LocalSpeechKind.TTS -> ttsMutex.withLock {
                    ensureTts(context, record, settings).also { tts = it }
                }
                LocalSpeechKind.ASR -> asrMutex.withLock {
                    ensureAsr(context, record).also { asr = it }
                }
            }
            updateState(
                kind = kind,
                recordId = record.id,
                phase = LocalSpeechRuntimePhase.READY,
                requestedBackend = record.runtimeSettings.backend.name,
                activeBackend = cached.activeBackend,
                message = LocalSpeechNativeCrashGuard.fallbackReason(record, kind, record.runtimeSettings.backend),
            )
        } catch (failure: Throwable) {
            updateState(
                kind = kind,
                recordId = record.id,
                phase = LocalSpeechRuntimePhase.FAILED,
                requestedBackend = record.runtimeSettings.backend.name,
                message = failure.message?.take(180) ?: failure.javaClass.simpleName,
            )
            throw failure
        }
    }

    suspend fun stop(recordId: String) = release(recordId)

    suspend fun synthesize(
        settings: SpeechSettings,
        text: String,
        onAudio: (ByteArray) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localTtsModelId, LocalSpeechKind.TTS)
        ttsMutex.withLock {
            val engine = ensureTts(context, record, settings).also { tts = it }
            val options = SynthesisOptions(settings.localTtsVoiceId.ifBlank { null }, settings.localTtsLocale.ifBlank { null })
            var resampler: StreamingPcmResampler? = null
            var sourceRate = 0
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.TTS, engine.requestedBackend, "tts-synthesize",
            )
            try {
                engine.engine.synthesizeStream(text, options).collect { chunk ->
                    attempt.phase("tts-stream")
                    if (resampler == null || sourceRate != chunk.sampleRateHz) {
                        sourceRate = chunk.sampleRateHz
                        resampler = StreamingPcmResampler(sourceRate, targetRate = 24_000)
                    }
                    val samples = requireNotNull(resampler).process(chunk.samples)
                    if (samples.isNotEmpty()) onAudio(floatPcmToPcm16(samples))
                }
                attempt.completed()
                updateState(
                    LocalSpeechKind.TTS, record.id, LocalSpeechRuntimePhase.READY,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                )
            } catch (failure: Throwable) {
                attempt.failed()
                discardTts(engine.engine)
                updateState(
                    LocalSpeechKind.TTS, record.id, LocalSpeechRuntimePhase.FAILED,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                    failure.message?.take(180) ?: failure.javaClass.simpleName,
                )
                throw failure
            }
        }
    }

    /** Returns the voices exposed by the installed TTS bundle rather than guessing from its file name. */
    suspend fun listVoices(settings: SpeechSettings): List<LocalSpeechVoiceInfo> = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localTtsModelId, LocalSpeechKind.TTS)
        ttsMutex.withLock {
            val engine = ensureTts(context, record, settings).also { tts = it }
            engine.engine.listVoices().map { voice ->
                LocalSpeechVoiceInfo(
                    id = voice.voiceId,
                    displayName = voice.displayName.ifBlank { voice.voiceId },
                    locale = voice.locale,
                    sampleRateHz = voice.sampleRateHz,
                    downloaded = voice.isDownloaded,
                )
            }
        }
    }

    suspend fun transcribe(
        settings: SpeechSettings,
        pcm16: ByteArray,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        if (pcm16.size < 2) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有录到语音，请重试")
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localAsrModelId, LocalSpeechKind.ASR)
        asrMutex.withLock {
            val engine = ensureAsr(context, record).also { asr = it }
            val samples = pcm16ToFloat(pcm16)
            val chunkSize = 16_000 // one second at the capture sample rate
            var offset = 0
            var latest = ""
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, engine.requestedBackend, "asr-transcribe",
            )
            try {
                while (offset < samples.size) {
                    attempt.phase("asr-stream")
                    val end = minOf(offset + chunkSize, samples.size)
                    val result = engine.engine.transcribe(samples.copyOfRange(offset, end), false)
                    val text = result.text.trim()
                    if (text.isNotBlank()) {
                        latest = text
                        onProgress(text)
                    }
                    offset = end
                }
                val final = engine.engine.finishStream().text.trim()
                attempt.completed()
                updateState(
                    LocalSpeechKind.ASR, record.id, LocalSpeechRuntimePhase.READY,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                )
                (final.ifBlank { latest }).trim()
            } catch (failure: Throwable) {
                attempt.failed()
                discardAsr(engine.engine)
                updateState(
                    LocalSpeechKind.ASR, record.id, LocalSpeechRuntimePhase.FAILED,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                    failure.message?.take(180) ?: failure.javaClass.simpleName,
                )
                throw failure
            }
        }
    }

    /** Streams captured PCM through the transcriber and can end on local voice activity. */
    suspend fun transcribeStream(
        settings: SpeechSettings,
        frames: ReceiveChannel<ByteArray>,
        endpointSilenceMs: Int,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localAsrModelId, LocalSpeechKind.ASR)
        asrMutex.withLock {
            val engine = ensureAsr(context, record).also { asr = it }
            val startedAt = SystemClock.elapsedRealtime()
            val endpoint = SpeechEndpointTracker(endpointSilenceMs)
            val transcript = SpeechTranscriptAccumulator()
            var latest = ""
            var acceptedResults = 0L
            var firstResultAtMs: Long? = null
            var endpointReached = false
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, engine.requestedBackend, "asr-stream-start",
            )
            try {
                for (frame in frames) {
                    attempt.phase("asr-stream-frame")
                    endpoint.consume(frame)
                    val result = engine.engine.transcribe(pcm16ToFloat(frame), false)
                    val segments = result.segments().map { segment ->
                        SpeechTranscriptSegment(
                            text = segment.transcript(),
                            isFinal = segment.isFinal(),
                            fromMs = segment.fromTimestampMs(),
                            toMs = segment.toTimestampMs(),
                        )
                    }
                    val text = transcript.accept(segments)
                    if (text.isNotBlank()) {
                        latest = text
                        acceptedResults++
                        if (firstResultAtMs == null) {
                            firstResultAtMs = SystemClock.elapsedRealtime() - startedAt
                        }
                        endpoint.markTranscript()
                        onProgress(latest)
                    }
                    if (endpointSilenceMs > 0 && endpoint.reachedEndpoint()) {
                        endpointReached = true
                        break
                    }
                }
                val finalResult = engine.engine.finishStream()
                val final = transcript.accept(finalResult.segments().map { segment ->
                    SpeechTranscriptSegment(
                        text = segment.transcript(),
                        isFinal = segment.isFinal(),
                        fromMs = segment.fromTimestampMs(),
                        toMs = segment.toTimestampMs(),
                    )
                }).ifBlank { latest }
                attempt.completed()
                updateState(
                    LocalSpeechKind.ASR, record.id, LocalSpeechRuntimePhase.READY,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                )
                AndroidAgentLogger.info(
                    "Nexus local ASR complete model=${record.id} backend=${engine.activeBackend} " +
                        "frames=${endpoint.frameCount} samples=${endpoint.sampleCount} " +
                        "audioMs=${endpoint.durationMs} voicedFrames=${endpoint.voicedFrameCount} " +
                        "peakRms=${endpoint.peakRms} accepted=${acceptedResults} " +
                        "firstResultMs=${firstResultAtMs ?: -1} finalChars=${final.length} " +
                        "endpoint=$endpointReached elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                final.trim()
            } catch (failure: Throwable) {
                attempt.failed()
                discardAsr(engine.engine)
                AndroidAgentLogger.warn(
                    "Nexus local ASR failed model=${record.id} backend=${engine.activeBackend} " +
                        "frames=${endpoint.frameCount} samples=${endpoint.sampleCount} " +
                        "audioMs=${endpoint.durationMs} voicedFrames=${endpoint.voicedFrameCount} " +
                        "accepted=${acceptedResults} partialChars=${latest.length} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "type=${failure.javaClass.simpleName} code=${failure.speechFailure().code}",
                )
                updateState(
                    LocalSpeechKind.ASR, record.id, LocalSpeechRuntimePhase.FAILED,
                    record.runtimeSettings.backend.name, engine.activeBackend,
                    failure.message?.take(180) ?: failure.javaClass.simpleName,
                )
                throw failure
            }
        }
    }

    private fun ensureTts(context: Context, record: LocalModelRecord, settings: SpeechSettings): CachedTts {
        val requested = record.runtimeSettings.backend
        val cached = tts
        if (cached != null && cached.recordId == record.id && cached.requestedBackend == requested &&
            cached.speakingRate == settings.localTtsRate.coerceIn(0.5f, 2f)) return cached
        cached?.engine?.close()
        tts = null
        return createTts(context, record, settings)
    }

    private fun ensureAsr(context: Context, record: LocalModelRecord): CachedAsr {
        val requested = record.runtimeSettings.backend
        val cached = asr
        if (cached != null && cached.recordId == record.id && cached.requestedBackend == requested) return cached
        cached?.engine?.close()
        asr = null
        return createAsr(context, record)
    }

    /** A failed native call may leave the engine stream unusable; force a clean reload next time. */
    private fun discardTts(engine: TextToSpeech) {
        if (tts?.engine === engine) tts = null
        runCatching { engine.close() }
    }

    private fun discardAsr(engine: AudioTranscriber) {
        if (asr?.engine === engine) asr = null
        runCatching { engine.close() }
    }

    private fun createTts(
        context: Context,
        record: LocalModelRecord,
        settings: SpeechSettings,
    ): CachedTts {
        val file = LocalModelRepository.artifactFile(context, record)
        if (!file.isFile) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地播报模型文件不存在，请重新下载")
        val requested = record.runtimeSettings.backend
        val safe = LocalSpeechNativeCrashGuard.safePolicy(record, LocalSpeechKind.TTS, requested)
        val delegates = speechDelegates(safe)
        var last: Throwable? = null
        for (delegate in delegates) {
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.TTS, safe, "tts-load-${delegate.name}",
            )
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath(file.absolutePath)
                    .setDelegate(delegate)
                    .build()
                val options = TextToSpeechOptions(
                    base,
                    settings.localTtsVoiceId.ifBlank { null },
                    settings.localTtsLocale.ifBlank { null },
                    settings.localTtsRate.coerceIn(0.5f, 2f),
                    0,
                    context.cacheDir.resolve("local-speech-tts").apply { mkdirs() }.absolutePath,
                )
                val engine = TextToSpeech.create(options)
                attempt.completed()
                return CachedTts(
                    recordId = record.id,
                    requestedBackend = requested,
                    activeBackend = delegate.name,
                    speakingRate = settings.localTtsRate.coerceIn(0.5f, 2f),
                    engine = engine,
                )
            } catch (failure: Throwable) {
                attempt.failed()
                last = failure
            }
        }
        throw SpeechFailure(SpeechErrorCode.AUDIO, "本地播报模型启动失败：${last?.message.orEmpty().take(160)}")
    }

    private fun createAsr(context: Context, record: LocalModelRecord): CachedAsr {
        val file = LocalModelRepository.artifactFile(context, record)
        if (!file.isFile) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别模型文件不存在，请重新下载")
        val requested = record.runtimeSettings.backend
        val safe = LocalSpeechNativeCrashGuard.safePolicy(record, LocalSpeechKind.ASR, requested)
        val delegates = speechDelegates(safe)
        var last: Throwable? = null
        for (delegate in delegates) {
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, safe, "asr-load-${delegate.name}",
            )
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath(file.absolutePath)
                    .setDelegate(delegate)
                    .build()
                val options = AudioTranscriber.AudioTranscriberOptions.builder()
                    .setBaseOptions(base)
                    .setSampleRateHz(SpeechRecorder.RATE)
                    .setEnableInterimResults(true)
                    .setModelArchitecture(AudioTranscriber.ModelArchitecture.AUTO)
                    .setCacheDir(context.cacheDir.resolve("local-speech-asr").apply { mkdirs() }.absolutePath)
                    .build()
                val transcriber = AudioTranscriber.createFromOptions(context, options)
                transcriber.prewarm()
                attempt.completed()
                return CachedAsr(record.id, requested, delegate.name, transcriber)
            } catch (failure: Throwable) {
                attempt.failed()
                last = failure
            }
        }
        throw SpeechFailure(SpeechErrorCode.AUDIO, "本地识别模型启动失败：${last?.message.orEmpty().take(160)}")
    }

    private fun requireLocal(recordId: String, kind: LocalSpeechKind): LocalModelRecord {
        if (recordId.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先选择本地${kind.label}模型")
        val record = LocalModelRepository.record(recordId)
            ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "所选本地${kind.label}模型已移除")
        if (localSpeechKind(record) != kind) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "所选模型不是${kind.label}模型")
        if (record.format != LocalModelFormat.LITERT_LM) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "当前本地${kind.label}适配器仅支持 LiteRT-LM 语音包")
        }
        return record
    }

    private fun speechDelegates(policy: LocalRuntimeBackendPolicy): List<Delegate> = when (policy) {
        LocalRuntimeBackendPolicy.NPU -> listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)
        LocalRuntimeBackendPolicy.GPU -> listOf(Delegate.GPU, Delegate.CPU)
        LocalRuntimeBackendPolicy.CPU -> listOf(Delegate.CPU)
        LocalRuntimeBackendPolicy.AUTO -> listOf(Delegate.GPU, Delegate.CPU)
    }.distinct()

    private fun updateState(
        kind: LocalSpeechKind,
        recordId: String?,
        phase: LocalSpeechRuntimePhase,
        requestedBackend: String = "",
        activeBackend: String = "",
        message: String? = null,
    ) {
        mutableStates.value = mutableStates.value.toMutableMap().apply {
            this[kind] = LocalSpeechRuntimeSnapshot(
                kind = kind,
                recordId = recordId,
                phase = phase,
                requestedBackend = requestedBackend,
                activeBackend = activeBackend,
                message = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private sealed interface CachedSpeech {
        val recordId: String
        val requestedBackend: LocalRuntimeBackendPolicy
        val activeBackend: String
    }

    private data class CachedTts(
        override val recordId: String,
        override val requestedBackend: LocalRuntimeBackendPolicy,
        override val activeBackend: String,
        val speakingRate: Float,
        val engine: TextToSpeech,
    ) : CachedSpeech

    private data class CachedAsr(
        override val recordId: String,
        override val requestedBackend: LocalRuntimeBackendPolicy,
        override val activeBackend: String,
        val engine: AudioTranscriber,
    ) : CachedSpeech
}

internal enum class LocalSpeechKind(val label: String) { TTS("播报"), ASR("识别") }

internal enum class LocalSpeechRuntimePhase { STOPPED, STARTING, READY, FAILED }

internal data class LocalSpeechVoiceInfo(
    val id: String,
    val displayName: String,
    val locale: String,
    val sampleRateHz: Int,
    val downloaded: Boolean,
)

internal data class LocalSpeechRuntimeSnapshot(
    val kind: LocalSpeechKind,
    val recordId: String?,
    val phase: LocalSpeechRuntimePhase,
    val requestedBackend: String,
    val activeBackend: String,
    val message: String?,
    val updatedAt: Long,
)

internal fun localSpeechKind(record: LocalModelRecord): LocalSpeechKind? {
    val input = record.inputModalities.map(String::lowercase).toSet()
    val output = record.outputModalities.map(String::lowercase).toSet()
    return when {
        "audio" in output && "text" in input -> LocalSpeechKind.TTS
        "audio" in input && "text" in output -> LocalSpeechKind.ASR
        else -> null
    }
}

internal fun floatPcmToPcm16(samples: FloatArray): ByteArray {
    val output = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        output.putShort(value)
    }
    return output.array()
}

internal fun pcm16ToFloat(bytes: ByteArray): FloatArray {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) {
        input.short / 32768f
    }
}
