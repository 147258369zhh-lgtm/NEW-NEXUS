package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide admission after each parent Run has applied its own parallelism limit. */
internal class SubagentConcurrencyGate(limit: Int = DEFAULT_GLOBAL_LIMIT) {
    private val permits = Semaphore(limit.coerceIn(1, MAX_GLOBAL_LIMIT), true)
    private val waiting = AtomicInteger()

    fun <T> withPermit(
        controller: AgentRunController,
        onQueued: (position: Int) -> Unit = {},
        onAdmitted: (waitedMillis: Long) -> Unit = {},
        block: () -> T,
    ): T {
        val waitStarted = System.nanoTime()
        var queuePosition: Int? = null
        var acquired = permits.tryAcquire()
        try {
        if (!acquired) {
            val position = waiting.incrementAndGet()
            queuePosition = position
            onQueued(position)
        }
        while (!acquired) {
            controller.throwIfCancelled()
            acquired = permits.tryAcquire(250, TimeUnit.MILLISECONDS)
        }
            controller.throwIfCancelled()
            if (queuePosition != null) {
                waiting.decrementAndGet()
                onAdmitted((System.nanoTime() - waitStarted) / 1_000_000)
                queuePosition = null
            }
            return block()
        } finally {
            if (queuePosition != null) waiting.decrementAndGet()
            if (acquired) permits.release()
        }
    }

    internal fun availablePermits(): Int = permits.availablePermits()
    internal fun waitingCount(): Int = waiting.get()

    companion object {
        const val DEFAULT_GLOBAL_LIMIT = 6
        const val MAX_GLOBAL_LIMIT = 16
        val shared = SubagentConcurrencyGate()
    }
}
