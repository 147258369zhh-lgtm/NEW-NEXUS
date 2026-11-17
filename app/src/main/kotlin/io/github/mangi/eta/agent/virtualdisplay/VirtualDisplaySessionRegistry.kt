package io.github.mangi.eta.agent.virtualdisplay

/** Resolves independently owned display sessions without relying on a process-wide primary session. */
internal class VirtualDisplaySessionRegistry {
    private val lock = Any()
    private val sessions = linkedMapOf<String, VirtualDisplaySessionController>()

    fun register(controller: VirtualDisplaySessionController) = synchronized(lock) {
        val sessionId = controller.snapshots.value.sessionId
        sessions.entries.removeAll { (registeredId, registeredController) ->
            registeredController === controller && registeredId != sessionId
        }
        if (sessionId.isBlank()) return@synchronized
        sessions[sessionId] = controller
    }

    fun unregister(sessionId: String, controller: VirtualDisplaySessionController? = null): Boolean = synchronized(lock) {
        val current = sessions[sessionId] ?: return@synchronized false
        if (controller != null && current !== controller) return@synchronized false
        sessions.remove(sessionId)
        true
    }

    fun unregisterController(controller: VirtualDisplaySessionController) = synchronized(lock) {
        sessions.entries.removeAll { it.value === controller }
    }

    fun resolve(sessionId: String): VirtualDisplaySessionController? = synchronized(lock) {
        sessions[sessionId]?.takeIf { it.snapshots.value.sessionId == sessionId }
    }

    fun resolve(lease: VirtualDisplayLease): VirtualDisplaySessionController? = synchronized(lock) {
        sessions[lease.sessionId]?.takeIf { controller ->
            controller.snapshots.value.displayId == lease.displayId && controller.validate(lease)
        }
    }

    fun sessionsForOwner(ownerRunId: String): List<VirtualDisplaySessionController> = synchronized(lock) {
        if (ownerRunId.isBlank()) return@synchronized emptyList()
        sessions.values.filter {
            val snapshot = it.snapshots.value
            snapshot.ownerRunId == ownerRunId || (snapshot.ownerRunId.isBlank() && snapshot.previewOwnerRunId == ownerRunId)
        }
    }

    fun activeSessions(): List<VirtualDisplaySessionController> = synchronized(lock) {
        sessions.values.filter { it.snapshots.value.active }
    }

    fun release(lease: VirtualDisplayLease): Boolean = resolve(lease)?.releaseLease(lease) ?: false
}
