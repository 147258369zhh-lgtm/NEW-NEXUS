package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Visible native slots and panels share their host stream; closing one view only retires its lease. */
internal object DshSharedSessions {
    private data class Key(val context: Context, val id: String, val endpoint: String, val revision: Long, val configurationRevision: Long)
    private class Entry(val session: DshUiSession, var references: Int = 0) {
        val visible = MutableStateFlow(0)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init { scope.launch {
            combine(visible, session.fileHold) { count, hold -> count > 0 || hold }.distinctUntilChanged().collectLatest { active ->
                if (active) session.maintainConnection()
            }
        } }
        fun close() { scope.cancel(); session.close() }
    }
    private val entries = linkedMapOf<Key, Entry>()
    suspend fun acquireAsync(context: Context, repository: DshHostRepository, host: SavedConnectorConfig): Lease {
        val owned = AtomicReference<Lease?>()
        return try { withContext(Dispatchers.IO) { acquire(context, repository, host).also(owned::set) } }
        catch (failure: Exception) { owned.getAndSet(null)?.close(); throw failure }
    }

    fun acquire(context: Context, repository: DshHostRepository, host: SavedConnectorConfig): Lease = synchronized(entries) {
        check(repository.isCurrent(host)) { "DSH 主机已停用或更新" }
        val key = Key(context.applicationContext, host.id, host.endpoint, host.credentialRevision, host.configurationRevision)
        entries.keys.filter { it.context === key.context && it.id == host.id && it != key }.forEach { stale -> entries.remove(stale)?.close() }
        val entry = entries.getOrPut(key) { Entry(DshUiSession(repository, host)) }
        entry.references++
        Lease(entry.session, onVisibility = { shown -> synchronized(entries) { entry.visible.value += if (shown) 1 else -1 } }) {
            synchronized(entries) {
                entry.references--
                if (entry.references == 0) { if (entries[key] === entry) entries.remove(key); entry.close() }
            }
        }
    }

    class Lease internal constructor(val session: DshUiSession, private val onVisibility: (Boolean) -> Unit, private val release: () -> Unit) : AutoCloseable {
        private var closed = false
        private var shown = false
        @Synchronized fun visible(value: Boolean) { if (!closed && value != shown) { shown = value; onVisibility(value) } }
        @Synchronized override fun close() { if (!closed) { closed = true; if (shown) { shown = false; onVisibility(false) }; release() } }
    }
}
