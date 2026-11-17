package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.supportsLiveRecognition
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val waveform: FloatArray = FloatArray(WAVEFORM_SAMPLES),
    val progress: String = "",
    val error: String? = null,
    val configureAvailable: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE

    companion object {
        const val WAVEFORM_SAMPLES = 128
    }
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(context = context),
    private val createRecorder: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val captureSpeakerFeatures: Boolean = false,
    private val onSpeakerFeatures: (ByteArray) -> Unit = {},
    private val onSpeakerIdentity: (String) -> Unit = {},
    private val duplexAudioGroup: Any? = null,
    private val recognizeStream: suspend (
        SpeechSettings,
        SpeechCredentials,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = ::recognizeSpeechStream,
    private val onPreview: (String) -> Unit = {},
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var job: Job? = null
    private var recorder: SpeechAudioCapture? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    /**
     * [holdToTalk] 表示用户按住说话：松手才结束，期间的停顿不会自动发送。
     * 停顿自动发送只对构造时声明 [automaticEndpoint] 的入口生效，聊天听写始终等待用户确认。
     */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false,
        capturedAudio: SpeechAudioCapture? = null) {
        cancel()
        recorder = capturedAudio
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接")
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val config = settings ?: SpeechSettingsRepository.settings()
                if (!config.recognitionEnabled) return@launch
                lease = SpeechAudioLease(context, duplexGroup = duplexAudioGroup) { cancel() }.also { it.acquire(playback = false) }
                val secrets = credentials ?: SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = false)
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk && config.asr.supportsLiveRecognition()) config.autoSendSilenceMs else 0
                val audio = capturedAudio ?: createRecorder()
                recorder = audio
                fun update(transform: (SpeechInputState) -> SpeechInputState) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == session) {
                            val previous = mutableState.value
                            val next = transform(previous)
                            mutableState.value = next
                            if (next.preview != previous.preview && next.preview.isNotBlank()) onPreview(next.preview)
                        }
                    }
                }
                val capturedPcm = ByteArrayOutputStream()
                // The recorder already enforces the 60-second PCM limit. Keep the hand-off
                // lossless while local inference is slower than real time so the capture loop
                // never turns transient ASR latency into a BACKPRESSURE failure.
                val recognitionFrames = speechFrameChannel()
                val text = coroutineScope {
                    val frameTap = launch(Dispatchers.IO) {
                        try {
                            for (frame in audio.frames) {
                                if (generation != session) return@launch
                                if (captureSpeakerFeatures && capturedPcm.size() + frame.size <= SpeechRecorder.MAX_BYTES) {
                                    capturedPcm.write(frame)
                                }
                                recognitionFrames.send(frame)
                            }
                            recognitionFrames.close()
                        } catch (error: Throwable) {
                            recognitionFrames.close(error)
                            throw error
                        }
                    }
                    var recording: Job? = null
                    val begin = {
                        if (generation == session && recording == null) {
                            beforeCapture()
                            capturing = true
                            recording = launch {
                                try {
                                    audio.record(
                                        onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束") } },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                        onWaveform = { waveform -> update { it.copy(waveform = waveform) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, waveform = FloatArray(SpeechInputState.WAVEFORM_SAMPLES), progress = "正在识别") }
                            }
                        }
                        Unit
                    }
                    try {
                        if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO || config.asr == AsrProvider.IFLYTEK) {
                            recognizeStream(
                                config,
                                secrets,
                                recognitionFrames,
                                begin,
                                { preview -> update { it.copy(preview = preview) } },
                                { if (generation == session) audio.finish() },
                                endpointSilenceMs,
                            )
                        } else {
                            begin()
                            if (config.asr == AsrProvider.LOCAL) {
                                withTimeout(65_000) {
                                    LocalSpeechRuntime.transcribeStream(
                                        settings = config,
                                        frames = recognitionFrames,
                                        endpointSilenceMs = endpointSilenceMs,
                                        onProgress = { preview ->
                                            update { it.copy(preview = preview) }
                                        },
                                    )
                                }
                            } else {
                                val wav = withTimeout(65_000) { collectSpeechAudio(recognitionFrames) }
                                recognizeSpeechFile(context, config, secrets, wav) { progress ->
                                    update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                                }
                            }
                        }
                    } finally {
                        audio.cancel()
                        frameTap.cancel()
                        recognitionFrames.cancel()
                        recording?.cancel()
                    }
                }
                if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                if (captureSpeakerFeatures && WakeWordStore(context).read().voiceprintEnabled) {
                    LocalSpeakerModel.extract(context, capturedPcm.toByteArray())?.let { features ->
                        onSpeakerIdentity(features.modelIdentity); onSpeakerFeatures(features.bytes)
                    }
                }
                if (generation == session) onPreview(text)
                val final = refined(session, config.refineTranscript, text)
                if (generation == session) {
                    cancel()
                    onResult(final)
                }
            } catch (error: Throwable) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Nexus speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS ||
                            failure.code == SpeechErrorCode.AUTHENTICATION,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    /** 纠错失败不影响交付；只有纠错仍属于当前会话时才展示进度。 */
    private suspend fun refined(session: Long, refineTranscript: Boolean, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING, preview = text, progress = "正在校对", level = 0f,
        )
        return try {
            refiner.refine(text)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            AndroidAgentLogger.warn("Nexus speech refine skipped: code=${failure.code} type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        val oldRecorder = recorder; recorder = null
        val oldJob = job; job = null
        val oldLease = lease; lease = null
        try {
            runCatching { oldRecorder?.cancel() }
            runCatching { releaseCapture() }
            oldJob?.cancel()
        } finally {
            runCatching { oldLease?.close() }
            mutableState.value = SpeechInputState()
        }
    }

    private fun releaseCapture() {
        if (!capturing) return
        capturing = false
        afterCapture()
    }
}
