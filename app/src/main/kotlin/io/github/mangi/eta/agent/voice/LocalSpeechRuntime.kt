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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * MediaPipe-backed local speech adapters. TTS and ASR deliberately stay outside the chat runtime:
 * their `.litertlm` bundles have audio interfaces and cannot be loaded as text LLMs.
 */
internal object LocalSpeechRuntime {
    private val ttsMutex = Mutex()
    private val asrMutex = Mutex()
    private val operations = LocalSpeechOperations()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleJobs = mutableMapOf<LocalSpeechKind, Job>()
    @Volatile private var tts: CachedTts? = null
    @Volatile private var asr: CachedAsr? = null
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
            supportedArtifact(context, record)
    }

    fun isLocalAsrReady(settings: SpeechSettings): Boolean {
        val record = LocalModelRepository.record(settings.localAsrModelId) ?: return false
        val context = appContext ?: return false
        return localSpeechKind(record) == LocalSpeechKind.ASR &&
            supportedArtifact(context, record)
    }

    private fun supportedArtifact(context: Context, record: LocalModelRecord): Boolean {
        val file = LocalModelRepository.artifactFile(context, record)
        return record.format == LocalModelFormat.LITERT_LM && file.isFile || record.format == LocalModelFormat.ONNX &&
            runCatching { SpeechModelPackage.inspect(file)?.kind == localSpeechKind(record) }.getOrDefault(false)
    }

    private fun cachedRecordId(kind: LocalSpeechKind) = (if (kind == LocalSpeechKind.TTS) tts?.recordId else asr?.recordId) ?: SherpaSpeechRuntime.recordId(kind)

    suspend fun release(recordId: String) = withContext(Dispatchers.Default) {
        val stop = operations.stop(recordId)
        val targets = stop.kinds + listOfNotNull(
            LocalSpeechKind.TTS.takeIf { cachedRecordId(LocalSpeechKind.TTS) == recordId }, LocalSpeechKind.ASR.takeIf { cachedRecordId(LocalSpeechKind.ASR) == recordId })
        targets.forEach { updateState(it, recordId, LocalSpeechRuntimePhase.STOPPING) }
        try {
            withTimeout(5_000) {
                for (kind in targets) {
                    val mutex = if (kind == LocalSpeechKind.TTS) ttsMutex else asrMutex
                    mutex.withLock {
                        if (kind == LocalSpeechKind.TTS && tts?.recordId == recordId) {
                            val current = tts; tts = null; current?.engine?.close()
                        }
                        if (kind == LocalSpeechKind.ASR && asr?.recordId == recordId) {
                            val current = asr; asr = null; current?.engine?.close()
                        }
                        SherpaSpeechRuntime.release(kind, recordId)
                        stoppedState(kind, recordId)
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            targets.forEach { updateState(it, recordId, LocalSpeechRuntimePhase.FAILED, message = "停止未完成，正在等待推理退出") }
            throw SpeechFailure(SpeechErrorCode.TIMEOUT, "本地语音停止未完成，请等待当前推理退出")
        } catch (failure: Exception) {
            targets.forEach { updateState(it, recordId, LocalSpeechRuntimePhase.FAILED, message = "模型释放失败，请重试") }
            throw failure
        } finally { stop.close() }
    }

    @Synchronized private fun stoppedState(kind: LocalSpeechKind, recordId: String) {
        val current = mutableStates.value[kind]?.recordId
        if (current == null || current == recordId) updateState(kind, null, LocalSpeechRuntimePhase.STOPPED)
    }

    private suspend fun <T> operate(kind: LocalSpeechKind, recordId: String, block: suspend () -> T): T = operations.run(recordId, kind) {
        cancelIdleRelease(kind)
        val mutex = if (kind == LocalSpeechKind.TTS) ttsMutex else asrMutex
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                block().also {
                    currentCoroutineContext().ensureActive()
                    scheduleIdleRelease(kind, recordId)
                }
            } catch (cancelled: CancellationException) {
                if (kind == LocalSpeechKind.TTS && tts?.recordId == recordId) tts?.engine?.let(::discardTts)
                if (kind == LocalSpeechKind.ASR && asr?.recordId == recordId) asr?.engine?.let(::discardAsr)
                SherpaSpeechRuntime.release(kind, recordId)
                stoppedState(kind, recordId)
                throw cancelled
            } catch (failure: Exception) {
                val record = LocalModelRepository.record(recordId)
                val current = mutableStates.value[kind]?.takeIf { it.recordId == recordId }
                updateState(kind, recordId, LocalSpeechRuntimePhase.FAILED,
                    record?.runtimeSettings?.backend?.name.orEmpty(), current?.activeBackend.orEmpty(),
                    failure.message?.take(180) ?: failure.javaClass.simpleName)
                throw failure
            }
        }
    }

    @Synchronized private fun cancelIdleRelease(kind: LocalSpeechKind) { idleJobs.remove(kind)?.cancel() }

    @Synchronized private fun scheduleIdleRelease(kind: LocalSpeechKind, recordId: String) {
        cancelIdleRelease(kind)
        val minutes = LocalModelRepository.record(recordId)?.runtimeSettings?.idleUnloadMinutes?.coerceIn(1, 120) ?: 10
        idleJobs[kind] = releaseScope.launch {
            delay(minutes * 60_000L)
            val mutex = if (kind == LocalSpeechKind.TTS) ttsMutex else asrMutex
            mutex.withLock {
                // A queued request owns the model too. An idle timer must never cancel it.
                if (!operations.isInUse(kind) && cachedRecordId(kind) == recordId) {
                    if (kind == LocalSpeechKind.TTS) tts?.engine?.let(::discardTts)
                    else asr?.engine?.let(::discardAsr)
                    SherpaSpeechRuntime.release(kind, recordId)
                    stoppedState(kind, recordId)
                }
            }
        }
    }

    /** Loads and prewarms the selected local speech model without routing it through chat. */
    suspend fun warmUp(settings: SpeechSettings, kind: LocalSpeechKind) = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val id = if (kind == LocalSpeechKind.TTS) settings.localTtsModelId else settings.localAsrModelId
        val record = requireLocal(id, kind)
        operate(kind, record.id) {
            if (record.format == LocalModelFormat.ONNX) return@operate onnx(record, kind, manualStart = true) { Unit }
            updateState(kind, record.id, LocalSpeechRuntimePhase.STARTING, record.runtimeSettings.backend.name, message = "正在加载")
            try {
                val cached = when (kind) {
                    LocalSpeechKind.TTS -> ensureTts(context, record, settings).also { tts = it }
                    LocalSpeechKind.ASR -> ensureAsr(context, record).also { asr = it }
                }
                currentCoroutineContext().ensureActive()
                updateState(
                    kind = kind,
                    recordId = record.id,
                    phase = LocalSpeechRuntimePhase.READY,
                    requestedBackend = record.runtimeSettings.backend.name,
                    activeBackend = cached.activeBackend,
                    message = LocalSpeechNativeCrashGuard.fallbackReason(record, kind, record.runtimeSettings.backend),
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
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
    }

    suspend fun stop(recordId: String) = release(recordId)

    suspend fun <T> whileStopped(recordId: String, block: suspend () -> T): T = operations.stop(recordId).use {
        release(recordId)
        block()
    }

    /** Covers loaded speech models even when the assistant overlay is not alive. */
    fun requestMemoryRelief(level: Int) {
        releaseScope.launch {
            try { releaseForMemory(level) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { AndroidAgentLogger.warn("Nexus speech memory release failed: type=${failure.javaClass.simpleName}") }
        }
    }

    suspend fun releaseForMemory(level: Int) {
        val targets = LocalVoiceResourcePolicy.releaseTargets(level,
            speaking = operations.isInUse(LocalSpeechKind.TTS), listening = operations.isInUse(LocalSpeechKind.ASR))
        val records = targets.flatMap { kind ->
            listOfNotNull(cachedRecordId(kind)) + operations.records(kind)
        }
        if (records.isEmpty()) return
        records.distinct().forEach {
            try { release(it) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                AndroidAgentLogger.warn("Nexus speech memory release failed: type=${failure.javaClass.simpleName}")
                throw failure
            }
        }
    }

    suspend fun releaseRecognitionForPrimaryModel() {
        (listOfNotNull(cachedRecordId(LocalSpeechKind.ASR)) + operations.records(LocalSpeechKind.ASR)).distinct().forEach { release(it) }
    }

    suspend fun synthesize(settings: SpeechSettings, text: String, onAudio: (ByteArray) -> Unit) =
        measured(settings.localTtsModelId, LocalSpeechKind.TTS) { meter ->
            synthesizeNative(settings, text) { bytes -> meter.output(bytes); onAudio(bytes) }
        }

    private suspend fun synthesizeNative(
        settings: SpeechSettings,
        text: String,
        onAudio: (ByteArray) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localTtsModelId, LocalSpeechKind.TTS)
        operate(LocalSpeechKind.TTS, record.id) {
            if (record.format == LocalModelFormat.ONNX) return@operate onnx(record, LocalSpeechKind.TTS) { SherpaSpeechRuntime.synthesize(settings, text, onAudio) }
            val engine = ensureTts(context, record, settings).also { tts = it }
            val options = SynthesisOptions(settings.localTtsVoiceId.ifBlank { null }, settings.localTtsLocale.ifBlank { null })
            var resampler: StreamingPcmResampler? = null
            var sourceRate = 0
            var outputBytes = 0L
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.TTS, LocalRuntimeBackendPolicy.valueOf(engine.activeBackend), "tts-synthesize",
            )
            try {
                engine.engine.synthesizeStream(text, options).collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    attempt.phase("tts-stream")
                    require(chunk.sampleRateHz in 8_000..192_000) { "语音模型返回了无效采样率" }
                    if (resampler == null || sourceRate != chunk.sampleRateHz) {
                        sourceRate = chunk.sampleRateHz
                        resampler = StreamingPcmResampler(sourceRate, targetRate = 24_000)
                    }
                    val samples = requireNotNull(resampler).process(chunk.samples)
                    if (samples.isNotEmpty()) {
                        outputBytes += samples.size.toLong() * 2
                        require(outputBytes <= 60L * 1024 * 1024) { "合成音频超过安全上限" }
                        onAudio(floatPcmToPcm16(samples))
                    }
                }
                require(outputBytes > 0) { "语音模型没有生成音频" }
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
        operate(LocalSpeechKind.TTS, record.id) {
            if (record.format == LocalModelFormat.ONNX) return@operate onnx(record, LocalSpeechKind.TTS) { SherpaSpeechRuntime.voices() }
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

    suspend fun transcribe(settings: SpeechSettings, pcm16: ByteArray, onProgress: (String) -> Unit = {}): String =
        measured(settings.localAsrModelId, LocalSpeechKind.ASR) { meter ->
            meter.input(pcm16)
            transcribeNative(settings, pcm16) { text -> if (text.isNotBlank()) meter.result(); onProgress(text) }
        }

    private suspend fun transcribeNative(
        settings: SpeechSettings,
        pcm16: ByteArray,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        if (pcm16.size < 2) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有录到语音，请重试")
        LocalSpeechInputBudget().accept(pcm16)
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localAsrModelId, LocalSpeechKind.ASR)
        operate(LocalSpeechKind.ASR, record.id) {
            if (record.format == LocalModelFormat.ONNX) return@operate onnx(record, LocalSpeechKind.ASR) {
                val frames = speechFrameChannel().also { it.trySend(pcm16); it.close() }
                SherpaSpeechRuntime.transcribe(frames, 0, onProgress)
            }
            val engine = ensureAsr(context, record).also { asr = it }
            val samples = pcm16ToFloat(pcm16)
            val chunkSize = 16_000 // one second at the capture sample rate
            var offset = 0
            var latest = ""
            val transcript = SpeechTranscriptAccumulator()
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.valueOf(engine.activeBackend), "asr-transcribe",
            )
            try {
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    attempt.phase("asr-stream")
                    val end = minOf(offset + chunkSize, samples.size)
                    val result = engine.engine.transcribe(samples.copyOfRange(offset, end), false)
                    val text = transcript.accept(result.segments().map { segment ->
                        SpeechTranscriptSegment(segment.transcript(), segment.isFinal(), segment.fromTimestampMs(), segment.toTimestampMs())
                    })
                    if (text.isNotBlank()) {
                        latest = text
                        onProgress(text)
                    }
                    offset = end
                }
                currentCoroutineContext().ensureActive()
                val final = transcript.accept(engine.engine.finishStream().segments().map { segment ->
                    SpeechTranscriptSegment(segment.transcript(), segment.isFinal(), segment.fromTimestampMs(), segment.toTimestampMs())
                })
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
    suspend fun transcribeStream(settings: SpeechSettings, frames: ReceiveChannel<ByteArray>, endpointSilenceMs: Int,
        onProgress: (String) -> Unit): String = measured(settings.localAsrModelId, LocalSpeechKind.ASR) { meter ->
        transcribeStreamNative(settings, frames, endpointSilenceMs, { text -> if (text.isNotBlank()) meter.result(); onProgress(text) }, meter)
    }

    private suspend fun transcribeStreamNative(
        settings: SpeechSettings,
        frames: ReceiveChannel<ByteArray>,
        endpointSilenceMs: Int,
        onProgress: (String) -> Unit,
        meter: SpeechMetricsMeasurement,
    ): String = withContext(Dispatchers.Default) {
        val context = requireNotNull(appContext) { "本地语音运行时尚未初始化" }
        val record = requireLocal(settings.localAsrModelId, LocalSpeechKind.ASR)
        operate(LocalSpeechKind.ASR, record.id) {
            if (record.format == LocalModelFormat.ONNX) return@operate onnx(record, LocalSpeechKind.ASR) {
                SherpaSpeechRuntime.transcribe(frames, endpointSilenceMs, onProgress, meter::input)
            }
            val engine = ensureAsr(context, record).also { asr = it }
            val startedAt = SystemClock.elapsedRealtime()
            val endpoint = SpeechEndpointTracker(endpointSilenceMs)
            val budget = LocalSpeechInputBudget()
            val transcript = SpeechTranscriptAccumulator()
            var latest = ""
            var acceptedResults = 0L
            var firstResultAtMs: Long? = null
            var endpointReached = false
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.valueOf(engine.activeBackend), "asr-stream-start",
            )
            try {
                for (frame in frames) {
                    budget.accept(frame)
                    meter.input(frame)
                    currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
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

    private suspend fun ensureTts(context: Context, record: LocalModelRecord, settings: SpeechSettings): CachedTts {
        SherpaSpeechRuntime.release(LocalSpeechKind.TTS)
        val requested = record.runtimeSettings.backend
        val cached = tts
        val key = LocalTtsCacheKey(record.id, requested, settings.localTtsRate.coerceIn(0.5f, 2f),
            settings.localTtsVoiceId, settings.localTtsLocale)
        if (cached != null && cached.key == key) return cached
        cached?.engine?.close()
        tts = null
        return createTts(context, record, settings)
    }

    private suspend fun ensureAsr(context: Context, record: LocalModelRecord): CachedAsr {
        SherpaSpeechRuntime.release(LocalSpeechKind.ASR)
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

    private suspend fun createTts(
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
            currentCoroutineContext().ensureActive()
            var created: TextToSpeech? = null
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.TTS, LocalRuntimeBackendPolicy.valueOf(delegate.name), "tts-load-${delegate.name}",
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
                created = engine
                currentCoroutineContext().ensureActive()
                attempt.completed()
                return CachedTts(
                    recordId = record.id,
                    requestedBackend = requested,
                    activeBackend = delegate.name,
                    speakingRate = settings.localTtsRate.coerceIn(0.5f, 2f),
                    voiceId = settings.localTtsVoiceId,
                    locale = settings.localTtsLocale,
                    engine = engine,
                )
            } catch (failure: Throwable) {
                runCatching { created?.close() }
                attempt.failed()
                if (failure is CancellationException) throw failure
                last = failure
            }
        }
        throw SpeechFailure(SpeechErrorCode.AUDIO, "本地播报模型启动失败：${last?.message.orEmpty().take(160)}")
    }

    private suspend fun createAsr(context: Context, record: LocalModelRecord): CachedAsr {
        val file = LocalModelRepository.artifactFile(context, record)
        if (!file.isFile) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别模型文件不存在，请重新下载")
        val requested = record.runtimeSettings.backend
        val safe = LocalSpeechNativeCrashGuard.safePolicy(record, LocalSpeechKind.ASR, requested)
        val delegates = speechDelegates(safe)
        var last: Throwable? = null
        for (delegate in delegates) {
            currentCoroutineContext().ensureActive()
            var created: AudioTranscriber? = null
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(
                record, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.valueOf(delegate.name), "asr-load-${delegate.name}",
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
                created = transcriber
                transcriber.prewarm()
                currentCoroutineContext().ensureActive()
                attempt.completed()
                return CachedAsr(record.id, requested, delegate.name, transcriber)
            } catch (failure: Throwable) {
                runCatching { created?.close() }
                attempt.failed()
                if (failure is CancellationException) throw failure
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
        if (record.format !in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.ONNX)) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请选择完整的 LiteRT-LM 或 ONNX 语音包")
        }
        return record
    }

    private suspend fun <T> onnx(record: LocalModelRecord, kind: LocalSpeechKind, manualStart: Boolean = false, action: suspend () -> T): T {
        val context = requireNotNull(appContext)
        val spec = SpeechModelPackage.inspect(LocalModelRepository.artifactFile(context, record))
            ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "ONNX 语音模型包不完整")
        require(spec.kind == kind) { "语音模型用途不匹配" }
        val engineId = "sherpa-onnx@2.0.1"
        if (manualStart) LocalSpeechNativeCrashGuard.confirmRetry(record, kind, engineId)
        else if (LocalSpeechNativeCrashGuard.hasNativeFailure(record, kind, engineId)) {
            throw SpeechFailure(SpeechErrorCode.AUDIO, "此模型上次发生原生崩溃，已暂停自动运行；可在本地模型中点击启动重试")
        }
        if (kind == LocalSpeechKind.TTS) tts?.engine?.let(::discardTts) else asr?.engine?.let(::discardAsr)
        val attempt = LocalSpeechNativeCrashGuard.beginAttempt(record, kind, LocalRuntimeBackendPolicy.CPU,
            "onnx-${kind.name.lowercase()}", engineId = engineId)
        try {
            if (SherpaSpeechRuntime.recordId(kind) != record.id) updateState(kind, record.id, LocalSpeechRuntimePhase.STARTING, record.runtimeSettings.backend.name)
            SherpaSpeechRuntime.warmUp(record, spec)
            currentCoroutineContext().ensureActive()
            return action().also {
                attempt.completed()
                updateState(kind, record.id, LocalSpeechRuntimePhase.READY, record.runtimeSettings.backend.name, "CPU",
                    if (record.runtimeSettings.backend in setOf(LocalRuntimeBackendPolicy.GPU, LocalRuntimeBackendPolicy.NPU)) "此 ONNX 语音包使用 CPU 后端" else null)
            }
        } catch (failure: Throwable) {
            attempt.failed()
            if (failure is CancellationException) throw failure
            SherpaSpeechRuntime.release(kind, record.id)
            updateState(kind, record.id, LocalSpeechRuntimePhase.FAILED, record.runtimeSettings.backend.name, "CPU", failure.message?.take(180))
            throw failure
        }
    }

    private fun speechDelegates(policy: LocalRuntimeBackendPolicy): List<Delegate> = when (policy) {
        LocalRuntimeBackendPolicy.NPU -> listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)
        LocalRuntimeBackendPolicy.GPU -> listOf(Delegate.GPU, Delegate.CPU)
        LocalRuntimeBackendPolicy.CPU -> listOf(Delegate.CPU)
        LocalRuntimeBackendPolicy.AUTO -> listOf(Delegate.GPU, Delegate.CPU)
    }.distinct()

    private suspend fun <T> measured(modelId: String, kind: LocalSpeechKind, action: suspend (SpeechMetricsMeasurement) -> T): T {
        val meter = SpeechMetricsMeasurement()
        val cached = cachedRecordId(kind) == modelId
        var success = false
        var code: String? = null
        try { return action(meter).also { success = true; if (kind == LocalSpeechKind.ASR && it is String && it.isNotBlank()) meter.result() } }
        catch (failure: Throwable) { code = if (failure is CancellationException) "CANCELLED" else failure.speechFailure().code.name; throw failure }
        finally {
            synchronized(this) {
                val current = mutableStates.value[kind]
                if (current?.recordId == modelId || current?.recordId == null) {
                    val record = LocalModelRepository.record(modelId)
                    val backend = current?.activeBackend?.takeIf(String::isNotBlank)
                        ?: if (record?.format == LocalModelFormat.ONNX) "CPU" else "UNKNOWN"
                    val metrics = meter.finish(modelId, kind, backend,
                        if (record?.format == LocalModelFormat.ONNX) "sherpa-onnx@2.0.1" else "mediapipe-tasks-audio@1.2.2", cached, success, code)
                    val state = current ?: LocalSpeechRuntimeSnapshot(kind, modelId, LocalSpeechRuntimePhase.FAILED, "", backend, null, System.currentTimeMillis())
                    mutableStates.value = mutableStates.value + (kind to state.copy(metrics = metrics))
                }
            }
        }
    }

    @Synchronized private fun updateState(
        kind: LocalSpeechKind,
        recordId: String?,
        phase: LocalSpeechRuntimePhase,
        requestedBackend: String = "",
        activeBackend: String = "",
        message: String? = null,
    ) {
        if (recordId != null && phase in setOf(LocalSpeechRuntimePhase.STARTING, LocalSpeechRuntimePhase.READY) && operations.isStopping(recordId)) return
        mutableStates.value = mutableStates.value.toMutableMap().apply {
            this[kind] = LocalSpeechRuntimeSnapshot(
                kind = kind,
                recordId = recordId,
                phase = phase,
                requestedBackend = requestedBackend,
                activeBackend = activeBackend,
                message = message,
                updatedAt = System.currentTimeMillis(),
                metrics = mutableStates.value[kind]?.metrics?.takeIf { it.modelId == recordId || recordId == null },
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
        val voiceId: String,
        val locale: String,
        val engine: TextToSpeech,
    ) : CachedSpeech {
        val key get() = LocalTtsCacheKey(recordId, requestedBackend, speakingRate, voiceId, locale)
    }

    private data class CachedAsr(
        override val recordId: String,
        override val requestedBackend: LocalRuntimeBackendPolicy,
        override val activeBackend: String,
        val engine: AudioTranscriber,
    ) : CachedSpeech
}

internal data class LocalTtsCacheKey(
    val recordId: String,
    val backend: LocalRuntimeBackendPolicy,
    val speakingRate: Float,
    val voiceId: String,
    val locale: String,
)

internal enum class LocalSpeechKind(val label: String) { TTS("播报"), ASR("识别") }

internal enum class LocalSpeechRuntimePhase { STOPPED, STARTING, READY, STOPPING, FAILED }

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
    val metrics: LocalSpeechMetrics? = null,
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
