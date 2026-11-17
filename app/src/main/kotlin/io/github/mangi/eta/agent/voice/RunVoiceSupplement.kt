package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RunVoiceSupplementState(val runId: String? = null, val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "", val message: String? = null) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

/** Small state projection; capture and foreground ownership remain in the existing assistant service. */
internal object RunVoiceSupplement {
    const val START = "nexus.voice.RUN_SUPPLEMENT"
    const val FINISH = "nexus.voice.FINISH_SUPPLEMENT"
    const val CANCEL = "nexus.voice.CANCEL_SUPPLEMENT"
    const val RUN_ID = "target_run_id"
    private val mutableState = MutableStateFlow(RunVoiceSupplementState())
    val state = mutableState.asStateFlow()
    fun begin(id: String) { mutableState.value = RunVoiceSupplementState(id, EtaSpeechPhase.STARTING) }
    fun start(context: Context, id: String) = command(context, START, id)
    fun finish(context: Context, id: String) = command(context, FINISH, id)
    fun cancel(context: Context, id: String) = command(context, CANCEL, id)
    private fun command(context: Context, action: String, id: String) {
        if (id.isBlank()) return
        if (action == START) begin(id)
        try { context.startService(Intent(context, EtaAssistantOverlayService::class.java).setAction(action).putExtra(RUN_ID, id)) }
        catch (_: RuntimeException) { mutableState.value = RunVoiceSupplementState(id, message = "无法启动语音，请检查麦克风权限") }
    }
    fun publish(id: String, input: SpeechInputState) {
        if (mutableState.value.runId == id) mutableState.value = RunVoiceSupplementState(id, input.phase,
            input.preview.ifBlank { mutableState.value.preview }, input.error)
    }
    fun completed(id: String, message: String, preview: String? = null) {
        if (mutableState.value.runId == id) mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.IDLE, message = message,
            preview = preview ?: mutableState.value.preview)
    }
}
