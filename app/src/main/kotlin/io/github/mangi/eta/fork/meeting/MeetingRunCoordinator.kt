package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** A single room owner drains follow-ups; ownership never drops between two accepted turns. */
internal object MeetingRunCoordinator {
    private val scheduledRooms = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val scheduler = MeetingTurnScheduler(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        acquire = { context, id, stop -> AgentExecutionService.acquire(context, id, onStop = stop) },
        release = AgentExecutionService::release,
        execute = { context, room, turn -> MeetingRuntime.runTurn(context, room, turn.text, turn.attachments, userMessageId = turn.id) },
        retain = { context, room, turns -> MeetingRepository.retainUnstartedTurns(context, room, turns) },
    )
    /** Live ownership only; a persisted running status may outlive a stopped process. */
    val activeRoomIds = combine(scheduledRooms, scheduler.activeRoomIds) { scheduled, manual ->
        scheduled.keys + manual
    }.distinctUntilChanged()
    suspend fun start(context: Context, roomId: String, rawText: String, attachments: ConversationAttachments = ConversationAttachments()) =
        MeetingRepository.lifecycleMutex.withLock { accept(context, roomId, rawText, attachments, enqueueOnly = false) }
    suspend fun enqueue(context: Context, roomId: String, rawText: String, attachments: ConversationAttachments = ConversationAttachments()) =
        MeetingRepository.lifecycleMutex.withLock { accept(context, roomId, rawText, attachments, enqueueOnly = true) }

    /** Restore one tool result, never reinterpret an answer as a fresh room-wide assignment. */
    suspend fun resumeInput(context: Context, input: io.github.mangi.eta.agent.runtime.RunInputRequest, answer: String): Boolean =
        MeetingRepository.lifecycleMutex.withLock {
            val roomId = input.targetId ?: return@withLock false
            if (isRunning(roomId)) return@withLock false
            val snapshot = MeetingRepository.snapshot(context, roomId).first() ?: return@withLock false
            val participant = snapshot.participants.firstOrNull {
                it.enabled && if (input.actorId.isNullOrBlank()) it.isHost else it.profileName == input.actorId
            } ?: return@withLock false
            require(participant.providerId?.startsWith("a2a:") != true) { "远端协议续答状态不可恢复，未启动远端重放" }
            val history = MeetingRepository.participantHistory(context, roomId, participant.profileName, snapshot.messages, participant.isHost)
            val prepared = io.github.mangi.eta.agent.runtime.RecoveredRunInputStore(context).use { it.prepare(context, input, answer, history) }
            val label = if (input.part.questions.isEmpty()) answer else io.github.mangi.eta.agent.runtime.RunInputForms.summary(
                input.part.questions, io.github.mangi.eta.agent.runtime.RunInputForms.answer(input.part.questions, answer))
            MeetingRepository.appendMessage(context, roomId, MeetingAuthorType.USER, null, "You",
                (if (prepared.exact) "补充回答（原工具结果）\n" else "补充回答（旧调用无法恢复；本轮仅核对和整理，不重放操作）\n") + label,
                messageId = "user-${prepared.runId}")
            val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
                val lease = "meeting-input:${prepared.runId}"
                var acquired = false
                try {
                    acquired = AgentExecutionService.acquire(context.applicationContext, lease, onStop = { this.cancel() })
                    if (acquired) MeetingRuntime.resumeInput(context.applicationContext, participant, prepared)
                } finally {
                    if (acquired) AgentExecutionService.release(lease)
                }
            }
            job.invokeOnCompletion {
                scheduledRooms.update { rooms -> if (rooms[roomId] === job) rooms - roomId else rooms }
            }
            scheduledRooms.update { it + (roomId to job) }
            job.start()
            true
        }

    private suspend fun accept(context: Context, roomId: String, text: String, attachments: ConversationAttachments, enqueueOnly: Boolean): Boolean {
        if (roomId.isBlank() || text.isBlank() && attachments.isEmpty || scheduledRooms.value.containsKey(roomId) || enqueueOnly && !isRunning(roomId)) return false
        val turn = QueuedMeetingTurn(text.trim(), attachments)
        var accepted = false
        try {
            val owned = withContext(Dispatchers.IO) { MeetingRepository.persistPendingTurn(context, roomId, turn) }
            accepted = if (enqueueOnly) scheduler.enqueue(roomId, owned.text, owned.attachments, owned.id)
                else scheduler.start(context, roomId, owned.text, owned.attachments, owned.id)
            return accepted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AndroidAgentLogger.warn("Meeting input persistence failed: type=${failure.safeLogType()}")
            return false
        } finally {
            if (!accepted) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { MeetingRepository.forgetPendingTurn(context, roomId, turn.id) }
            }
        }
    }
    fun queuedCount(roomId: String) = scheduler.queuedCount(roomId)
    fun stop(roomId: String): Boolean {
        scheduledRooms.value[roomId]?.let { it.cancel(); return true }
        return scheduler.stop(roomId)
    }
    fun isRunning(roomId: String) = scheduledRooms.value.containsKey(roomId) || scheduler.isRunning(roomId)

    /** Scheduled and manual turns share admission, recovery visibility and foreground ownership. */
    suspend fun runScheduled(
        context: Context,
        roomId: String,
        rawText: String,
        toolPolicy: AgentToolPolicy,
        runtime: MeetingParticipantRuntime = DefaultMeetingParticipantRuntime,
    ): MeetingTurnOutcome = coroutineScope {
        val owner = currentCoroutineContext().job
        val leaseId = "meeting-run:$roomId"
        var admitted = false
        var acquired = false
        try {
            admitted = MeetingRepository.lifecycleMutex.withLock {
                if (roomId.isBlank() || rawText.isBlank() || isRunning(roomId)) false
                else { scheduledRooms.update { it + (roomId to owner) }; true }
            }
            if (!admitted) return@coroutineScope MeetingTurnOutcome.REJECTED
            acquired = AgentExecutionService.acquire(context.applicationContext, leaseId, onStop = { owner.cancel() })
            if (!acquired) return@coroutineScope MeetingTurnOutcome.REJECTED
            ensureActive()
            MeetingRuntime.runTurnOutcome(context.applicationContext, roomId, rawText, toolPolicy = toolPolicy, runtime = runtime)
        } finally {
            if (admitted) {
                try { if (acquired) AgentExecutionService.release(leaseId) }
                finally { scheduledRooms.update { rooms -> if (rooms[roomId] === owner) rooms - roomId else rooms } }
            }
        }
    }
}

internal data class QueuedMeetingTurn(val text: String, val attachments: ConversationAttachments,
    val id: String = java.util.UUID.randomUUID().toString())

internal class MeetingTurnScheduler(
    private val scope: CoroutineScope,
    private val acquire: (Context, String, () -> Unit) -> Boolean,
    private val release: (String) -> Unit,
    private val execute: suspend (Context, String, QueuedMeetingTurn) -> Boolean,
    private val retain: suspend (Context, String, List<QueuedMeetingTurn>) -> Unit,
) {
    private class Room(val initial: QueuedMeetingTurn) {
        val pending = ArrayDeque<QueuedMeetingTurn>()
        var accepting = true
        var stopped = false
        var current: QueuedMeetingTurn? = initial
        var job: Job? = null
    }
    private val lock = Any()
    private val rooms = mutableMapOf<String, Room>()
    private val mutableActiveRoomIds = MutableStateFlow<Set<String>>(emptySet())
    val activeRoomIds: StateFlow<Set<String>> = mutableActiveRoomIds.asStateFlow()

    fun start(context: Context, roomId: String, rawText: String, attachments: ConversationAttachments,
        turnId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val turn = QueuedMeetingTurn(rawText.trim(), attachments, turnId)
        if (roomId.isBlank() || turn.text.isEmpty() && attachments.isEmpty) return false
        val appContext = context.applicationContext
        synchronized(lock) {
            if (roomId in rooms) return false
            val room = Room(turn)
            rooms[roomId] = room
            val leaseId = "meeting-run:$roomId"
            val acquired = try { acquire(appContext, leaseId) { stop(roomId) } } catch (failure: Exception) {
                AndroidAgentLogger.warn("Meeting admission failed: type=${failure.safeLogType()}")
                false
            }
            if (!acquired) { rooms.remove(roomId); return false }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                var next: QueuedMeetingTurn? = synchronized(lock) {
                    if (room.stopped) null else room.initial
                }
                while (next != null) {
                    RuntimeDebugTrace.meetingStarted(roomId)
                    val completed = try { execute(appContext, roomId, next!!) } finally {
                        RuntimeDebugTrace.meetingFinished(roomId)
                    }
                    next = synchronized(lock) {
                        if (completed) room.current = null
                        if (!completed) room.stopped = true
                        if (room.stopped || room.pending.isEmpty()) {
                            room.accepting = false
                            null
                        } else room.pending.removeFirst().also { room.current = it }
                    }
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.error("Meeting turn failed: type=${failure.safeLogType()}")
                }
            }
            room.job = job
            job.invokeOnCompletion {
                val unstarted = synchronized(lock) {
                    mutableActiveRoomIds.value = mutableActiveRoomIds.value - roomId
                    room.accepting = false
                    buildList {
                        room.current?.let(::add)
                        addAll(room.pending)
                        room.pending.clear()
                    }
                }
                // Also runs when cancellation wins before a LAZY coroutine has entered its body.
                scope.launch(NonCancellable + Dispatchers.IO) {
                    try {
                        if (unstarted.isNotEmpty()) retain(appContext, roomId, unstarted)
                    } catch (failure: Exception) {
                        AndroidAgentLogger.error("Meeting pending input retention failed: type=${failure.safeLogType()}")
                    } finally {
                        synchronized(lock) {
                            release(leaseId)
                            rooms.remove(roomId, room)
                        }
                    }
                }
            }
            if (room.stopped) job.cancel() else {
                mutableActiveRoomIds.value = mutableActiveRoomIds.value + roomId
                job.start()
            }
            return true
        }
    }

    fun enqueue(roomId: String, rawText: String, attachments: ConversationAttachments,
        turnId: String = java.util.UUID.randomUUID().toString()): Boolean = synchronized(lock) {
        val text = rawText.trim()
        val room = rooms[roomId] ?: return false
        if (!room.accepting || room.stopped || text.isEmpty() && attachments.isEmpty || room.pending.size >= 8) return false
        room.pending.addLast(QueuedMeetingTurn(text, attachments, turnId))
        true
    }

    fun queuedCount(roomId: String): Int = synchronized(lock) { rooms[roomId]?.pending?.size ?: 0 }
    fun isRunning(roomId: String): Boolean = synchronized(lock) { roomId in rooms }
    fun stop(roomId: String): Boolean = synchronized(lock) {
        val room = rooms[roomId] ?: return false
        if (room.stopped) return false
        room.stopped = true
        room.accepting = false
        room.job?.cancel()
        true
    }
}
