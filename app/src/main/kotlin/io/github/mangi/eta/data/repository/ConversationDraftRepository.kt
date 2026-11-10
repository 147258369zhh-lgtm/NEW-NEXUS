package io.github.mangi.eta.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.data.model.ConversationSubject
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class ConversationDraft(val text: String, val selectionStart: Int = text.length, val selectionEnd: Int = selectionStart) {
    fun normalized() = copy(selectionStart = selectionStart.coerceIn(0, text.length), selectionEnd = selectionEnd.coerceIn(0, text.length))
}

/**
 * Main-thread callers only enqueue immutable snapshots. One IO consumer preserves ordering across
 * page disposal, reopening, clearing and backup flushes. This store never sends or queues a message.
 */
internal class ConversationDraftRepository(
    private val preferences: SharedPreferences,
    scope: CoroutineScope,
) {
    private sealed interface Command {
        data class Read(val key: String, val result: CompletableDeferred<ConversationDraft?>) : Command
        data class Write(val key: String, val pending: PendingWrite, val result: CompletableDeferred<Unit>) : Command
        data class Flush(val result: CompletableDeferred<Unit>) : Command
        data class Clear(val result: CompletableDeferred<Unit>) : Command
    }
    private data class PendingWrite(val draft: ConversationDraft?)
    // Identity matters: retiring one mounted editor must not retire a separately opened reader.
    internal class Lease(val subject: ConversationSubject, val epoch: Long, val generation: Long)

    private val lock = Any()
    private val epochs = mutableMapOf<ConversationSubject, Long>()
    private data class ActiveEditor(val lease: Lease, val read: () -> ConversationDraft?)
    private val activeEditors = linkedMapOf<Any, ActiveEditor>()
    private val retiredEditors = Collections.newSetFromMap(WeakHashMap<Lease, Boolean>())
    // A cached null is a deliberate clear, not an unloaded draft. Keep only a small recent set.
    private val recent = LinkedHashMap<String, ConversationDraft?>(32, 0.75f, true)
    // Pending/failed input is not an LRU entry: eviction must never discard the only unsaved copy.
    private val pendingWrites = mutableMapOf<String, PendingWrite>()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var generation = 0L
    private val writeFailures = mutableMapOf<String, Throwable>()
    private val worker: Job

    init {
        worker = scope.launch(Dispatchers.IO) {
            for (command in commands) when (command) {
                is Command.Read -> command.result.completeWith(runCatching { read(command.key) })
                is Command.Write -> {
                    val result = runCatching { write(command.key, command.pending.draft) }
                    if (result.isSuccess) synchronized(lock) {
                        if (pendingWrites[command.key] === command.pending) pendingWrites.remove(command.key)
                    }
                    result.exceptionOrNull()?.let { writeFailures[command.key] = it } ?: writeFailures.remove(command.key)
                    command.result.completeWith(result)
                }
                is Command.Clear -> {
                    val result = runCatching { clearPreferences() }
                    if (result.isSuccess) writeFailures.clear() else writeFailures["clear"] = result.exceptionOrNull()!!
                    command.result.completeWith(result)
                }
                is Command.Flush -> {
                    val failure = writeFailures.values.firstOrNull()
                    if (failure == null) command.result.complete(Unit) else command.result.completeExceptionally(failure)
                }
            }
        }
        worker.invokeOnCompletion { failure ->
            commands.close(failure)
            while (true) {
                val command = commands.tryReceive().getOrNull() ?: break
                val error = failure ?: IllegalStateException("Draft store closed")
                when (command) {
                    is Command.Read -> command.result.completeExceptionally(error)
                    is Command.Write -> command.result.completeExceptionally(error)
                    is Command.Flush -> command.result.completeExceptionally(error)
                    is Command.Clear -> command.result.completeExceptionally(error)
                }
            }
        }
    }

    fun open(subject: ConversationSubject): Lease = synchronized(lock) { Lease(subject, epochs[subject] ?: 0, generation) }

    /** Reader is invoked only by main-thread input/share actions, never by the IO worker. */
    @androidx.annotation.MainThread
    fun registerActive(lease: Lease, acceptHandoff: Boolean = true, read: () -> ConversationDraft?): Closeable {
        val token = Any()
        val previous = synchronized(lock) {
            activeEditors.values.lastOrNull { it.lease.subject == lease.subject && valid(it.lease) }
        }
        val handoff = if (acceptHandoff && previous?.lease !== lease) previous?.read?.invoke() else null
        synchronized(lock) {
            if (!valid(lease)) return Closeable {}
            activeEditors.values.filter { it.lease.subject == lease.subject && it.lease !== lease }
                .forEach { retiredEditors.add(it.lease) }
            // Capture live input before invalidating the old page's late onDispose. The receiving
            // editor restores this snapshot even when the previous 300 ms debounce never ran.
            if (handoff != null) enqueueWrite(lease.subject.key, handoff.normalized().takeUnless { it.text.isEmpty() })
            activeEditors[token] = ActiveEditor(lease, read)
        }
        return Closeable { synchronized(lock) { activeEditors.remove(token) } }
    }

    @androidx.annotation.MainThread
    fun activeDraft(subject: ConversationSubject): ConversationDraft? {
        val editor = synchronized(lock) { activeEditors.values.lastOrNull { it.lease.subject == subject && valid(it.lease) } }
        return editor?.read?.invoke()
    }

    suspend fun load(lease: Lease): ConversationDraft? {
        val key = lease.subject.key
        synchronized(lock) {
            if (!valid(lease)) return null
            pendingWrites[key]?.let { return it.draft }
            if (recent.containsKey(key)) return recent[key]
        }
        val result = CompletableDeferred<ConversationDraft?>()
        if (commands.trySend(Command.Read(key, result)).isFailure) error("Draft store closed")
        val stored = result.await()
        return synchronized(lock) {
            if (!valid(lease)) null else if (pendingWrites.containsKey(key)) pendingWrites[key]?.draft
            else if (recent.containsKey(key)) recent[key] else stored
        }
    }

    fun save(lease: Lease, draft: ConversationDraft): Deferred<Unit> = synchronized(lock) {
        if (!valid(lease)) return@synchronized CompletableDeferred(Unit)
        val normalized = draft.normalized()
        val value = normalized.takeUnless { it.text.isEmpty() }
        enqueueWrite(lease.subject.key, value)
    }

    /** Reopening keeps unsaved cached input visible and retries its last failed/pending disk write. */
    fun retryCached(lease: Lease): Deferred<Unit>? = synchronized(lock) {
        val key = lease.subject.key
        if (!valid(lease) || (!pendingWrites.containsKey(key) && !recent.containsKey(key))) return@synchronized null
        enqueueWrite(key, pendingWrites[key]?.draft ?: recent[key])
    }

    /** Invalidate old mounted editors before clearing, so late onDispose cannot recreate a deleted draft. */
    fun remove(subject: ConversationSubject): Deferred<Unit> = synchronized(lock) {
        epochs[subject] = (epochs[subject] ?: 0) + 1
        enqueueWrite(subject.key, null)
    }

    suspend fun flush() {
        val result = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Flush(result)).isFailure) error("Draft store closed")
        result.await()
    }

    fun clearAll(): Deferred<Unit> = synchronized(lock) {
        generation++
        recent.clear()
        pendingWrites.clear()
        epochs.clear()
        val result = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Clear(result)).isFailure) result.completeExceptionally(IllegalStateException("Draft store closed"))
        result
    }

    private fun valid(lease: Lease) = lease.generation == generation &&
        lease.epoch == (epochs[lease.subject] ?: 0) && lease !in retiredEditors

    /** Caller holds lock, keeping the visible snapshot and command order atomic. */
    private fun enqueueWrite(key: String, draft: ConversationDraft?): Deferred<Unit> {
        val pending = PendingWrite(draft)
        pendingWrites[key] = pending
        cache(key, draft)
        val result = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Write(key, pending, result)).isFailure) {
            result.completeExceptionally(IllegalStateException("Draft store closed"))
        }
        return result
    }

    private fun cache(key: String, draft: ConversationDraft?) {
        recent[key] = draft
        while (recent.size > 32) recent.remove(recent.keys.first())
    }

    private fun read(key: String): ConversationDraft? {
        val encoded = preferences.getString(key, null) ?: return null
        val json = JSONObject(encoded)
        require(json.getInt("version") == 1) { "Unsupported draft version" }
        return ConversationDraft(json.getString("text"), json.getInt("start"), json.getInt("end")).normalized()
    }

    @SuppressLint("UseKtx") // Persistence failures must remain visible; KTX edit hides commit's result.
    private fun write(key: String, draft: ConversationDraft?) {
        val editor = preferences.edit()
        if (draft == null) editor.remove(key) else editor.putString(key, JSONObject()
            .put("version", 1).put("text", draft.text).put("start", draft.selectionStart).put("end", draft.selectionEnd).toString())
        check(editor.commit()) { "Draft write failed" }
    }

    @SuppressLint("UseKtx") // The boolean commit result is required, including for import cleanup.
    private fun clearPreferences() { check(preferences.edit().clear().commit()) { "Draft clear failed" } }

    companion object {
        const val PREFERENCES_NAME = "eta_conversation_drafts"
        @Volatile private var instance: ConversationDraftRepository? = null

        fun get(context: Context): ConversationDraftRepository {
            val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return instance?.takeIf { it.preferences === preferences } ?: synchronized(this) {
                instance?.takeIf { it.preferences === preferences } ?: ConversationDraftRepository(
                    preferences, CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { created -> instance?.worker?.cancel(); instance = created }
            }
        }
    }
}
