package io.github.mangi.eta.agent.voice

import android.content.Context
import java.util.UUID
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

    fun start() {
        if (mutableState.value.active) return
        job?.cancel()
        mutableState.value = VoiceprintEnrollmentState(active = true, progress = "请自然朗读 5 秒")
        job = scope.launch(Dispatchers.IO) {
            val lease = SpeechAudioLease(context) { cancel() }
            val recorder = recorderFactory()
            try {
                lease.acquire(playback = false)
                val pcm = coroutineScope {
                    val capture = async {
                        collectSpeechPcm(recorder.frames)
                    }
                    val recording = launch {
                        recorder.record(onReady = {}, onLevel = {})
                    }
                    delay(5_000)
                    recorder.finish()
                    recording.join()
                    capture.await()
                }
                mutableState.value = mutableState.value.copy(progress = "正在生成声纹")
                val vector = VoiceprintFeatureExtractor.extract(pcm)
                    ?: throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "有效语音不足，请靠近麦克风并连续朗读 5 秒")
                val profile = withContext(Dispatchers.Main.immediate) {
                    VoiceprintProfileStore(context).enroll("voiceprint-${UUID.randomUUID()}", vector)
                } ?: throw SpeechFailure(SpeechErrorCode.STORAGE, "声纹档案保存失败")
                mutableState.value = VoiceprintEnrollmentState(progress = "声纹已录入", completed = true)
                withContext(Dispatchers.Main.immediate) { onProfile(profile) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                val failure = error.speechFailure()
                mutableState.value = VoiceprintEnrollmentState(error = failure.userMessage)
            } finally {
                recorder.cancel()
                lease.close()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = VoiceprintEnrollmentState()
    }
}
