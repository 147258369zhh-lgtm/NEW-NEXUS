package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class McpDiscoveryBatchTest {
    @Test fun hostsPrepareInParallelWithinTwoSlotsAndKeepUserOrderAndOwner() {
        val controller = AgentRunController()
        RuntimeInvocationScope.withController(controller) { runBlocking(RuntimeInvocationScope.coroutineContext()) {
            val arrivals = Channel<Int>(4)
            val release = Channel<Unit>(4)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val result = async { discoverMcpBatch(listOf(0, 1, 2, 3)) { value ->
                assertSame(controller, RuntimeInvocationScope.controller)
                maximum.accumulateAndGet(active.incrementAndGet()) { first, second -> maxOf(first, second) }
                arrivals.send(value)
                release.receive()
                active.decrementAndGet()
                value * 2
            } }
            withTimeout(5000) {
                val first = setOf(arrivals.receive(), arrivals.receive())
                assertEquals(2, first.size)
                assertNull(arrivals.tryReceive().getOrNull())
                repeat(2) { release.send(Unit) }
                val second = setOf(arrivals.receive(), arrivals.receive())
                assertEquals(setOf(0, 1, 2, 3), first + second)
                repeat(2) { release.send(Unit) }
                assertEquals(listOf(0, 2, 4, 6), result.await())
            }
            assertEquals(2, maximum.get())
        } }
    }
}
