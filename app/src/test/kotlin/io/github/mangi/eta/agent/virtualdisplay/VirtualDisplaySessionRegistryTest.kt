package io.github.mangi.eta.agent.virtualdisplay

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class VirtualDisplaySessionRegistryTest {
    @Suppress("UNCHECKED_CAST")
    private fun mutableSnapshots(controller: VirtualDisplaySessionController) =
        VirtualDisplaySessionController::class.java.getDeclaredField("mutableSnapshots")
            .apply { isAccessible = true }.get(controller) as MutableStateFlow<VirtualDisplaySnapshot>

    private fun controller(sessionId: String, displayId: Int, ownerRunId: String): VirtualDisplaySessionController =
        VirtualDisplaySessionController().also { controller ->
            mutableSnapshots(controller).value = VirtualDisplaySnapshot(
                active = true,
                displayId = displayId,
                width = 100,
                height = 200,
                sessionId = sessionId,
                ownerRunId = ownerRunId,
                lifecycle = VirtualDisplayLifecycle.ACTIVE,
            )
        }

    @Test fun leaseAndSessionLookupRemainIndependentAcrossRegisteredDisplays() {
        val registry = VirtualDisplaySessionRegistry()
        val a = controller("session-a", 4, "run-a")
        val b = controller("session-b", 5, "run-b")
        registry.register(a)
        registry.register(b)
        val leaseA = VirtualDisplayLease("run-a", "session-a", 4)
        val leaseB = VirtualDisplayLease("run-b", "session-b", 5)

        assertSame(a, registry.resolve("session-a"))
        assertSame(b, registry.resolve("session-b"))
        assertSame(a, registry.resolve(leaseA))
        assertSame(b, registry.resolve(leaseB))
        assertTrue(registry.release(leaseA))
        assertNull(registry.resolve(leaseA))
        assertSame(b, registry.resolve("session-b"))
        assertSame(b, registry.resolve(leaseB))
        assertEquals("run-b", b.snapshots.value.ownerRunId)
        assertTrue(registry.unregister("session-a", a))
        assertNull(registry.resolve("session-a"))
        assertSame(b, registry.resolve("session-b"))
    }

    @Test fun ownerIndexIncludesMultipleSessionsAndLeaseResolutionRejectsStaleIdentity() {
        val registry = VirtualDisplaySessionRegistry()
        val a = controller("session-a", 4, "shared-run")
        val b = controller("session-b", 5, "shared-run")
        val c = controller("session-c", 6, "other-run")
        listOf(a, b, c).forEach(registry::register)

        assertEquals(listOf(a, b), registry.sessionsForOwner("shared-run"))
        assertEquals(listOf(c), registry.sessionsForOwner("other-run"))
        assertTrue(registry.resolve(VirtualDisplayLease("shared-run", "session-a", 5)) == null)
        assertTrue(registry.resolve(VirtualDisplayLease("other-run", "session-a", 4)) == null)

        val stale = VirtualDisplayLease("shared-run", "session-a", 4)
        mutableSnapshots(a).value = a.snapshots.value.copy(sessionId = "replacement-a", displayId = 4)
        assertNull(registry.resolve(stale))
        assertNull(registry.resolve("session-a"))
        assertSame(b, registry.resolve("session-b"))
    }
}
