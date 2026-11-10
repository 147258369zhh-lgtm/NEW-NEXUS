package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MeetingRunEventRecorderTest {
    private val start = AgentEvent.ToolStarted(1, "call", "run_command", "run", "echo safe")
    private val finish = AgentEvent.ToolFinished(1, "call", "run_command", "done", 1, 100, success = true)

    @Test fun slowDatabaseNeverBlocksCallbackAndToolsAndWaitsKeepOneOrderedWriter() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Collections.synchronizedList(mutableListOf<String>())
        val caller = Thread.currentThread()
        val recorder = MeetingRunEventRecorder("run", persistWait = { result += "wait:${it.phase}" }, persistTool = {
            assertNotEquals(caller, Thread.currentThread())
            if (it.phase == MeetingToolPhase.RUNNING) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            result += "tool:${it.phase}"
        })
        try {
            recorder.accept(start)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            recorder.accept(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING))
            recorder.accept(finish)
            assertTrue(result.isEmpty())
        } finally { release.countDown(); assertTrue(recorder.finish()) }
        assertEquals(listOf("tool:RUNNING", "wait:WAITING", "tool:SUCCESS", "wait:CANCELLED"), result)
    }

    @Test fun duplicateReplayAndLateStartsOrFinishesCannotReopenATerminalTool() = runBlocking {
        val result = mutableListOf<MeetingToolActivity>()
        val recorder = MeetingRunEventRecorder("run", {}, { result += it })
        repeat(3) { recorder.accept(start) }
        recorder.accept(finish)
        recorder.accept(start)
        recorder.accept(finish.copy(success = false))
        assertTrue(recorder.finish())
        recorder.accept(start.copy(toolCallId = "late"))
        assertTrue(recorder.finish())
        assertEquals(listOf(MeetingToolPhase.RUNNING, MeetingToolPhase.SUCCESS), result.map { it.phase })
        assertEquals("echo safe", result.last().command)
    }

    @Test fun persistenceFailureStillAllowsTheCompleteFinishToRepairTheTrace() = runBlocking {
        val result = mutableListOf<MeetingToolActivity>()
        var failures = 0
        val recorder = MeetingRunEventRecorder("run", {}, {
            if (it.phase == MeetingToolPhase.RUNNING) error("write unavailable")
            result += it
        }, { failures++ })
        recorder.accept(start)
        recorder.accept(finish)
        assertTrue(recorder.finish())
        assertEquals(1, failures)
        assertEquals("run", result.single().arguments)
        assertEquals(MeetingToolPhase.SUCCESS, result.single().phase)
    }

    @Test fun hostedAndLocalToolsWithTheSameProviderIdStayIndependent() = runBlocking {
        val result = mutableListOf<MeetingToolActivity>()
        val recorder = MeetingRunEventRecorder("run", {}, { result += it })
        recorder.accept(start)
        recorder.accept(AgentEvent.HostedToolStarted(1, "call", "web_search"))
        recorder.accept(AgentEvent.HostedToolFinished(1, "call", "web_search", false))
        assertTrue(recorder.finish())
        assertEquals(2, result.map { it.messageId }.distinct().size)
        assertEquals(MeetingToolPhase.INTERRUPTED, result.last().phase)
        assertTrue(result.none { it.phase == MeetingToolPhase.SUCCESS })
    }

    @Test fun cancelledCallerStillDrainsToolAndWaitTerminalStates() = runBlocking {
        val result = mutableListOf<MeetingToolPhase>()
        val recorder = MeetingRunEventRecorder("run", {}, { result += it.phase })
        recorder.accept(start)
        val entered = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            try { entered.complete(Unit); awaitCancellation() } finally { recorder.finish() }
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(listOf(MeetingToolPhase.RUNNING, MeetingToolPhase.INTERRUPTED), result)
    }

    @Test fun permanentlySuspendedWriterHasABoundedFlushAndCannotAcceptMoreEvents() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val recorder = MeetingRunEventRecorder("run", {}, { entered.complete(Unit); awaitCancellation() },
            onFailure = { failures += it }, flushTimeoutMs = 50)
        recorder.accept(start)
        entered.await()
        assertFalse(recorder.finish())
        recorder.accept(finish)
        assertTrue(failures.any { it is TimeoutException })
    }

    @Test fun textDeltasAndNestedProgressAreNotAccidentallyStoredAsToolArguments() = runBlocking {
        val received = mutableListOf<MeetingToolActivity>()
        val recorder = MeetingRunEventRecorder("run", {}, { received += it })
        repeat(1_000) { recorder.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, 6, "secret")) }
        recorder.accept(AgentEvent.SubagentProgress(1, "call", "child", "coder", "private progress"))
        assertTrue(recorder.finish())
        assertTrue(received.isEmpty())
    }

    @Test fun aTransientTerminalWriteFailureIsRetriedFromTheLatestSnapshotWithoutReexecution() = runBlocking {
        val saved = mutableListOf<MeetingToolActivity>()
        var terminalAttempts = 0
        val recorder = MeetingRunEventRecorder("run", {}, {
            if (it.phase.terminal && terminalAttempts++ == 0) error("transient terminal write failure")
            saved += it
        })
        recorder.accept(start); recorder.accept(finish)
        assertTrue(recorder.finish())
        assertEquals(2, terminalAttempts)
        assertEquals(listOf(MeetingToolPhase.RUNNING, MeetingToolPhase.SUCCESS), saved.map { it.phase })
        assertEquals("done", saved.last().result)
    }

    @Test fun unresolvedTerminalWritesAreNotReportedAsACompleteFlush() = runBlocking {
        var attempts = 0
        val recorder = MeetingRunEventRecorder("run", {}, {
            if (it.phase.terminal) { attempts++; error("terminal storage unavailable") }
        })
        recorder.accept(start); recorder.accept(finish)
        assertFalse(recorder.finish())
        assertEquals(2, attempts)
    }

    @Test fun eachOutageKeepsItsOwnTerminalRetryEvenIfAnotherOutageStarts() = runBlocking {
        val saved = mutableListOf<AgentEvent.AccessibilityWaiting>()
        var firstTerminalAttempts = 0
        val recorder = MeetingRunEventRecorder("run", {
            if (it.outage == 1 && it.phase != AccessibilityWaitPhase.WAITING && firstTerminalAttempts++ == 0) error("transient")
            saved += it
        }, {})
        recorder.accept(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING))
        recorder.accept(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.RESTORED))
        recorder.accept(AgentEvent.AccessibilityWaiting(2, AccessibilityWaitPhase.WAITING))
        assertTrue(recorder.finish())
        assertEquals(2, firstTerminalAttempts)
        assertEquals(AccessibilityWaitPhase.RESTORED, saved.last { it.outage == 1 }.phase)
        assertEquals(AccessibilityWaitPhase.CANCELLED, saved.last { it.outage == 2 }.phase)
    }

    @Test fun manyFastDeltasProduceBatchedWritesAndFlushBeforeAToolBoundary() = runBlocking {
        val result = mutableListOf<String>()
        val snapshots = mutableListOf<MeetingStreamActivity>()
        val recorder = MeetingRunEventRecorder("run", {}, { result += "tool" },
            persistStream = { snapshots += it; result += "stream" }, streamFlushIntervalMs = 10_000)
        repeat(1_000) { recorder.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 1, "x")) }
        recorder.accept(start); recorder.accept(finish)
        assertTrue(recorder.finish())
        assertEquals(1, snapshots.size)
        assertEquals("x".repeat(1_000), snapshots.single().content)
        assertEquals(MeetingStreamPhase.COMPLETED, snapshots.single().phase)
        assertEquals("stream", result.first())
    }

    @Test fun streamTerminalFailureRetriesItsLatestTextOnceAndReportsTheRealFlushResult() = runBlocking {
        var attempts = 0
        val result = mutableListOf<MeetingStreamActivity>()
        val recorder = MeetingRunEventRecorder("run", {}, {}, persistStream = {
            if (attempts++ == 0) error("temporary stream write failure")
            result += it
        }, streamFlushIntervalMs = 10_000)
        recorder.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 4, "body"))
        assertTrue(recorder.finish())
        assertEquals(2, attempts)
        assertEquals("body", result.single().content)
        assertEquals(MeetingStreamPhase.INTERRUPTED, result.single().phase)
    }
}
