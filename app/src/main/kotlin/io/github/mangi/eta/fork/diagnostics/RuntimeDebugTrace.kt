package io.github.mangi.eta.fork.diagnostics

import io.github.mangi.eta.agent.runtime.AgentEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class RuntimeDebugEvent(
    val at: Long,
    val type: String,
    val detail: String,
)

internal data class RuntimeDebugSnapshot(
    val serviceConnected: Boolean = false,
    val runtimeRunCount: Int? = null,
    val activeMeetings: Int = 0,
    val connectionCount: Long = 0,
    val bindingFailureCount: Long = 0,
    val backgroundRunCount: Long = 0,
    val timeoutCount: Long = 0,
    val taskFailureCount: Long = 0,
    val agentToolCallCount: Long = 0,
    val agentFailedToolCount: Long = 0,
    val agentInputTokens: Long = 0,
    val agentOutputTokens: Long = 0,
    val recentEvents: List<RuntimeDebugEvent> = emptyList(),
)

/** Process-local observability for debug builds; never contains prompts, credentials or tool payloads. */
internal object RuntimeDebugTrace {
    private const val MAX_EVENTS = 18
    private val mutableSnapshot = MutableStateFlow(RuntimeDebugSnapshot())
    val snapshot: StateFlow<RuntimeDebugSnapshot> = mutableSnapshot.asStateFlow()

    fun serviceConnected(elapsedMillis: Long) = record(
        type = "runtime_connected",
        detail = "bind ${elapsedMillis}ms",
    ) {
        copy(
            serviceConnected = true,
            connectionCount = connectionCount + 1,
        )
    }

    fun serviceDisconnected() = record("runtime_disconnected", "service disconnected") {
        copy(serviceConnected = false)
    }

    fun bindingFailed(reason: String) = record("runtime_bind_failed", reason) {
        copy(
            serviceConnected = false,
            bindingFailureCount = bindingFailureCount + 1,
        )
    }

    fun activeRuntimeRuns(count: Int) = mutableSnapshot.update { it.copy(runtimeRunCount = count) }

    fun meetingStarted(roomId: String) = record("meeting_started", roomId.take(80)) {
        copy(
            activeMeetings = activeMeetings + 1,
            backgroundRunCount = backgroundRunCount + 1,
        )
    }

    fun meetingFinished(roomId: String) = record("meeting_finished", roomId.take(80)) {
        copy(activeMeetings = (activeMeetings - 1).coerceAtLeast(0))
    }

    fun meetingTimedOut(roomId: String) = record("meeting_timeout", roomId.take(80)) {
        copy(timeoutCount = timeoutCount + 1)
    }

    fun taskTick(executedRuns: Int, queuedRuns: Int) = record(
        type = "task_tick",
        detail = "executed=$executedRuns queued=$queuedRuns",
    ) {
        copy(backgroundRunCount = backgroundRunCount + if (executedRuns > 0) 1 else 0)
    }

    fun taskFailure(type: String) = record("task_failure", type.take(120)) {
        copy(taskFailureCount = taskFailureCount + 1)
    }

    fun agentEvent(event: AgentEvent) {
        mutableSnapshot.update { current ->
            when (event) {
                is AgentEvent.ToolStarted -> current.copy(agentToolCallCount = current.agentToolCallCount + 1)
                is AgentEvent.ToolFinished -> current.copy(
                    agentFailedToolCount = current.agentFailedToolCount + if (event.success == false) 1 else 0,
                )
                is AgentEvent.UsageReceived -> current.copy(
                    agentInputTokens = current.agentInputTokens + (event.usage.inputTokens ?: 0),
                    agentOutputTokens = current.agentOutputTokens + (event.usage.outputTokens ?: 0),
                )
                else -> current
            }
        }
    }

    private inline fun record(
        type: String,
        detail: String,
        transform: RuntimeDebugSnapshot.() -> RuntimeDebugSnapshot,
    ) {
        mutableSnapshot.update { current ->
            current.transform().copy(
                recentEvents = (listOf(RuntimeDebugEvent(System.currentTimeMillis(), type, detail)) +
                    current.recentEvents).take(MAX_EVENTS),
            )
        }
    }

    fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DEBUG_TIME_FORMAT)

    private val DEBUG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
}
