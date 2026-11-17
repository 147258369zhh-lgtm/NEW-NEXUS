package io.github.mangi.eta.agent.voice

import com.k2fsa.sherpa.onnx.*
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.LocalModelRecord
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.ByteArrayOutputStream

/** Called only while LocalSpeechRuntime holds the corresponding ASR/TTS ownership lock. */
internal object SherpaSpeechRuntime {
    private data class Asr(val id: String, val threads: Int, val spec: SpeechModelSpec, val online: OnlineRecognizer?, val offline: OfflineRecognizer?)
    private data class Tts(val id: String, val threads: Int, val spec: SpeechModelSpec, val engine: OfflineTts)
    @Volatile private var asr: Asr? = null
    @Volatile private var tts: Tts? = null
    fun recordId(kind: LocalSpeechKind) = if (kind == LocalSpeechKind.TTS) tts?.id else asr?.id

    fun warmUp(record: LocalModelRecord, spec: SpeechModelSpec) {
        val threads = record.runtimeSettings.threads.coerceIn(1, 8)
        if (spec.kind == LocalSpeechKind.TTS) {
            if (tts?.id == record.id && tts?.threads == threads) return
            release(LocalSpeechKind.TTS)
            tts = Tts(record.id, threads, spec, OfflineTts(config = ttsConfig(spec, threads)))
        } else {
            if (asr?.id == record.id && asr?.threads == threads) return
            release(LocalSpeechKind.ASR)
            if (spec.architecture.endsWith("-streaming")) asr = Asr(record.id, threads, spec, OnlineRecognizer(config = onlineConfig(spec, threads)), null)
            else asr = Asr(record.id, threads, spec, null, OfflineRecognizer(config = offlineConfig(spec, threads)))
        }
    }

    fun release(kind: LocalSpeechKind, recordId: String? = null) {
        if (kind == LocalSpeechKind.TTS) tts?.takeIf { recordId == null || it.id == recordId }?.let { tts = null; it.engine.release() }
        else asr?.takeIf { recordId == null || it.id == recordId }?.let { asr = null; it.online?.release(); it.offline?.release() }
    }

    fun voices(): List<LocalSpeechVoiceInfo> {
        val cached = checkNotNull(tts)
        return (0 until cached.engine.numSpeakers().coerceIn(1, 1024)).map { id ->
            LocalSpeechVoiceInfo(id.toString(), cached.spec.voices.getOrNull(id) ?: "音色 ${id + 1}",
                cached.spec.language.ifBlank { cached.engine.lang() }, cached.engine.sampleRate(), true)
        }
    }

    suspend fun synthesize(settings: SpeechSettings, text: String, onAudio: (ByteArray) -> Unit) {
        val cached = checkNotNull(tts)
        val count = cached.engine.numSpeakers().coerceAtLeast(1)
        val speaker = settings.localTtsVoiceId.toIntOrNull() ?: cached.spec.voices.indexOf(settings.localTtsVoiceId).coerceAtLeast(0)
        require(speaker in 0 until count) { "所选音色不存在，请重新选择" }
        val job = currentCoroutineContext().job
        val resampler = StreamingPcmResampler(cached.engine.sampleRate(), 24_000)
        var failure: Throwable? = null
        var bytes = 0L
        cached.engine.generateWithCallback(text, speaker, settings.localTtsRate.coerceIn(.5f, 2f)) { samples ->
            if (!job.isActive) 0 else try {
                val pcm = floatPcmToPcm16(resampler.process(samples))
                bytes += pcm.size
                require(bytes <= 60 * 1024 * 1024) { "合成音频超过安全上限" }
                if (pcm.isNotEmpty()) onAudio(pcm)
                1
            } catch (error: Throwable) { failure = error; 0 }
        }
        failure?.let { throw it }
        currentCoroutineContext().ensureActive()
        require(bytes > 0) { "语音模型没有生成音频" }
    }

    suspend fun transcribe(frames: ReceiveChannel<ByteArray>, silenceMs: Int, progress: (String) -> Unit, onFrame: (ByteArray) -> Unit = {}): String {
        val cached = checkNotNull(asr)
        val endpoint = SpeechEndpointTracker(silenceMs)
        val budget = LocalSpeechInputBudget()
        val online = cached.online
        if (online == null) {
            val pcm = ByteArrayOutputStream()
            for (frame in frames) {
                budget.accept(frame)
                onFrame(frame)
                currentCoroutineContext().ensureActive()
                require(pcm.size() + frame.size <= SpeechRecorder.MAX_BYTES) { "录音超过长度限制" }
                pcm.write(frame); endpoint.consume(frame)
                if (endpoint.voicedFrameCount > 0) endpoint.markTranscript()
                if (silenceMs > 0 && endpoint.reachedEndpoint()) break
            }
            currentCoroutineContext().ensureActive()
            val offline = checkNotNull(cached.offline)
            val stream = offline.createStream()
            return try {
                stream.acceptWaveform(pcm16ToFloat(pcm.toByteArray()), SpeechRecorder.RATE)
                offline.decode(stream)
                currentCoroutineContext().ensureActive()
                clean(offline.getResult(stream).text).also(progress)
            } finally { stream.release() }
        }
        val stream = online.createStream()
        val completed = mutableListOf<String>()
        suspend fun decode() { while (online.isReady(stream)) { currentCoroutineContext().ensureActive(); online.decode(stream) } }
        fun transcript() = (completed + clean(online.getResult(stream).text)).filter(String::isNotBlank).joinToString(" ")
        try {
            for (frame in frames) {
                budget.accept(frame)
                onFrame(frame)
                currentCoroutineContext().ensureActive()
                endpoint.consume(frame)
                stream.acceptWaveform(pcm16ToFloat(frame), SpeechRecorder.RATE); decode()
                val text = transcript()
                if (text.isNotBlank()) { endpoint.markTranscript(); progress(text) }
                if (silenceMs > 0 && endpoint.reachedEndpoint()) break
                if (online.isEndpoint(stream)) {
                    clean(online.getResult(stream).text).takeIf(String::isNotBlank)?.let(completed::add)
                    online.reset(stream)
                }
            }
            currentCoroutineContext().ensureActive()
            stream.acceptWaveform(FloatArray(8_000), SpeechRecorder.RATE)
            stream.inputFinished(); decode()
            return transcript().also(progress)
        } finally { stream.release() }
    }

    internal fun onlineConfig(spec: SpeechModelSpec, threads: Int) = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
        transducer = if (spec.architecture == "zipformer-streaming") OnlineTransducerModelConfig(spec.path("encoder"), spec.path("decoder"), spec.path("joiner")) else OnlineTransducerModelConfig(),
        paraformer = if (spec.architecture == "paraformer-streaming") OnlineParaformerModelConfig(spec.path("encoder"), spec.path("decoder")) else OnlineParaformerModelConfig(),
        tokens = spec.path("tokens"), numThreads = threads, provider = "cpu"))

    internal fun offlineConfig(spec: SpeechModelSpec, threads: Int) = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        whisper = if (spec.architecture == "whisper") OfflineWhisperModelConfig(spec.path("encoder"), spec.path("decoder"), language = spec.language) else OfflineWhisperModelConfig(),
        senseVoice = if (spec.architecture == "sensevoice") OfflineSenseVoiceModelConfig(model = spec.path("model"), language = spec.language.ifBlank { "auto" }, useInverseTextNormalization = true) else OfflineSenseVoiceModelConfig(),
        paraformer = if (spec.architecture == "paraformer") OfflineParaformerModelConfig(model = spec.path("model")) else OfflineParaformerModelConfig(),
        tokens = spec.path("tokens"), numThreads = threads, provider = "cpu"))

    internal fun ttsConfig(spec: SpeechModelSpec, threads: Int) = OfflineTtsConfig(model = OfflineTtsModelConfig(
        vits = if (spec.architecture == "vits") OfflineTtsVitsModelConfig(model = spec.path("model"), lexicon = spec.path("lexicon"), tokens = spec.path("tokens"), dataDir = spec.path("data_dir"), dictDir = spec.path("dict_dir")) else OfflineTtsVitsModelConfig(),
        kokoro = if (spec.architecture == "kokoro") OfflineTtsKokoroModelConfig(model = spec.path("model"), voices = spec.path("voices"), tokens = spec.path("tokens"), lexicon = spec.path("lexicon"), lang = spec.language, dataDir = spec.path("data_dir"), dictDir = spec.path("dict_dir")) else OfflineTtsKokoroModelConfig(),
        matcha = if (spec.architecture == "matcha") OfflineTtsMatchaModelConfig(acousticModel = spec.path("model"), vocoder = spec.path("vocoder"), lexicon = spec.path("lexicon"), tokens = spec.path("tokens"), dataDir = spec.path("data_dir"), dictDir = spec.path("dict_dir")) else OfflineTtsMatchaModelConfig(),
        numThreads = threads, provider = "cpu"), ruleFsts = spec.path("rule_fsts"), ruleFars = spec.path("rule_fars"))

    private fun clean(text: String) = text.replace(Regex("<\\|[^|]+\\|>"), "").trim()
}
