package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubagentConcurrencyGateTest {
    @Test
    fun globalGateNeverExceedsItsProcessWideLimit() {
        val gate = SubagentConcurrencyGate(limit = 2)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val queued = AtomicInteger()
        val admittedAfterWaiting = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 6).map {
                executor.submit {
                    gate.withPermit(
                        controller = AgentRunController(),
                        onQueued = { queued.incrementAndGet() },
                        onAdmitted = { admittedAfterWaiting.incrementAndGet() },
                    ) {
                        maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        active.decrementAndGet()
                    }
                }
            }
            assertEquals(true, entered.await(3, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(2, maximum.get())
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4, queued.get())
            assertEquals(4, admittedAfterWaiting.get())
            assertEquals(2, gate.availablePermits())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun waitingHolderObservesCancellationBeforeAdmission() {
        val gate = SubagentConcurrencyGate(limit = 1)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                gate.withPermit(AgentRunController()) {
                    admitted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertEquals(true, admitted.await(3, TimeUnit.SECONDS))
            val waiting = AgentRunController().apply { cancel() }
            assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
                gate.withPermit(waiting) { error("cancelled waiter executed") }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledWaiterReleasesQueueAccountingWithoutChangingPermits() {
        val gate = SubagentConcurrencyGate(limit = 1)
        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                gate.withPermit(AgentRunController()) {
                    holderEntered.countDown()
                    releaseHolder.await(3, TimeUnit.SECONDS)
                }
            }
            assertEquals(true, holderEntered.await(2, TimeUnit.SECONDS))

            val waitingController = AgentRunController()
            val waiter = pool.submit {
                gate.withPermit(waitingController) { error("cancelled waiter must not run") }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (gate.waitingCount() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, gate.waitingCount())
            waitingController.cancel()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { waiter.get(2, TimeUnit.SECONDS) }
            assertEquals(0, gate.waitingCount())
            assertEquals(0, gate.availablePermits())
            releaseHolder.countDown()
            holder.get(2, TimeUnit.SECONDS)
            assertEquals(1, gate.availablePermits())
        } finally {
            releaseHolder.countDown()
            pool.shutdownNow()
        }
    }
}
