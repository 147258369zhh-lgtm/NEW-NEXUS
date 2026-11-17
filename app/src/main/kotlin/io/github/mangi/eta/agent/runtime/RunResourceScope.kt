package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels one nested host invocation while its enclosing Run may continue. */
internal class RunResourceScope : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val cancellations = CopyOnWriteArraySet<() -> Unit>()
    val isClosed get() = closed.get()
    fun checkActive() { if (isClosed) throw AgentRunCancelledException() }
    fun register(cancel: () -> Unit): AutoCloseable {
        cancellations.add(cancel)
        if (isClosed) runCatching(cancel)
        return AutoCloseable { cancellations.remove(cancel) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancellations.forEach { runCatching(it) }
        cancellations.clear()
    }
}
