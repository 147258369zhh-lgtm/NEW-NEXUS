package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound preparation concurrency; preserve user order and the caller's cancellation/resource owner. */
internal suspend fun <T, R> discoverMcpBatch(values: List<T>, operation: suspend (T) -> R): List<R> {
    val invocation = RuntimeInvocationScope.coroutineContext()
    return coroutineScope {
        val slots = Semaphore(2)
        values.map { value -> async(Dispatchers.IO + invocation) { slots.withPermit { operation(value) } } }.awaitAll()
    }
}
