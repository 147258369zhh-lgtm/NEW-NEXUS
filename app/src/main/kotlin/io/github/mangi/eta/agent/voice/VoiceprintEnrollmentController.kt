package io.github.mangi.eta.agent.voice

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class VoiceprintEnrollmentState(
    val active: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val completed: Boolean = false,
)

/** Captures a five-second sample, extracts a bounded speaker vector, and discards raw audio. */
internal class VoiceprintEnrollmentController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recorderFactory: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val onProfile: (VoiceprintProfile) -> Unit,
) {
    private val mutableState = MutableStateFlow(VoiceprintEnrollmentState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var recorder: SpeechAudioCapture? = null
    private val generation = AtomicLong()

    fun start() {
        if (mutableState.value.active) return
        job?.cancel()
        val session = generation.incrementAndGet()
        mutableState.value = VoiceprintEnrollmentState(active = true, progress = "请自然朗读 5 秒")
        job = scope.launch(Dispatchers.IO) {
            val lease = SpeechAudioLease(context) { cancel() }
            val audio = recorderFactory(); recorder = audio
            try {
                lease.acquire(playback = false)
                val pcm = coroutineScope {
                    val capture = async {
                        collectSpeechPcm(audio.frames)
                    }
                    val ready = CompletableDeferred<Unit>()
                    val recording = launch {
                        audio.record(onReady = { ready.complete(Unit) }, onLevel = {})
                    }
                    withTimeout(8_000) { ready.await() }
                    delay(5_000)
                    audio.finish()
                    withTimeout(3_000) { recording.join() }
                    capture.await()
                }
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    if (session != generation.get()) throw CancellationException("录入已取消")
                    mutableState.value = mutableState.value.copy(progress = "正在生成声纹")
                }
                val vector = LocalSpeakerModel.extract(context, pcm, manualRetry = true)
                    ?: throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "有效语音不足，请靠近麦克风并连续朗读 5 秒")
                val profile = VoiceprintProfileStore(context).access {
                    currentCoroutineContext().ensureActive()
                    if (session != generation.get()) throw CancellationException("录入已取消")
                    // Once accepted, finish this write on IO. A newer capture cannot overtake it;
                    // cancellation invalidates callbacks without blocking Main on storage.
                    enroll("voiceprint-${UUID.randomUUID()}", vector.bytes, vector.modelIdentity)
                } ?: throw SpeechFailure(SpeechErrorCode.STORAGE, "声纹档案保存失败")
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    if (session != generation.get()) throw CancellationException("录入已取消")
                    mutableState.value = VoiceprintEnrollmentState(progress = "声纹已录入", completed = true)
                    onProfile(profile)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                val failure = error.speechFailure()
                withContext(Dispatchers.Main.immediate) {
                    if (session == generation.get()) {
                        mutableState.value = VoiceprintEnrollmentState(error = failure.userMessage)
                    }
                }
            } finally {
                audio.cancel()
                if (recorder === audio) recorder = null
                lease.close()
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        recorder?.cancel(); recorder = null
        job?.cancel()
        job = null
        mutableState.value = VoiceprintEnrollmentState()
    }
}
