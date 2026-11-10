package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class AgentRunStoreCancellationTest {
    private fun store() = AgentRunStore(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> }

    @Test
    fun stoppingLocalFallbackCancelsProviderResourcesOnce() {
        val store = store()
        val controller = AgentRunController()
        var cancellations = 0
        controller.register { cancellations++ }
        store.register("run", "conversation")
        store.registerLocalCancellation("run", controller::cancel)

        assertTrue(store.requestStop("run", byUser = true))
        assertFalse(store.requestStop("run", byUser = true))
        assertTrue(controller.isCancelled)
        assertTrue(store.wasStoppedByUser("run"))
        assertEquals(1, cancellations)
    }

    @Test
    fun stopWhileRuntimeIsFailingAlsoCancelsLateFallback() {
        val store = store()
        val controller = AgentRunController()
        store.register("run", "conversation")
        store.requestStop("run")
        store.registerLocalCancellation("run", controller::cancel)
        assertTrue(controller.isCancelled)
    }

    @Test
    fun removedRunCannotStartLocalFallback() {
        val store = store()
        val controller = AgentRunController()
        store.register("run", "conversation")
        store.remove("run")
        store.registerLocalCancellation("run", controller::cancel)
        assertTrue(controller.isCancelled)
    }
}
