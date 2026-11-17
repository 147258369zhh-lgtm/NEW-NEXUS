package io.github.mangi.eta.agent.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Tracks native owners and queued callers so stopping ASR never waits for unrelated TTS. */
internal class LocalSpeechOperations {
    private data class Operation(val recordId: String, val kind: LocalSpeechKind, val job: Job)
    private val lock = Any()
    private val active = mutableSetOf<Operation>()
    private val stopping = mutableMapOf<String, Int>()

    suspend fun <T> run(recordId: String, kind: LocalSpeechKind, block: suspend () -> T): T = coroutineScope {
        val operation = Operation(recordId, kind, currentCoroutineContext().job)
        synchronized(lock) {
            check(recordId !in stopping) { "LOCAL_SPEECH_STOPPING" }
            active += operation
        }
        try { currentCoroutineContext().ensureActive(); block() }
        finally { synchronized(lock) { active -= operation } }
    }

    fun stop(recordId: String): Stop {
        val owners = synchronized(lock) {
            stopping[recordId] = stopping.getOrDefault(recordId, 0) + 1
            active.filter { it.recordId == recordId }
        }
        owners.forEach { it.job.cancel(CancellationException("Local speech model stopped")) }
        return Stop(recordId, owners.map { it.kind }.toSet())
    }

    fun isStopping(recordId: String): Boolean = synchronized(lock) { recordId in stopping }
    fun isInUse(kind: LocalSpeechKind): Boolean = synchronized(lock) { active.any { it.kind == kind } }
    fun records(kind: LocalSpeechKind): Set<String> = synchronized(lock) { active.filter { it.kind == kind }.map { it.recordId }.toSet() }

    inner class Stop internal constructor(private val recordId: String, val kinds: Set<LocalSpeechKind>) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                val remaining = stopping.getValue(recordId) - 1
                if (remaining == 0) stopping.remove(recordId) else stopping[recordId] = remaining
            }
        }
    }
}
