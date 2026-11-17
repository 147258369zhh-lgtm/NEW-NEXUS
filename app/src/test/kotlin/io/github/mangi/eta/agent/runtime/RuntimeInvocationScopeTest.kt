package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*

class RuntimeInvocationScopeTest {
    @Test fun suspendingToolRestoresItsOwnerAcrossDispatcherHops() {
        val controller = AgentRunController()
        RuntimeInvocationScope.withController(controller) {
            runBlocking(RuntimeInvocationScope.coroutineContext()) {
                withContext(Dispatchers.IO) {
                    yield()
                    assertSame(controller, RuntimeInvocationScope.controller)
                    withContext(Dispatchers.Default) {
                        delay(1)
                        assertSame(controller, RuntimeInvocationScope.controller)
                    }
                    assertSame(controller, RuntimeInvocationScope.controller)
                }
                assertSame(controller, RuntimeInvocationScope.controller)
            }
        }
        assertNull(RuntimeInvocationScope.controller)
    }

    @Test fun nestedScopeRestoresParentAndExceptionalReturnReleasesTheThread() {
        val parent=AgentRunController();val child=AgentRunController()
        assertNull(RuntimeInvocationScope.controller)
        RuntimeInvocationScope.withController(parent) {
            assertSame(parent,RuntimeInvocationScope.controller)
            assertThrows(IllegalStateException::class.java) { RuntimeInvocationScope.withController(child) {
                assertSame(child,RuntimeInvocationScope.controller);error("fixture")
            } }
            assertSame(parent,RuntimeInvocationScope.controller)
        }
        assertNull(RuntimeInvocationScope.controller)
    }

    @Test fun concurrentChildThreadsCannotBorrowAnotherRunsController() {
        val entered=CountDownLatch(2)
        val failure=AtomicReference<Throwable>()
        val workers=(1..2).map { thread {
            try {
                val owner=AgentRunController()
                RuntimeInvocationScope.withController(owner) {
                    entered.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS))
                    assertSame(owner,RuntimeInvocationScope.controller)
                }
                assertNull(RuntimeInvocationScope.controller)
            } catch(e:Throwable) { failure.set(e) }
        } }
        workers.forEach { it.join(3_000);assertFalse(it.isAlive) }
        assertNull(failure.get())
    }
}
