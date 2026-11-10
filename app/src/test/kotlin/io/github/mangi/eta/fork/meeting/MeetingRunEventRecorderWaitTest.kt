package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MeetingRunEventRecorderWaitTest {
    private val wait = AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING)

    @Test fun callbackNeverWaitsForSlowDatabaseAndFinishFlushesInOrder() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<AgentEvent.AccessibilityWaiting>())
        val recorder = MeetingRunEventRecorder(runId = "wait-run", persistTool = {}, persistWait = {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            received += it
        })
        try {
            recorder.accept(wait)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            recorder.accept(wait.copy(phase = AccessibilityWaitPhase.RESTORED))
            assertTrue(received.isEmpty())
        } finally { release.countDown(); recorder.finish() }
        assertEquals(listOf(AccessibilityWaitPhase.WAITING, AccessibilityWaitPhase.RESTORED), received.map { it.phase })
    }

    @Test fun duplicateReplayAndLateEventsDoNotReopenCompletedRecorder() = runBlocking {
        val received = mutableListOf<AgentEvent.AccessibilityWaiting>()
        val recorder = MeetingRunEventRecorder(runId = "wait-run", persistTool = {}, persistWait = { received += it })
        repeat(5) { recorder.accept(wait) }
        recorder.finish()
        recorder.accept(wait.copy(outage = 2))
        recorder.finish()
        assertEquals(listOf(AccessibilityWaitPhase.WAITING, AccessibilityWaitPhase.CANCELLED), received.map { it.phase })
    }

    @Test fun persistenceFailureCannotCrashMainThreadOrPreventTerminalStage() = runBlocking {
        val received = mutableListOf<AccessibilityWaitPhase>()
        var failures = 0
        val recorder = MeetingRunEventRecorder(runId = "wait-run", persistTool = {}, persistWait = {
            if (it.phase == AccessibilityWaitPhase.WAITING) error("database temporarily unavailable")
            received += it.phase
        }, onFailure = { failures++ })
        recorder.accept(wait)
        recorder.finish()
        assertEquals(1, failures)
        assertEquals(listOf(AccessibilityWaitPhase.CANCELLED), received)
    }

    @Test fun cancelledCallerStillFlushesEndingState() = runBlocking {
        val received = mutableListOf<AccessibilityWaitPhase>()
        val recorder = MeetingRunEventRecorder(runId = "wait-run", persistTool = {}, persistWait = { received += it.phase })
        recorder.accept(wait)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() } finally { recorder.finish() }
        }
        entered.await()
        job.cancel()
        job.join()
        recorder.finish()
        assertEquals(listOf(AccessibilityWaitPhase.WAITING, AccessibilityWaitPhase.CANCELLED), received)
    }
}
