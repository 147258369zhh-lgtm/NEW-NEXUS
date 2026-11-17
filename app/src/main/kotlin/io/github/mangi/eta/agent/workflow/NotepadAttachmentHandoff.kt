package io.github.mangi.eta.agent.workflow

/** Owns copied files until the editor has accepted their names. */
internal class NotepadAttachmentHandoff(private val cleanup: (List<String>) -> Unit) {
    private val pending = mutableSetOf<String>()
    private var disposed = false

    @Synchronized fun offer(name: String): Boolean {
        if (disposed) return false
        pending.add(name)
        return true
    }

    @Synchronized fun deliver(name: String, accept: () -> Unit) {
        if (!disposed && name in pending) {
            accept()
            pending.remove(name)
        }
    }

    fun dispose() {
        val abandoned = synchronized(this) {
            disposed = true
            pending.toList().also { pending.clear() }
        }
        if (abandoned.isNotEmpty()) cleanup(abandoned)
    }
}
