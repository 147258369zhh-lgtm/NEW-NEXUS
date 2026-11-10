package io.github.mangi.eta.agent.accessibility

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [33, 36, 37], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class AccessibilityRunGateTest {
    private val coordinator = GuiControlCoordinator()
    private val controller = AgentRunController()
    private val gui = coordinator.openRun("test", controller)
    private val available = AtomicBoolean(false)
    private val events = Collections.synchronizedList(mutableListOf<AgentEvent.AccessibilityWaiting>())

    private fun gate(recovery: () -> Unit = {}, budget: Long = 5_000,
                     now: () -> Long = { System.nanoTime() / 1_000_000L }) = AccessibilityRunGate(
        controller, gui, available::get, recovery, { events += it },
        totalWaitMs = budget, nowMs = now, pollMs = 10, minimumRecoveryBudgetMs = 0,
    )

    @Test fun availableAndBackgroundToolsNeverRequestRecoveryOrChangeGui() {
        val gate = gate(recovery = { error("Should not recover") })
        assertSame(ToolExecutionDecision.Allow, gate.prepare("memory_get"))
        available.set(true)
        val generation = gui.withAction { it }
        assertSame(ToolExecutionDecision.Allow, gate.prepare("observe_screen"))
        assertEquals(generation, gui.withAction { it })
        assertTrue(events.isEmpty())
        gui.close()
    }

    @Test fun restoredWriteIsNotReplayedAndOldGuiGenerationIsInvalidated() {
        val before = gui.withAction { it }
        val result = gate({ available.set(true) }).prepare("type_text") as ToolExecutionDecision.Reject
        assertEquals("ACCESSIBILITY_RESTORED_REOBSERVE", result.code)
        assertNull(coordinator.state.value.ownerRunId)
        assertFalse(coordinator.state.value.accessibilityWaiting)
        assertTrue(gui.withAction { it } > before)
        assertEquals(listOf(AccessibilityWaitPhase.WAITING, AccessibilityWaitPhase.RESTORED), events.map { it.phase })
        gui.close()
    }

    @Test fun ordinaryModeCanResumeObservationAfterManualServiceRecovery() {
        val started = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val gate = gate({ started.countDown() })
            val result = pool.submit<ToolExecutionDecision> { gate.prepare("observe_screen") }
            await(started)
            assertTrue(coordinator.state.value.accessibilityWaiting)
            assertNull(coordinator.state.value.ownerRunId)
            available.set(true)
            assertSame(ToolExecutionDecision.Allow, result.get(2, TimeUnit.SECONDS))
            assertEquals(AccessibilityWaitPhase.RESTORED, events.last().phase)
        } finally { gui.close(); pool.shutdownNow() }
    }

    @Test fun otherRunsCannotAcquireGuiWhileUserReconnectsInSettings() {
        val started = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val other = coordinator.openRun("other", AgentRunController()) { if (it == GuiControlStatus.Waiting) queued.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            gui.withAction { }
            val wait = pool.submit<ToolExecutionDecision> { gate({ started.countDown() }).prepare("observe_screen") }
            await(started)
            val action = pool.submit<Boolean> { other.withAction { true } }
            await(queued)
            assertFalse(action.isDone)
            available.set(true)
            wait.get(2, TimeUnit.SECONDS)
            assertTrue(action.get(2, TimeUnit.SECONDS))
        } finally { gui.close(); other.close(); pool.shutdownNow() }
    }

    @Test fun cancellationInterruptsWaitingAndClearsGlobalIntervention() {
        val started = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<ToolExecutionDecision> { gate({ started.countDown() }).prepare("tap") }
            await(started)
            controller.cancel()
            val failure = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is AgentRunCancelledException)
            assertEquals(AccessibilityWaitPhase.CANCELLED, events.last().phase)
            assertFalse(coordinator.state.value.accessibilityWaiting)
            coordinator.openRun("next", AgentRunController()).use { it.withAction { } }
        } finally { gui.close(); pool.shutdownNow() }
    }

    @Test fun cancellationInterruptsRootRecoveryBeforeAnyAction() {
        val started = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<ToolExecutionDecision> {
                gate({ started.countDown(); Thread.sleep(60_000) }).prepare("observe_screen")
            }
            await(started)
            controller.cancel()
            val failure = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is AgentRunCancelledException)
            assertFalse(coordinator.state.value.accessibilityWaiting)
        } finally { gui.close(); pool.shutdownNow() }
    }

    @Test fun repeatedToolsShareBudgetAndDoNotRestartRecoveryAfterTimeout() {
        val clock = AtomicLong(0)
        val calls = AtomicInteger(0)
        val gate = gate({
            val count = calls.incrementAndGet()
            clock.addAndGet(if (count == 1) 40 else 61)
            if (count == 1) available.set(true)
        }, budget = 100, now = clock::get)
        assertSame(ToolExecutionDecision.Allow, gate.prepare("observe_screen"))
        available.set(false)
        assertEquals("ACCESSIBILITY_WAIT_TIMEOUT", (gate.prepare("observe_screen") as ToolExecutionDecision.Reject).code)
        assertEquals("ACCESSIBILITY_WAIT_TIMEOUT", (gate.prepare("tap") as ToolExecutionDecision.Reject).code)
        assertEquals(2, calls.get())
        assertEquals(listOf(1, 1, 2, 2), events.map { it.outage })
        assertFalse(coordinator.state.value.accessibilityWaiting)
        available.set(true)
        assertSame(ToolExecutionDecision.Allow, gate.prepare("observe_screen"))
        gui.close()
    }

    @Test fun automaticRecoveryNeverResumesManualUserPause() {
        val otherController = AgentRunController()
        val other = coordinator.openRun("manual", otherController)
        try {
            other.withAction { }
            assertTrue(coordinator.pauseForUser("manual"))
            assertSame(ToolExecutionDecision.Allow, gate({ available.set(true) }).prepare("observe_screen"))
            assertTrue(coordinator.state.value.userPaused)
            assertTrue(otherController.isPaused)
            assertFalse(coordinator.state.value.accessibilityWaiting)
        } finally { coordinator.resumeForUser(); other.close(); gui.close() }
    }

    @Test fun recoveryFailureStillAllowsManualRecoveryWithoutRepeatedBackendCalls() {
        val pool = Executors.newSingleThreadExecutor()
        val attempted = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val wait = pool.submit<ToolExecutionDecision> {
                gate({ calls.incrementAndGet(); attempted.countDown(); error("backend lost") }).prepare("observe_screen")
            }
            await(attempted)
            available.set(true)
            assertSame(ToolExecutionDecision.Allow, wait.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { gui.close(); pool.shutdownNow() }
    }

    @Test fun concurrentCallsShareOneOutageAndOneRecovery() {
        val pool = Executors.newFixedThreadPool(2)
        val attempted = CountDownLatch(1)
        val calls = AtomicInteger()
        val gate = gate({ calls.incrementAndGet(); attempted.countDown() })
        try {
            val first = pool.submit<ToolExecutionDecision> { gate.prepare("observe_screen") }
            await(attempted)
            val second = pool.submit<ToolExecutionDecision> { gate.prepare("observe_screen") }
            available.set(true)
            assertSame(ToolExecutionDecision.Allow, first.get(2, TimeUnit.SECONDS))
            assertSame(ToolExecutionDecision.Allow, second.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(2, events.size)
        } finally { gui.close(); pool.shutdownNow() }
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(2, TimeUnit.SECONDS))
}
