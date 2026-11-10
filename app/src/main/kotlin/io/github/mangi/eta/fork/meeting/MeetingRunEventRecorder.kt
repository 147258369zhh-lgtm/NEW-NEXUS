package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Messenger callbacks only enqueue. One IO writer preserves tool/wait ordering within a member run. */
internal class MeetingRunEventRecorder(
    private val runId: String,
    private val persistWait: suspend (AgentEvent.AccessibilityWaiting) -> Unit,
    private val persistTool: suspend (MeetingToolActivity) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
    private val flushTimeoutMs: Long = 10_000,
    private val persistStream: suspend (MeetingStreamActivity) -> Unit = {},
    private val recordStreams: Boolean = true,
    private val streamFlushIntervalMs: Long = 120,
) {
    private val lock = Any()
    private val events = MeetingRunEventMailbox()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    @Volatile private var writesComplete = false
    private val writer = scope.launch {
        val waits = linkedMapOf<Int, AgentEvent.AccessibilityWaiting>()
        val tools = linkedMapOf<String, MeetingToolActivity>()
        val failedWaits = mutableSetOf<Int>()
        val failedTools = mutableSetOf<String>()
        val stream = MeetingStreamReducer(runId)
        val failedStreams = linkedMapOf<String, MeetingStreamActivity>()
        suspend fun persist(block: suspend () -> Unit): Boolean = try {
            block(); true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { runCatching { onFailure(failure) }; false }
        suspend fun saveWait(event: AgentEvent.AccessibilityWaiting) {
            waits[event.outage] = event
            if (persist { persistWait(event) }) failedWaits.remove(event.outage) else failedWaits.add(event.outage)
        }
        suspend fun saveTool(tool: MeetingToolActivity) {
            tools[tool.messageId] = tool
            if (persist { persistTool(tool) }) failedTools.remove(tool.messageId) else failedTools.add(tool.messageId)
        }
        suspend fun flushStreams() {
            stream.drain().forEach { activity ->
                if (persist { persistStream(activity) }) failedStreams.remove(activity.messageId)
                else failedStreams[activity.messageId] = activity
            }
        }
        var lastFlush = System.nanoTime()
        while (true) {
            val waitMs = (streamFlushIntervalMs - (System.nanoTime() - lastFlush) / 1_000_000).coerceAtLeast(1)
            val received = withTimeoutOrNull(waitMs) { events.receiveCatching() }
            if (received == null) { flushStreams(); lastFlush = System.nanoTime(); continue }
            val event = received.getOrNull() ?: break
            if (recordStreams) stream.accept(event)
            val streamEvent = event is AgentEvent.AssistantBlockStart || event is AgentEvent.AssistantBlockDelta ||
                event is AgentEvent.AssistantBlockEnd || event is AgentEvent.AssistantReceived
            // Preserve chronology at tool/wait boundaries. Text deltas are otherwise batched by time.
            if (!streamEvent || (System.nanoTime() - lastFlush) / 1_000_000 >= streamFlushIntervalMs) {
                flushStreams(); lastFlush = System.nanoTime()
            }
            if (event is AgentEvent.AccessibilityWaiting) {
                val previous = waits[event.outage]
                if (previous != event && (previous == null || previous.phase == AccessibilityWaitPhase.WAITING)) saveWait(event)
            } else {
                val initial = MeetingToolActivity.fromEvent(runId, event, null) ?: continue
                val previous = tools[initial.messageId]
                if (previous?.phase?.terminal == true) continue
                if (previous != null && previous.name != initial.name) continue
                val tool = MeetingToolActivity.fromEvent(runId, event, previous)!!.durable()
                if (tool != previous) saveTool(tool)
            }
        }
        stream.finish()
        flushStreams()
        // Missing finishes do not prove successful execution; preserve the partial trace without a spinner.
        tools.values.filter { !it.phase.terminal }.forEach { tool -> saveTool(tool.copy(phase = MeetingToolPhase.INTERRUPTED)) }
        waits.values.filter { it.phase == AccessibilityWaitPhase.WAITING }.forEach { event -> saveWait(event.copy(phase = AccessibilityWaitPhase.CANCELLED)) }
        // A failed terminal write has no later event to repair it. Retry each latest snapshot once,
        // within the same bounded flush, without replaying any tool or inventing a successful commit.
        failedTools.toList().forEach { id -> saveTool(tools.getValue(id)) }
        failedWaits.toList().forEach { outage -> saveWait(waits.getValue(outage)) }
        failedStreams.toMap().forEach { (id, activity) -> if (persist { persistStream(activity) }) failedStreams.remove(id) }
        writesComplete = failedTools.isEmpty() && failedWaits.isEmpty() && failedStreams.isEmpty()
    }

    init { writer.invokeOnCompletion { events.close() } }

    fun accept(event: AgentEvent) {
        if (event !is AgentEvent.AccessibilityWaiting && event !is AgentEvent.ToolStarted &&
            event !is AgentEvent.ToolFinished && event !is AgentEvent.HostedToolStarted && event !is AgentEvent.HostedToolFinished &&
            (!recordStreams || !isStreamEvent(event))) return
        synchronized(lock) { if (!closed) events.trySend(event) }
    }

    private fun isStreamEvent(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart -> event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL
        is AgentEvent.AssistantBlockDelta -> event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL
        is AgentEvent.AssistantBlockEnd -> event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL
        is AgentEvent.AssistantReceived, is AgentEvent.RoundStarted, is AgentEvent.ModelRetryScheduled,
        is AgentEvent.RunFinished, is AgentEvent.RunFailed -> true
        else -> false
    }

    suspend fun finish(): Boolean {
        synchronized(lock) { if (!closed) { closed = true; events.close() } }
        return withContext(NonCancellable) {
            try {
                val joined = withTimeoutOrNull(flushTimeoutMs) { writer.join(); true } ?: false
                if (!joined) runCatching { onFailure(TimeoutException("meeting_trace_flush_timeout")) }
                else if (writer.isCancelled) runCatching { onFailure(IllegalStateException("meeting_trace_writer_cancelled")) }
                joined && !writer.isCancelled && writesComplete
            } finally { scope.cancel() }
        }
    }
}
