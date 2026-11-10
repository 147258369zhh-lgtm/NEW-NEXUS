package io.github.zhh_lgtm.nexus.application.api

enum class VoiceState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    FAILED,
}

enum class AudioOwner {
    NONE,
    USER,
    ASSISTANT_CAPTURE,
    ASSISTANT_PLAYBACK,
}

sealed interface VoiceTransition {
    data object StartListening : VoiceTransition
    data object SpeechCaptured : VoiceTransition
    data object ResponseStarted : VoiceTransition
    data object StartSpeaking : VoiceTransition
    data object UserInterrupted : VoiceTransition
    data object Completed : VoiceTransition
    data class Failed(val code: String) : VoiceTransition
    data object Reset : VoiceTransition
}

data class VoiceSessionSnapshot(
    val state: VoiceState = VoiceState.IDLE,
    val audioOwner: AudioOwner = AudioOwner.NONE,
    val turnId: String? = null,
    val errorCode: String? = null,
)

object VoiceStateMachine {
    fun transition(current: VoiceSessionSnapshot, transition: VoiceTransition): VoiceSessionSnapshot = when (transition) {
        VoiceTransition.StartListening -> requireState(current, VoiceState.IDLE, VoiceState.LISTENING)
            .copy(audioOwner = AudioOwner.ASSISTANT_CAPTURE, errorCode = null)
        VoiceTransition.SpeechCaptured -> requireState(current, VoiceState.LISTENING, VoiceState.TRANSCRIBING)
            .copy(audioOwner = AudioOwner.NONE)
        VoiceTransition.ResponseStarted -> requireState(current, VoiceState.TRANSCRIBING, VoiceState.THINKING)
        VoiceTransition.StartSpeaking -> requireState(current, VoiceState.THINKING, VoiceState.SPEAKING)
            .copy(audioOwner = AudioOwner.ASSISTANT_PLAYBACK)
        VoiceTransition.UserInterrupted -> {
            if (current.state !in setOf(VoiceState.SPEAKING, VoiceState.THINKING)) {
                error("Voice interruption is not valid from ${current.state}")
            }
            current.copy(state = VoiceState.INTERRUPTED, audioOwner = AudioOwner.USER)
        }
        VoiceTransition.Completed -> current.copy(state = VoiceState.IDLE, audioOwner = AudioOwner.NONE, turnId = null)
        is VoiceTransition.Failed -> current.copy(
            state = VoiceState.FAILED,
            audioOwner = AudioOwner.NONE,
            errorCode = transition.code,
        )
        VoiceTransition.Reset -> VoiceSessionSnapshot()
    }

    private fun requireState(current: VoiceSessionSnapshot, expected: VoiceState, next: VoiceState): VoiceSessionSnapshot {
        require(current.state == expected) { "Invalid voice transition: ${current.state} -> $next" }
        return current.copy(state = next)
    }
}

enum class WorkloadType {
    FOREGROUND_CHAT,
    USER_TASK,
    NIGHTLY_CONSOLIDATION,
    PLUGIN_WARMUP,
    LINUX_IDLE_SERVICE,
}

data class ResourcePressure(
    val lowMemory: Boolean = false,
    val batterySaver: Boolean = false,
    val thermalThrottled: Boolean = false,
)

object ResourcePriorityPolicy {
    private val priority = WorkloadType.entries.withIndex().associate { it.value to it.index }

    fun shouldYield(workload: WorkloadType, higherPriorityWork: WorkloadType): Boolean =
        priority.getValue(workload) > priority.getValue(higherPriorityWork)

    fun mayContinue(workload: WorkloadType, pressure: ResourcePressure): Boolean = when (workload) {
        WorkloadType.FOREGROUND_CHAT -> true
        WorkloadType.USER_TASK -> !(pressure.lowMemory && pressure.thermalThrottled)
        WorkloadType.NIGHTLY_CONSOLIDATION -> !pressure.lowMemory && !pressure.batterySaver
        WorkloadType.PLUGIN_WARMUP -> !pressure.lowMemory && !pressure.batterySaver && !pressure.thermalThrottled
        WorkloadType.LINUX_IDLE_SERVICE -> !pressure.lowMemory && !pressure.batterySaver
    }
}
