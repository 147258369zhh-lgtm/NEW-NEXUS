package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiControlCoordinatorTest {
    @Test fun manualActionWaitsForAgentAndBlocksResumeUntilItFinishes() {
        val coordinator = GuiControlCoordinator(); val control = coordinator.openRun("a", AgentRunController())
        val agentEntered = CountDownLatch(1); val agentDone = CountDownLatch(1)
        val manualEntered = CountDownLatch(1); val manualDone = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val original = pool.submit { control.withAction { agentEntered.countDown(); await(agentDone) } }
            await(agentEntered); coordinator.pauseForUser("a")
            val manual = pool.submit { coordinator.withUserAction("a") { manualEntered.countDown(); await(manualDone) } }
            assertFalse(manualEntered.await(50, TimeUnit.MILLISECONDS))
            agentDone.countDown(); original.get(2, TimeUnit.SECONDS); await(manualEntered)
            coordinator.resumeForUser()
            val next = pool.submit { control.withAction {} }
            Thread.sleep(50); assertFalse(next.isDone)
            manualDone.countDown(); manual.get(2, TimeUnit.SECONDS); next.get(2, TimeUnit.SECONDS)
        } finally { agentDone.countDown(); manualDone.countDown(); control.close(); pool.shutdownNow() }
    }
    @Test
    fun ownershipSpansActionsAndWaitersEnterInOrderAfterRelease() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bQueued = CountDownLatch(1)
        val cQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val c = coordinator.openRun("c", AgentRunController()) { if (it == GuiControlStatus.Waiting) cQueued.countDown() }
        val order = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstGeneration = a.withAction { it }
            val bResult = pool.submit { b.withAction { order += "b"; b.release() } }
            await(bQueued)
            val cResult = pool.submit { c.withAction { order += "c"; c.release() } }
            await(cQueued)
            assertEquals(firstGeneration, a.withAction { it })
            assertTrue(order.isEmpty())
            a.release()
            bResult.get(2, TimeUnit.SECONDS)
            cResult.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("b", "c"), order)
            assertNull(coordinator.state.value.ownerRunId)
        } finally {
            a.close(); b.close(); c.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellationKeepsOwnershipUntilTheCurrentActionExits() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val aResult = pool.submit { a.withAction { entered.countDown(); await(drain) } }
            await(entered)
            val bResult = pool.submit { b.withAction { } }
            await(bQueued)
            controller.cancel()
            assertEquals("a", coordinator.state.value.ownerRunId)
            assertFalse(bResult.isDone)
            drain.countDown()
            aResult.get(2, TimeUnit.SECONDS)
            bResult.get(2, TimeUnit.SECONDS)
            assertEquals("b", coordinator.state.value.ownerRunId)
        } finally {
            drain.countDown()
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancelledWaiterCannotEnterOrBlockTheNextWaiter() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val bController = AgentRunController()
        val bQueued = CountDownLatch(1)
        val b = coordinator.openRun("b", bController) { if (it == GuiControlStatus.Waiting) bQueued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            a.withAction { }
            val result = pool.submit { b.withAction { error("已取消的等待者不能执行") } }
            await(bQueued)
            bController.cancel()
            try {
                result.get(2, TimeUnit.SECONDS)
                error("应传播取消")
            } catch (failure: ExecutionException) {
                assertTrue(failure.cause is AgentRunCancelledException)
            }
            a.release()
            coordinator.openRun("c", AgentRunController()).use { c ->
                c.withAction { assertEquals("c", coordinator.state.value.ownerRunId) }
            }
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun userPauseSurvivesOwnerCancellationUntilExplicitResume() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        val a = coordinator.openRun("a", controller)
        val initialGeneration = a.withAction { it }
        val queued = CountDownLatch(1)
        val b = coordinator.openRun("b", AgentRunController()) { if (it == GuiControlStatus.Waiting) queued.countDown() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(coordinator.pauseForUser("a"))
            assertTrue(coordinator.state.value.userPaused)
            val bResult = pool.submit<Long> { b.withAction { it } }
            await(queued)
            controller.cancel()
            assertNull(coordinator.state.value.ownerRunId)
            assertTrue(coordinator.state.value.userPaused)
            assertFalse(bResult.isDone)
            assertTrue(coordinator.resumeForUser())
            assertTrue(bResult.get(2, TimeUnit.SECONDS) > initialGeneration)
            assertFalse(coordinator.state.value.userPaused)
        } finally {
            a.close(); b.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun releasingDuringUserPauseDoesNotLeaveTheOriginalRunPermanentlyPaused() {
        val coordinator = GuiControlCoordinator()
        val controller = AgentRunController()
        coordinator.openRun("a", controller).use { a ->
            a.withAction { }
            coordinator.pauseForUser("a")
            a.release()
            assertTrue(controller.isPaused)
            assertTrue(coordinator.state.value.userPaused)
            assertNull(coordinator.state.value.ownerRunId)
            coordinator.resumeForUser()
            assertFalse(controller.isPaused)
            assertFalse(coordinator.state.value.userPaused)
        }
    }

    @Test
    fun pauseIsPublishedOnlyAfterTheActionDrainsAndResumeInvalidatesObservationGeneration() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("a", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Long> { a.withAction { entered.countDown(); await(drain); it } }
            await(entered)
            assertTrue(coordinator.pauseForUser("a"))
            assertFalse(coordinator.state.value.userPaused)
            drain.countDown()
            val oldGeneration = result.get(2, TimeUnit.SECONDS)
            assertTrue(coordinator.state.value.userPaused)
            assertTrue(coordinator.resumeForUser())
            assertTrue(a.withAction { it } > oldGeneration)
        } finally {
            drain.countDown()
            a.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun accessibilityInterventionWaitsForAnAlreadyRunningActionToDrain() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("action", AgentRunController())
        val b = coordinator.openRun("repair", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val repairStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val action = pool.submit { a.withAction { entered.countDown(); await(drain) } }
            await(entered)
            val repair = pool.submit { b.withAccessibilityIntervention(2_000) { repairStarted.countDown() } }
            assertFalse(repairStarted.await(50, TimeUnit.MILLISECONDS))
            drain.countDown()
            action.get(2, TimeUnit.SECONDS)
            repair.get(2, TimeUnit.SECONDS)
            await(repairStarted)
            assertFalse(coordinator.state.value.accessibilityWaiting)
        } finally { drain.countDown(); a.close(); b.close(); pool.shutdownNow() }
    }

    @Test
    fun overlappingAccessibilityInterventionsRemainBlockedUntilBothEnd() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("repair-a", AgentRunController())
        val b = coordinator.openRun("repair-b", AgentRunController())
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val aEnd = CountDownLatch(1)
        val bEnd = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { a.withAccessibilityIntervention(2_000) { aStarted.countDown(); await(aEnd) } }
            val second = pool.submit { b.withAccessibilityIntervention(2_000) { bStarted.countDown(); await(bEnd) } }
            await(aStarted); await(bStarted)
            aEnd.countDown(); first.get(2, TimeUnit.SECONDS)
            assertTrue(coordinator.state.value.accessibilityWaiting)
            bEnd.countDown(); second.get(2, TimeUnit.SECONDS)
            assertFalse(coordinator.state.value.accessibilityWaiting)
        } finally { aEnd.countDown(); bEnd.countDown(); a.close(); b.close(); pool.shutdownNow() }
    }

    @Test
    fun timedOutInterventionCannotLeakAGlobalGuiBlock() {
        val coordinator = GuiControlCoordinator()
        val a = coordinator.openRun("action", AgentRunController())
        val b = coordinator.openRun("repair", AgentRunController())
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val action = pool.submit { a.withAction { entered.countDown(); await(drain) } }
            await(entered)
            org.junit.Assert.assertThrows(GuiInterventionTimeoutException::class.java) {
                b.withAccessibilityIntervention(20) { error("Must drain first") }
            }
            assertFalse(coordinator.state.value.accessibilityWaiting)
            drain.countDown(); action.get(2, TimeUnit.SECONDS)
            a.release()
            b.withAction { }
        } finally { drain.countDown(); a.close(); b.close(); pool.shutdownNow() }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("并发步骤未到达", latch.await(2, TimeUnit.SECONDS))
    }
}
