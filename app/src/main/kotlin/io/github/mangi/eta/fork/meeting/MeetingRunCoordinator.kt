package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns meeting turns for the application lifetime. A room turn must not depend on the
 * composition that submitted it, otherwise navigating away can cancel model requests and
 * leave the persisted room stuck in RUNNING.
 */
internal object MeetingRunCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roomAdmissions = ConcurrentHashMap<String, CompletableJob>()
    private val roomJobs = ConcurrentHashMap<String, Job>()
    private val queuedPrompts = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<QueuedTurn>>()
    private data class QueuedTurn(val text: String, val attachments: io.github.mangi.eta.data.repository.ConversationAttachments)

    /**
     * Returns false only when this request was not admitted. Once true is returned the turn owns
     * an application-level job and a foreground execution lease until it settles or is stopped.
     */
    fun start(context: Context, roomId: String, rawText: String, attachments: io.github.mangi.eta.data.repository.ConversationAttachments = io.github.mangi.eta.data.repository.ConversationAttachments()): Boolean {
        val text = rawText.trim()
        if (roomId.isBlank() || (text.isEmpty() && attachments.isEmpty)) return false

        val admission = Job(scope.coroutineContext[Job])
        if (roomAdmissions.putIfAbsent(roomId, admission) != null) return false

        val appContext = context.applicationContext
        val leaseId = "meeting-run:$roomId"
        val jobRef = AtomicReference<Job?>()
        if (!AgentExecutionService.acquire(appContext, leaseId, onStop = { jobRef.get()?.cancel() })) {
            roomAdmissions.remove(roomId, admission)
            admission.complete()
            return false
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            RuntimeDebugTrace.meetingStarted(roomId)
            try {
                MeetingRuntime.runTurn(appContext, roomId, text, attachments)
            } finally {
                RuntimeDebugTrace.meetingFinished(roomId)
                AgentExecutionService.release(leaseId)
            }
        }
        jobRef.set(job)
        roomJobs[roomId] = job
        job.invokeOnCompletion {
            roomJobs.remove(roomId, job)
            roomAdmissions.remove(roomId, admission)
            admission.complete()
            val next = queuedPrompts[roomId]?.poll()
            if (next != null) {
                start(appContext, roomId, next.text, next.attachments)
            } else queuedPrompts.remove(roomId)
        }
        job.start()
        return true
    }


    /** Accepts a follow-up while the current turn is running; it starts after the current turn settles. */
    fun enqueue(context: Context, roomId: String, rawText: String, attachments: io.github.mangi.eta.data.repository.ConversationAttachments = io.github.mangi.eta.data.repository.ConversationAttachments()): Boolean {
        val text = rawText.trim()
        if (roomId.isBlank() || (text.isEmpty() && attachments.isEmpty) || !isRunning(roomId)) return false
        val queue = queuedPrompts.computeIfAbsent(roomId) { java.util.concurrent.ConcurrentLinkedQueue() }
        if (queue.size >= 8) return false
        queue.add(QueuedTurn(text, attachments))
        return true
    }

    fun queuedCount(roomId: String): Int = queuedPrompts[roomId]?.size ?: 0

    /** Stop a currently running room turn from a page-independent control surface. */
    fun stop(roomId: String): Boolean {
        if (roomId.isBlank()) return false
        val job = roomJobs[roomId] ?: return false
        val stopped = job.isActive
        if (stopped) job.cancel()
        // MeetingRuntime runs in this job; cancellation propagates through its interruptible
        // model request and persists the partial-turn state in its NonCancellable cleanup.
        return stopped
    }

    fun isRunning(roomId: String): Boolean = roomAdmissions.containsKey(roomId)
}
