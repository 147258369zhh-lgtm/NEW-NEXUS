package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentRunControllerTest {
    @Test fun abortWakesPausedRunAndPreservesFailureWhenResourceInterruptsWorker() {
        val controller = AgentRunController()
        val reason = IllegalStateException("budget expired")
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val observed = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread {
            val resource = controller.register(Thread.currentThread()::interrupt)
            try {
                entered.countDown()
                controller.throwIfCancelled()
            } catch (failure: Throwable) { observed.set(failure) }
            finally { resource.close(); finished.countDown() }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.abort(reason)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertSame(reason, observed.get())
            assertTrue(controller.isCancelled)
            assertFalse(controller.isUserCancelled)
            controller.cancel()
            assertSame(reason, controller.abortFailure)
        } finally { controller.cancel(); worker.join(1_000) }
    }

    @Test fun explicitCancellationWinsOverALaterDeadline() {
        val controller = AgentRunController()
        controller.cancel()
        controller.abort(IllegalStateException("late deadline"))
        assertNull(controller.abortFailure)
        assertTrue(controller.isUserCancelled)
        assertThrows(AgentRunCancelledException::class.java, controller::throwIfCancelled)
    }
    @Test
    fun cancellationWakesLongRetryWait() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            started.countDown()
            try {
                controller.awaitRetryDelay(60_000L)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun steeringIsQueuedOneAtATimeWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }

        assertTrue(controller.steer("  first  "))
        assertFalse(controller.steer("   "))
        assertTrue(controller.steer("second"))

        assertEquals(0, cancellations.get())
        assertEquals("first", controller.pollSteeringMessage())
        assertEquals("second", controller.pollSteeringMessage())
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun finalPollAtomicallySealsSteering() {
        val controller = AgentRunController()

        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun cancelClearsSteeringAndCancelsEachResourceOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }
        controller.steer("pending")

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancellations.get())
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.steer("late"))
    }

    @Test
    fun closedBindingIsNotCancelledLater() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        val binding = controller.register { cancellations.incrementAndGet() }

        binding.close()
        controller.cancel()

        assertEquals(0, cancellations.get())
    }

    @Test
    fun resourceRegisteredAfterCancellationIsCancelledExactlyOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.cancel()

        val binding = controller.register { cancellations.incrementAndGet() }
        controller.cancel()
        binding.close()

        assertEquals(1, cancellations.get())
    }

    @Test
    fun pauseBlocksUntilResume() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-pause-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun steeringDoesNotResumeAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-steering-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.steer("补充条件"))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("补充条件", controller.pollSteeringMessage())
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelWhilePausedWakesWorkerWithCancellation() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-cancel-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun inboundA2AApprovalReleasesOnlyTheExactPendingCallOnce() {
        val controller = AgentRunController()
        controller.bindRecoverySurface(io.github.zhh_lgtm.nexus.kernel.api.RunSource.A2A_INBOUND)
        val digest = "c".repeat(64)
        controller.bindLocalA2AApproval(
            grant = a2aApprovalGrant(digest = digest),
            expectedTaskId = "task-1",
            expectedContinuationCount = 1,
        )

        val exact = controller.beginApproval(
            toolName = "write_file",
            effect = io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
            argumentsDigest = digest,
        )
        assertTrue(exact.isPreapproved)

        // The same grant must not release the call a second time.
        val replay = assertThrows(AgentRunPauseException::class.java) {
            controller.beginApproval(
                toolName = "write_file",
                effect = io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
                argumentsDigest = digest,
            )
        }
        assertFalse(replay.pause.pendingExecuted)
    }

    @Test
    fun inboundA2AApprovalRejectsADifferentArgumentDigest() {
        val controller = AgentRunController()
        controller.bindRecoverySurface(io.github.zhh_lgtm.nexus.kernel.api.RunSource.A2A_INBOUND)
        controller.bindLocalA2AApproval(
            grant = a2aApprovalGrant(digest = "c".repeat(64)),
            expectedTaskId = "task-1",
            expectedContinuationCount = 1,
        )

        val pause = assertThrows(AgentRunPauseException::class.java) {
            controller.beginApproval(
                toolName = "write_file",
                effect = io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
                argumentsDigest = "d".repeat(64),
            )
        }
        assertEquals("write_file", pause.pause.pendingTool)
        assertEquals("d".repeat(64), pause.pause.pendingArgumentsDigest)
        assertFalse(pause.pause.pendingExecuted)
    }

    @Test
    fun settledInboundA2ACallsAreBlockedByToolEffectAndDigest() {
        val controller = AgentRunController()
        val digest = "c".repeat(64)
        controller.bindA2ASettledCalls(listOf("write_file|REVERSIBLE_WRITE|$digest"))

        assertTrue(
            controller.isSettledA2ACall(
                "write_file",
                io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
                digest,
            ),
        )
        assertFalse(
            controller.isSettledA2ACall(
                "write_file",
                io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
                "d".repeat(64),
            ),
        )
        assertFalse(
            controller.isSettledA2ACall(
                "read_file",
                io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE,
                digest,
            ),
        )
        assertTrue(controller.hasSettledA2AEffectfulCall())
        controller.recordSettledA2AToolCall(
            "read_file",
            io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ,
            "e".repeat(64),
        )
        assertTrue(
            controller.isSettledA2ACall(
                "read_file",
                io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ,
                "e".repeat(64),
            ),
        )
        assertEquals(2, controller.settledA2ACallsSnapshot().size)
    }

    private fun a2aApprovalGrant(digest: String) = AgentRuntimeWire.A2ALocalApprovalGrant(
        taskId = "task-1",
        messageId = "message-1",
        continuationCount = 1,
        allowedTools = setOf("write_file"),
        allowedToolEffects = setOf("write_file=REVERSIBLE_WRITE"),
        originalGrantFingerprint = "original",
        currentGrantFingerprint = "current",
        pendingTool = "write_file",
        pendingEffect = "REVERSIBLE_WRITE",
        pendingArgumentsDigest = digest,
    )
}
