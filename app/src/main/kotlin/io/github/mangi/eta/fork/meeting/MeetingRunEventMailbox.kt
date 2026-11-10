package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.runtime.AgentEvent
import kotlinx.coroutines.channels.Channel

/** Coalesce adjacent text chunks while a slow database writer is suspended; never merge across a tool boundary. */
internal class MeetingRunEventMailbox {
    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private class Pending(source: AgentEvent) {
        val event = (source as? AgentEvent.AssistantBlockDelta)?.copy(delta = "") ?: source
        val text = (source as? AgentEvent.AssistantBlockDelta)?.let {
            StringBuilder(it.delta.take(MeetingStreamActivity.MAX_PREVIEW_CHARS + 1))
        }
        fun matches(delta: AgentEvent.AssistantBlockDelta): Boolean =
            (event as? AgentEvent.AssistantBlockDelta)?.let {
                it.round == delta.round && it.index == delta.index && it.kind == delta.kind
            } == true
        fun materialize(): AgentEvent = (event as? AgentEvent.AssistantBlockDelta)?.let {
            it.copy(delta = text.toString(), deltaChars = text!!.length)
        } ?: event
    }
    class Delivery(private val event: AgentEvent?) { fun getOrNull(): AgentEvent? = event }

    fun trySend(event: AgentEvent) = synchronized(lock) {
        if (closed) return@synchronized
        val last = queue.lastOrNull()
        if (event is AgentEvent.AssistantBlockDelta && last?.matches(event) == true) {
            val remaining = MeetingStreamActivity.MAX_PREVIEW_CHARS + 1 - last.text!!.length
            if (remaining > 0) last.text.append(event.delta.take(remaining))
        } else queue.addLast(Pending(event))
        signal.trySend(Unit)
        Unit
    }

    suspend fun receiveCatching(): Delivery {
        while (true) {
            synchronized(lock) {
                if (queue.isNotEmpty()) return Delivery(queue.removeFirst().materialize())
                if (closed) return Delivery(null)
            }
            signal.receiveCatching()
        }
    }

    fun close() = synchronized(lock) { closed = true; signal.close(); Unit }
}
