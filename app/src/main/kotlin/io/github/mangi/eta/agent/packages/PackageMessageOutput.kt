package io.github.mangi.eta.agent.packages

/** Captured at invocation entry; Binder callback threads use the captured sink, not their own thread-local state. */
internal object PackageMessageOutput {
    private val current = ThreadLocal<((String) -> Unit)?>()
    val sink: ((String) -> Unit)? get() = current.get()
    fun <T> withSink(sink: (String) -> Unit, execute: () -> T): T {
        val old = current.get(); current.set(sink)
        return try { execute() } finally { if (old == null) current.remove() else current.set(old) }
    }
}
