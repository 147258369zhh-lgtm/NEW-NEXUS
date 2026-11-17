package io.github.mangi.eta.agent.virtualdisplay

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VirtualDisplaySessionTest {
    @Suppress("UNCHECKED_CAST")
    private fun snapshots() = VirtualDisplaySessionController::class.java.getDeclaredField("mutableSnapshots").apply { isAccessible = true }
        .get(VirtualDisplayController.primaryForTest) as MutableStateFlow<VirtualDisplaySnapshot>
    @Suppress("UNCHECKED_CAST")
    private fun snapshots(controller: VirtualDisplaySessionController) =
        VirtualDisplaySessionController::class.java.getDeclaredField("mutableSnapshots").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<VirtualDisplaySnapshot>
    private fun session(id: String) = VirtualDisplaySnapshot(active = true, displayId = 4, width = 100, height = 200,
        sessionId = id, userControlling = true, manualInputGeneration = 1, lifecycle = VirtualDisplayLifecycle.ACTIVE,
        startedAtElapsedMs = 1, lastFrameAtElapsedMs = 1)

    private fun lease(owner: String, sessionId: String) = VirtualDisplayLease(owner, sessionId, 4, snapshots().value.ownershipGeneration)

    @Test fun releasedLeaseCannotReviveWhenSameOwnerReclaimsTheSameSession() {
        val controller = VirtualDisplaySessionController()
        val state = snapshots(controller)
        try {
            state.value = session("round-trip").copy(ownerRunId = "run-a")
            val oldLease = controller.claimLease("run-a")!!
            assertTrue(controller.releaseLease(oldLease))
            val replacementLease = controller.claimLease("run-a")!!
            assertEquals(oldLease.sessionId, replacementLease.sessionId)
            assertNotEquals(oldLease, replacementLease)
            assertFalse(controller.validate(oldLease))
            assertTrue(controller.validate(replacementLease))
            assertFalse(controller.dispatchIfLeaseCurrent(oldLease) { fail("Released capability revived") })
            assertFalse(controller.releaseLease(oldLease))
            assertTrue(controller.validate(replacementLease))
        } finally {
            state.value = VirtualDisplaySnapshot.Inactive
            controller.shutdownExecutors()
        }
    }

    @Test fun frameFreshnessExpiresOldFramesAndRejectsClockRegression() {
        val snapshot = session("frame").copy(startedAtElapsedMs = 1_000, lastFrameAtElapsedMs = 2_000)
        assertTrue(snapshot.hasFreshFrame(12_000))
        assertFalse(snapshot.hasFreshFrame(12_001))
        assertFalse(snapshot.hasFreshFrame(1_999))
        assertTrue(snapshot.copy(lastFrameAtElapsedMs = 0).hasFreshFrame(10_999))
        assertFalse(snapshot.copy(lastFrameAtElapsedMs = 0).hasFreshFrame(11_001))
    }

    @Test fun focusEvidenceIsObservedPerCurrentDisplayLeaseAndDoesNotCrossSessionReplacement() {
        val snapshots = snapshots()
        val old = snapshots.value
        try {
            snapshots.value = session("focus-a").copy(ownerRunId = "run-a")
            val leaseA = VirtualDisplayController.claimLease("run-a")!!
            assertTrue(
                VirtualDisplayController.recordFocusObservation(
                    leaseA,
                    VirtualDisplayFocusObservation.FOCUSED_APPLICATION_WINDOW,
                    observedAtElapsedMs = 3_000L,
                ),
            )
            assertTrue(snapshots.value.hasObservedApplicationWindowFocus)
            assertEquals(VirtualDisplayFocusObservation.FOCUSED_APPLICATION_WINDOW, snapshots.value.focusObservation)
            assertEquals(3_000L, snapshots.value.focusObservedAtElapsedMs)

            snapshots.value = session("focus-b").copy(ownerRunId = "run-a")
            assertFalse(
                VirtualDisplayController.recordFocusObservation(
                    leaseA,
                    VirtualDisplayFocusObservation.NO_FOCUSED_APPLICATION_WINDOW,
                    observedAtElapsedMs = 4_000L,
                ),
            )
            assertEquals(VirtualDisplayFocusObservation.UNKNOWN, snapshots.value.focusObservation)
            assertFalse(snapshots.value.hasObservedApplicationWindowFocus)
        } finally {
            snapshots.value = old
        }
    }

    @Test fun delayedLaunchFromReplacedSessionCannotDispatchOrUpdateReplacement() {
        val snapshots = snapshots()
        val old = snapshots.value
        var dispatched = false
        try {
            snapshots.value = session("launch-old").copy(ownerRunId = "run-a", packageName = "old.pkg")
            val captured = snapshots.value
            snapshots.value = session("launch-new").copy(ownerRunId = "run-a", packageName = "new.pkg")

            assertFalse(VirtualDisplayController.dispatchIfSessionCurrent(captured) { dispatched = true })
            assertFalse(dispatched)
            assertEquals("launch-new", snapshots.value.sessionId)
            assertEquals("new.pkg", snapshots.value.packageName)
        } finally {
            snapshots.value = old
        }
    }

    @Test fun separateSessionControllersKeepLeasesSnapshotsAndFrameResourcesIndependent() {
        val controllerA = VirtualDisplaySessionController()
        val controllerB = VirtualDisplaySessionController()
        val snapshotsA = snapshots(controllerA)
        val snapshotsB = snapshots(controllerB)
        snapshotsA.value = session("session-a").copy(displayId = 4)
        snapshotsB.value = session("session-b").copy(displayId = 5)

        val leaseA = controllerA.claimLease("run-a")
        val leaseB = controllerB.claimLease("run-b")
        assertEquals(4, leaseA?.displayId)
        assertEquals(5, leaseB?.displayId)
        assertEquals("session-a", controllerA.snapshots.value.sessionId)
        assertEquals("session-b", controllerB.snapshots.value.sessionId)

        assertTrue(controllerA.releaseLease(leaseA))
        assertEquals("", controllerA.snapshots.value.ownerRunId)
        assertEquals("run-b", controllerB.snapshots.value.ownerRunId)

        val bitmapField = VirtualDisplaySessionController::class.java
            .getDeclaredField("latestFullBitmap").apply { isAccessible = true }
        val bitmapA = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmapB = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmapField.set(controllerA, bitmapA)
            bitmapField.set(controllerB, bitmapB)
            assertSame(bitmapA, bitmapField.get(controllerA))
            assertSame(bitmapB, bitmapField.get(controllerB))
        } finally {
            bitmapField.set(controllerA, null)
            bitmapField.set(controllerB, null)
            bitmapA.recycle()
            bitmapB.recycle()
        }
    }

    @Test fun facadeResolvesRegisteredLeasesToTheirOwnFramesFocusAndReleaseState() {
        val controllerA = VirtualDisplaySessionController()
        val controllerB = VirtualDisplaySessionController()
        val snapshotsA = snapshots(controllerA)
        val snapshotsB = snapshots(controllerB)
        val now = android.os.SystemClock.elapsedRealtime()
        val oldA = snapshotsA.value
        val oldB = snapshotsB.value
        val registryField = VirtualDisplayController::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val registry = registryField.get(VirtualDisplayController)
        val register = registry.javaClass.getDeclaredMethod("register", VirtualDisplaySessionController::class.java).apply { isAccessible = true }
        val unregister = registry.javaClass.getDeclaredMethod("unregister", String::class.java, VirtualDisplaySessionController::class.java)
            .apply { isAccessible = true }
        val bitmapField = VirtualDisplaySessionController::class.java.getDeclaredField("latestFullBitmap").apply { isAccessible = true }
        val bitmapA = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bitmapB = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try {
            snapshotsA.value = session("facade-a").copy(
                displayId = 14, ownerRunId = "facade-run-a", startedAtElapsedMs = now, lastFrameAtElapsedMs = now,
            )
            snapshotsB.value = session("facade-b").copy(
                displayId = 15, ownerRunId = "facade-run-b", startedAtElapsedMs = now, lastFrameAtElapsedMs = now,
            )
            bitmapField.set(controllerA, bitmapA)
            bitmapField.set(controllerB, bitmapB)
            register.invoke(registry, controllerA)
            register.invoke(registry, controllerB)
            val leaseA = snapshotsA.value.lease!!
            val leaseB = snapshotsB.value.lease!!

            assertTrue(VirtualDisplayController.validate(leaseA))
            assertTrue(VirtualDisplayController.validate(leaseB))
            assertEquals("facade-a", VirtualDisplayController.snapshotFor(leaseA)?.sessionId)
            assertEquals("facade-b", VirtualDisplayController.snapshotFor(leaseB)?.sessionId)
            val frameA = VirtualDisplayController.captureScreenshotBitmap(
                waitUpToMs = 0, expectedDisplayId = 14, expectedSessionId = "facade-a", lease = leaseA,
            )!!
            val frameB = VirtualDisplayController.captureScreenshotBitmap(
                waitUpToMs = 0, expectedDisplayId = 15, expectedSessionId = "facade-b", lease = leaseB,
            )!!
            try {
                assertEquals(android.graphics.Color.RED, frameA.getPixel(0, 0))
                assertEquals(android.graphics.Color.BLUE, frameB.getPixel(0, 0))
            } finally {
                frameA.recycle()
                frameB.recycle()
            }

            assertTrue(VirtualDisplayController.recordFocusObservation(
                leaseA, VirtualDisplayFocusObservation.FOCUSED_APPLICATION_WINDOW, observedAtElapsedMs = now + 1,
            ))
            assertTrue(snapshotsA.value.hasObservedApplicationWindowFocus)
            assertFalse(snapshotsB.value.hasObservedApplicationWindowFocus)
            assertTrue(VirtualDisplayController.releaseLease(leaseA))
            assertFalse(VirtualDisplayController.validate(leaseA))
            assertTrue(VirtualDisplayController.validate(leaseB))
            assertEquals("facade-run-b", snapshotsB.value.ownerRunId)
        } finally {
            unregister.invoke(registry, "facade-a", controllerA)
            unregister.invoke(registry, "facade-b", controllerB)
            bitmapField.set(controllerA, null)
            bitmapField.set(controllerB, null)
            snapshotsA.value = oldA
            snapshotsB.value = oldB
            bitmapA.recycle()
            bitmapB.recycle()
            controllerA.shutdownExecutors()
            controllerB.shutdownExecutors()
        }
    }

    @Test fun facadeSelectsExactOwnedSessionAndReleasesOnlyRequestedRunLeases() {
        val controllerA = VirtualDisplaySessionController()
        val controllerB = VirtualDisplaySessionController()
        val controllerC = VirtualDisplaySessionController()
        val snapshotsA = snapshots(controllerA)
        val snapshotsB = snapshots(controllerB)
        val snapshotsC = snapshots(controllerC)
        val oldA = snapshotsA.value
        val oldB = snapshotsB.value
        val oldC = snapshotsC.value
        val registryField = VirtualDisplayController::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val registry = registryField.get(VirtualDisplayController)
        val register = registry.javaClass.getDeclaredMethod("register", VirtualDisplaySessionController::class.java).apply { isAccessible = true }
        val unregister = registry.javaClass.getDeclaredMethod("unregister", String::class.java, VirtualDisplaySessionController::class.java)
            .apply { isAccessible = true }
        try {
            snapshotsA.value = session("select-a").copy(displayId = 24, ownerRunId = "select-run")
            snapshotsB.value = session("select-b").copy(displayId = 25, ownerRunId = "select-run")
            snapshotsC.value = session("select-c").copy(displayId = 26, ownerRunId = "sibling-run")
            listOf(controllerA, controllerB, controllerC).forEach { register.invoke(registry, it) }

            val leases = listOf(
                VirtualDisplayLease("select-run", "select-a", 24),
                VirtualDisplayLease("select-run", "select-b", 25),
                VirtualDisplayLease("sibling-run", "select-c", 26),
            )
            assertEquals(setOf("select-a", "select-b"),
                VirtualDisplayController.snapshotsForRun("select-run").map { it.sessionId }.toSet())
            assertEquals(setOf("select-c"),
                VirtualDisplayController.snapshotsForRun("sibling-run").map { it.sessionId }.toSet())

            assertEquals(leases[1], VirtualDisplayController.chooseSession("select-run", "select-b"))
            assertEquals(leases[0], VirtualDisplayController.chooseSession("select-run", "select-a"))
            assertNull(VirtualDisplayController.chooseSession("sibling-run", "select-a"))
            assertNull(VirtualDisplayController.chooseSession("select-run", "missing-session"))
            assertNull(VirtualDisplayController.claimLease("select-run", "missing-session"))
            assertNull(VirtualDisplayController.claimLease("sibling-run", "select-a"))

            assertTrue(VirtualDisplayController.releaseRunLeases("select-run"))
            assertFalse(VirtualDisplayController.validate(leases[0]))
            assertFalse(VirtualDisplayController.validate(leases[1]))
            assertTrue(VirtualDisplayController.validate(leases[2]))
            assertEquals("sibling-run", snapshotsC.value.ownerRunId)
            assertEquals("", snapshotsA.value.ownerRunId)
            assertEquals("", snapshotsB.value.ownerRunId)
        } finally {
            unregister.invoke(registry, "select-a", controllerA)
            unregister.invoke(registry, "select-b", controllerB)
            unregister.invoke(registry, "select-c", controllerC)
            snapshotsA.value = oldA
            snapshotsB.value = oldB
            snapshotsC.value = oldC
            controllerA.shutdownExecutors()
            controllerB.shutdownExecutors()
            controllerC.shutdownExecutors()
        }
    }

    @Test fun additionalSessionAllocationEnforcesPerRunAndGlobalCapsWithoutCreatingDisplays() {
        val controllersField = VirtualDisplayController::class.java.getDeclaredField("controllers").apply { isAccessible = true }
        val runControllersField = VirtualDisplayController::class.java.getDeclaredField("runControllers").apply { isAccessible = true }
        val controllers = controllersField.get(VirtualDisplayController) as java.util.concurrent.ConcurrentHashMap<String, VirtualDisplaySessionController>
        val runControllers = runControllersField.get(VirtualDisplayController) as java.util.concurrent.ConcurrentHashMap<String, VirtualDisplaySessionController>
        val previousControllers = controllers.toMap()
        val previousRunControllers = runControllers.toMap()
        val primary = VirtualDisplayController.primaryForTest
        val primarySnapshots = snapshots(primary)
        val previousPrimarySnapshot = primarySnapshots.value
        val additionalController = VirtualDisplayController::class.java
            .getDeclaredMethod("additionalController", String::class.java).apply { isAccessible = true }
        val created = mutableListOf<VirtualDisplaySessionController>()
        try {
            controllers.clear()
            controllers[primary.registryKey] = primary
            runControllers.clear()
            primarySnapshots.value = VirtualDisplaySnapshot.Inactive

            fun reserve(runId: String) {
                val controller = additionalController.invoke(VirtualDisplayController, runId) as? VirtualDisplaySessionController
                    ?: error("Expected capacity for $runId")
                created += controller
                snapshots(controller).value = VirtualDisplaySnapshot(
                    active = true, displayId = 40 + created.size, width = 100, height = 200,
                    sessionId = "cap-${created.size}", ownerRunId = runId,
                    lifecycle = VirtualDisplayLifecycle.ACTIVE,
                )
            }
            repeat(3) { reserve("run-cap-a") }
            assertNull(additionalController.invoke(VirtualDisplayController, "run-cap-a"))
            repeat(3) { reserve("run-cap-b") }
            assertEquals(6, controllers.size)
            assertNull(additionalController.invoke(VirtualDisplayController, "run-cap-c"))

            val idleController = VirtualDisplaySessionController()
            created += idleController
            val idleSnapshots = snapshots(idleController)
            idleSnapshots.value = VirtualDisplaySnapshot(
                active = true, displayId = 50, width = 100, height = 200, sessionId = "idle-old",
                ownerRunId = "", lifecycle = VirtualDisplayLifecycle.ACTIVE,
            )
            val unownedSince = VirtualDisplaySessionController::class.java
                .getDeclaredField("unownedSinceElapsedMs").apply { isAccessible = true }
            unownedSince.setLong(idleController, 1L)
            assertTrue(idleController.releaseIfIdle(nowElapsedMs = 120_002L, idleAfterMs = 120_000L))
            assertFalse(idleSnapshots.value.active)
        } finally {
            controllers.values.toSet().filter { it !== primary && it !in previousControllers.values }.forEach { it.shutdownExecutors() }
            created.filter { it !== primary && it !in previousControllers.values }.forEach { it.shutdownExecutors() }
            controllers.clear()
            controllers.putAll(previousControllers)
            runControllers.clear()
            runControllers.putAll(previousRunControllers)
            primarySnapshots.value = previousPrimarySnapshot
        }
    }

    @Test fun staleRunReservationCannotDispatchOrReleaseReclaimedController() {
        val controller = VirtualDisplaySessionController()
        val sibling = VirtualDisplaySessionController()
        val controllerSnapshots = snapshots(controller)
        val siblingSnapshots = snapshots(sibling)
        val oldControllerSnapshot = controllerSnapshots.value
        val oldSiblingSnapshot = siblingSnapshots.value
        try {
            controllerSnapshots.value = session("reservation-session").copy(ownerRunId = "reservation-run")
            siblingSnapshots.value = session("sibling-session").copy(displayId = 8, ownerRunId = "sibling-run")
            val oldStamp = controller.reservationStamp()
            controller.releaseCurrent(null, rootAvailable = { false })
            controllerSnapshots.value = session("reservation-session").copy(ownerRunId = "")
            assertTrue(controller.claimRunOwnership("reservation-run"))
            val newStamp = controller.reservationStamp()
            assertNotEquals(oldStamp, newStamp)

            var actionRan = false
            assertNull(controller.withRunReservation("reservation-run", oldStamp) { actionRan = true })
            assertFalse(actionRan)
            assertFalse(controller.releaseForOwner("reservation-run", null, expectedReleaseGeneration = oldStamp.first))
            assertEquals("reservation-run", controllerSnapshots.value.ownerRunId)
            assertEquals("sibling-run", siblingSnapshots.value.ownerRunId)
            assertEquals("sibling-session", siblingSnapshots.value.sessionId)

            controller.shutdownExecutors()
            assertFalse(controller.claimRunOwnership("reservation-run"))
            assertNull(controller.withRunReservation("reservation-run", newStamp) { actionRan = true })
        } finally {
            controllerSnapshots.value = oldControllerSnapshot
            siblingSnapshots.value = oldSiblingSnapshot
            controller.shutdownExecutors()
            sibling.shutdownExecutors()
        }
    }

    @Test fun releasedSessionRemainsSelectableByItsPreviewOwner() {
        val controller = VirtualDisplaySessionController()
        val snapshots = snapshots(controller)
        val oldSnapshot = snapshots.value
        val registry = VirtualDisplayController::class.java.getDeclaredField("registry").apply { isAccessible = true }
            .get(VirtualDisplayController)
        val register = registry.javaClass.getDeclaredMethod("register", VirtualDisplaySessionController::class.java)
            .apply { isAccessible = true }
        val unregister = registry.javaClass.getDeclaredMethod("unregister", String::class.java, VirtualDisplaySessionController::class.java)
            .apply { isAccessible = true }
        try {
            snapshots.value = session("preview-session").copy(displayId = 34, ownerRunId = "preview-run")
            register.invoke(registry, controller)
            val originalLease = snapshots.value.lease!!
            assertTrue(VirtualDisplayController.releaseLease(originalLease))
            assertEquals("", snapshots.value.ownerRunId)
            assertEquals("preview-run", snapshots.value.previewOwnerRunId)

            assertNull(VirtualDisplayController.chooseSession("other-run", "preview-session"))
            val selected = VirtualDisplayController.chooseSession("preview-run", "preview-session")
            assertNotEquals(originalLease, selected)
            assertFalse(VirtualDisplayController.validate(originalLease))
            assertTrue(VirtualDisplayController.validate(selected))
            assertEquals("preview-run", snapshots.value.ownerRunId)
            assertEquals("", snapshots.value.previewOwnerRunId)
        } finally {
            unregister.invoke(registry, "preview-session", controller)
            snapshots.value = oldSnapshot
            controller.shutdownExecutors()
        }
    }

    @Test fun gestureDispatchIsFencedAgainstLeaseTransferAndRejectsQueuedStaleLease() {
        val snapshots = snapshots(); val old = snapshots.value
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transferStarted = CountDownLatch(1)
        try {
            snapshots.value = session("dispatch").copy(ownerRunId = "run-a")
            val leaseA = VirtualDisplayController.claimLease("run-a")!!
            val dispatch = pool.submit<Boolean> {
                VirtualDisplayController.dispatchIfLeaseCurrent(leaseA) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val transfer = pool.submit<VirtualDisplayLease?> {
                transferStarted.countDown()
                check(VirtualDisplayController.releaseLease(leaseA))
                VirtualDisplayController.claimLease("run-b")
            }
            assertTrue(transferStarted.await(2, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { transfer.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(dispatch.get(2, TimeUnit.SECONDS))
            val leaseB = transfer.get(2, TimeUnit.SECONDS)!!
            assertEquals("run-b", leaseB.ownerRunId)
            var staleDispatchRan = false
            assertFalse(VirtualDisplayController.dispatchIfLeaseCurrent(leaseA) { staleDispatchRan = true })
            assertFalse(staleDispatchRan)
        } finally {
            release.countDown()
            pool.shutdownNow()
            snapshots.value = old
        }
    }

    @Test fun queuedManualInputCannotTouchARecoveredDisplayWithTheSameNumericId() {
        val snapshots = snapshots(); val old = snapshots.value
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        var rootChecks = 0
        try {
            snapshots.value = session("old-session")
            VirtualDisplayController.dispatchUserTapOnCanvas(.5f, .5f, rootAvailable = { rootChecks++; false }, withControl = { action ->
                entered.countDown()
                try { check(release.await(2, TimeUnit.SECONDS)); action() } finally { finished.countDown() }
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            snapshots.value = session("new-session")
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, rootChecks)
        } finally { release.countDown(); snapshots.value = old }
    }

    @Test fun queuedManualInputCannotTouchTheSameSessionAfterItsOwnerChanges() {
        val snapshots = snapshots(); val old = snapshots.value
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        var rootChecks = 0
        try {
            snapshots.value = session("same-session").copy(ownerRunId = "run-a")
            VirtualDisplayController.dispatchUserTapOnCanvas(
                .5f,
                .5f,
                rootAvailable = { rootChecks++; false },
                lease = lease("run-a", "same-session"),
                withControl = { action ->
                    entered.countDown()
                    try {
                        check(release.await(2, TimeUnit.SECONDS))
                        action()
                    } finally {
                        finished.countDown()
                    }
                },
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            snapshots.value = session("same-session").copy(ownerRunId = "run-b", manualInputGeneration = 1)
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, rootChecks)
        } finally {
            release.countDown()
            snapshots.value = old
        }
    }

    @Test fun screenshotCannotReturnABitmapFromAnotherDisplayOrSessionOrWhileRecovering() {
        val snapshots = snapshots(); val old = snapshots.value
        val bitmapField = VirtualDisplaySessionController::class.java.getDeclaredField("latestFullBitmap").apply { isAccessible = true }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val previous = bitmapField.get(VirtualDisplayController.primaryForTest)
        try {
            snapshots.value = session("current")
            bitmapField.set(VirtualDisplayController.primaryForTest, bitmap)
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 5))
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4, expectedSessionId = "previous"))
            val ownedLease = VirtualDisplayController.claimLease("run-a")
            assertNotNull(ownedLease)
            val claimedBitmap = VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4, lease = ownedLease)
            assertNotNull(claimedBitmap)
            claimedBitmap!!.recycle()
            assertEquals("run-a", snapshots.value.ownerRunId)
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4, lease = lease("run-b", "current")))
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4, lease = lease("run-a", "stale")))
            snapshots.value = session("current").copy(active = false, lifecycle = VirtualDisplayLifecycle.RECOVERING)
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4))
            snapshots.value = session("current")
            val freshLease = VirtualDisplayController.claimLease("run-a")
            val result = VirtualDisplayController.captureScreenshotBitmap(
                0, expectedDisplayId = 4, expectedSessionId = "current", lease = freshLease,
            )
            assertNotNull(result); result!!.recycle()
        } finally {
            bitmapField.set(VirtualDisplayController.primaryForTest, previous)
            snapshots.value = old
            bitmap.recycle()
        }
    }

    @Test fun onlyOneRunCanClaimAnOwnerlessSessionAndCloseReleasesWithoutStoppingIt() {
        val snapshots = snapshots(); val old = snapshots.value
        try {
            snapshots.value = session("reusable").copy(ownerRunId = "")
            val leaseA = VirtualDisplayController.claimLease("run-a")
            assertEquals(lease("run-a", "reusable"), leaseA)
            assertEquals("run-a", snapshots.value.ownerRunId)
            assertEquals(leaseA, VirtualDisplayController.claimLease("run-a"))
            assertNull(VirtualDisplayController.claimLease("run-b"))
            assertEquals("run-a", snapshots.value.ownerRunId)

            assertTrue(VirtualDisplayController.releaseLease(leaseA))
            assertEquals("", snapshots.value.ownerRunId)
            assertTrue(snapshots.value.active)
            val leaseB = VirtualDisplayController.claimLease("run-b")
            assertEquals(lease("run-b", "reusable"), leaseB)
            assertEquals("run-b", snapshots.value.ownerRunId)
            assertFalse(VirtualDisplayController.releaseLease(leaseA))
            assertEquals("run-b", snapshots.value.ownerRunId)
        } finally { snapshots.value = old }
    }

    @Test fun userControlsAndHandoffRequireTheCurrentDisplayOwner() {
        val snapshots = snapshots(); val old = snapshots.value
        try {
            snapshots.value = session("owned").copy(ownerRunId = "run-a", userControlling = false)
            VirtualDisplayController.setUserControlling(true, lease = lease("run-b", "owned"))
            assertFalse(snapshots.value.userControlling)
            VirtualDisplayController.setUserControlling(true, lease = lease("run-a", "owned"))
            assertTrue(snapshots.value.userControlling)
            assertFalse(VirtualDisplayController.handoffToMainDisplay(
                android.app.Application(), lease = lease("run-b", "owned"), rootAvailable = { false },
            ))
            assertTrue(VirtualDisplayController.validate(lease("run-a", "owned")))
            assertEquals("run-a", snapshots.value.ownerRunId)
        } finally { snapshots.value = old }
    }

    @Test fun aLeaseFromAReplacedSessionCannotOperateUntilTheOwnerReacquires() {
        val snapshots = snapshots(); val old = snapshots.value
        try {
            snapshots.value = session("session-a").copy(ownerRunId = "run-a")
            val staleLease = VirtualDisplayController.claimLease("run-a")
            assertEquals(lease("run-a", "session-a"), staleLease)

            snapshots.value = session("session-b").copy(ownerRunId = "run-a")
            assertFalse(VirtualDisplayController.validate(staleLease))
            assertNull(VirtualDisplayController.captureScreenshotBitmap(0, expectedDisplayId = 4, lease = staleLease))

            val renewedLease = VirtualDisplayController.claimLease("run-a")
            assertEquals(lease("run-a", "session-b"), renewedLease)
            assertTrue(VirtualDisplayController.validate(renewedLease))
            assertFalse(VirtualDisplayController.validate(staleLease))
        } finally { snapshots.value = old }
    }

    @Test fun aLeaseFromAnotherOwnerCannotOperateTheSameSession() {
        val snapshots = snapshots(); val old = snapshots.value
        try {
            snapshots.value = session("shared").copy(ownerRunId = "run-a")
            val leaseA = VirtualDisplayController.claimLease("run-a")
            assertEquals(lease("run-a", "shared"), leaseA)

            snapshots.value = session("shared").copy(ownerRunId = "run-b")
            assertFalse(VirtualDisplayController.validate(leaseA))
            assertNull(VirtualDisplayController.claimLease("run-a"))

            val leaseB = VirtualDisplayController.claimLease("run-b")
            assertEquals(lease("run-b", "shared"), leaseB)
            assertTrue(VirtualDisplayController.validate(leaseB))
            assertFalse(VirtualDisplayController.validate(leaseA))
            assertEquals("run-b", snapshots.value.ownerRunId)
        } finally { snapshots.value = old }
    }

    @Test fun releasingAStaleLeaseKeepsTheNewerSessionOfTheSameRunOwned() {
        val snapshots = snapshots(); val old = snapshots.value
        try {
            snapshots.value = session("session-a").copy(ownerRunId = "run-a")
            val staleLease = VirtualDisplayController.claimLease("run-a")
            snapshots.value = session("session-b").copy(ownerRunId = "run-a")

            assertFalse(VirtualDisplayController.releaseLease(staleLease))
            assertEquals("run-a", snapshots.value.ownerRunId)

            val renewedLease = VirtualDisplayController.claimLease("run-a")
            assertTrue(VirtualDisplayController.releaseLease(renewedLease))
            assertEquals("", snapshots.value.ownerRunId)
            assertTrue(snapshots.value.active)
        } finally { snapshots.value = old }
    }

    @Test fun staleHealthProbeCannotRecoverAReplacementSession() {
        val probe = VirtualDisplayHealthProbe("old-session", 4, 9)
            val generation = VirtualDisplaySessionController::class.java
            .getDeclaredField("healthScheduleGeneration").apply { isAccessible = true }
            .get(VirtualDisplayController.primaryForTest) as java.util.concurrent.atomic.AtomicLong
        val previous = generation.get()
        try {
            generation.set(9L)
            assertTrue(VirtualDisplayController.isHealthProbeCurrentLocked(probe, session("old-session")))
            assertFalse(VirtualDisplayController.isHealthProbeCurrentLocked(probe, session("new-session")))
            assertFalse(VirtualDisplayController.isHealthProbeCurrentLocked(probe, session("old-session").copy(displayId = 5)))
            generation.set(10L)
            assertFalse(VirtualDisplayController.isHealthProbeCurrentLocked(probe, session("old-session")))
        } finally {
            generation.set(previous)
        }
    }
}
