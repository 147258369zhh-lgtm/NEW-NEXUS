package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** The actual local/IPC execution boundary; no Room writes are performed by its event callback. */
internal object MeetingExecutionTranscript {
    suspend fun <T> run(
        context: Context,
        participant: MeetingParticipantEntity,
        runId: String,
        taskId: String?,
        recordStreams: Boolean = true,
        execute: ((AgentEvent) -> Unit) -> T,
    ): T {
        val recorder = MeetingRunEventRecorder(
            runId = runId,
            persistWait = { MeetingRepository.recordAccessibilityWait(context, participant.roomId,
                participant.profileName, participant.displayName, runId, it) },
            persistTool = { MeetingRepository.recordToolActivity(context, participant.roomId,
                participant.profileName, participant.displayName, participant.isHost, taskId, it) },
            persistStream = { MeetingRepository.recordStreamActivity(context, participant.roomId,
                participant.profileName, participant.displayName, participant.isHost, taskId, it) },
            recordStreams = recordStreams,
            onFailure = { AndroidAgentLogger.warn("Meeting transcript persistence failed: ${it.javaClass.simpleName}") },
        )
        return try { runInterruptible(Dispatchers.IO) { execute(recorder::accept) } }
        finally { recorder.finish() }
    }
}
