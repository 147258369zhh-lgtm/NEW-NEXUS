package io.github.mangi.eta.agent.packages

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Captured by the package executor before crossing Binder threads. Frames belong only to the active invocation. */
internal object PackageUiOutput {
    private val current = ThreadLocal<((AgentEvent.PluginUiFrame) -> Unit)?>()
    val sink: ((AgentEvent.PluginUiFrame) -> Unit)? get() = current.get()
    fun <T> withSink(sink: (AgentEvent.PluginUiFrame) -> Unit, execute: () -> T): T {
        val old = current.get(); current.set(sink)
        return try { execute() } finally { if (old == null) current.remove() else current.set(old) }
    }
}
