package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONObject

internal enum class MeetingStreamPhase(val wireValue: String) {
    STREAMING("streaming"), COMPLETED("completed"), INTERRUPTED("interrupted"), SETTLED("settled"),
}

/** Content lives once in meeting_messages; this metadata never duplicates the streamed body. */
internal data class MeetingStreamActivity(
    val runId: String,
    val round: Int,
    val attempt: Int,
    val kind: AgentEvent.AssistantBlockKind,
    val phase: MeetingStreamPhase,
    val content: String,
    val truncated: Boolean = false,
) {
    val messageId: String = "meeting-stream-" + MeetingToolActivity.idFor(runId, round,
        "stream:$attempt:${kind.name}", false).removePrefix("meeting-tool-")
    val eventType: String get() = "$EVENT_PREFIX$messageId"
    fun encode(isHost: Boolean): String = JSONObject()
        .put("version", 1).put("run_id", runId).put("round", round).put("attempt", attempt)
        .put("kind", kind.name).put("phase", phase.wireValue).put("truncated", truncated)
        .put("is_host", isHost).toString()

    companion object {
        const val EVENT_PREFIX = "stream_activity:"
        const val MAX_PREVIEW_CHARS = 262_144
        fun decode(payload: String, content: String): MeetingStreamActivity? = runCatching {
            val json = JSONObject(payload)
            require(json.getInt("version") == 1)
            MeetingStreamActivity(
                runId = json.getString("run_id").also { require(it.isNotBlank()) },
                round = json.getInt("round").also { require(it >= 0) },
                attempt = json.getInt("attempt").also { require(it >= 0) },
                kind = AgentEvent.AssistantBlockKind.valueOf(json.getString("kind")).also {
                    require(it != AgentEvent.AssistantBlockKind.TOOL_CALL)
                },
                phase = MeetingStreamPhase.entries.first { it.wireValue == json.getString("phase") },
                content = content, truncated = json.optBoolean("truncated"),
            )
        }.getOrNull()
    }
}

/** One bounded preview per round/kind; final authoritative output is committed separately, without truncation. */
internal class MeetingStreamReducer(private val runId: String) {
    private data class Key(val round: Int, val attempt: Int, val kind: AgentEvent.AssistantBlockKind)
    private class Block { val text = StringBuilder(); var ended = false; var truncated = false }
    private class Segment {
        val blocks = sortedMapOf<Int, Block>()
        var interrupted = false
    }
    private val attempts = mutableMapOf<Int, Int>()
    private val segments = linkedMapOf<Key, Segment>()
    private val published = mutableMapOf<Key, MeetingStreamActivity>()
    private val dirty = linkedSetOf<Key>()
    private var finished = false

    fun accept(event: AgentEvent) {
        if (finished) return
        when (event) {
            is AgentEvent.AssistantBlockStart -> if (event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL) {
                block(event.round, event.kind, event.index)
            }
            is AgentEvent.AssistantBlockDelta -> if (event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL && event.delta.isNotEmpty()) {
                val (key, segment, block) = block(event.round, event.kind, event.index)
                if (!segment.interrupted && !block.ended) {
                    val remaining = (MeetingStreamActivity.MAX_PREVIEW_CHARS - segment.blocks.values.sumOf { it.text.length }).coerceAtLeast(0)
                    block.text.append(event.delta.take(remaining))
                    if (event.delta.length > remaining) block.truncated = true
                    dirty += key
                }
            }
            is AgentEvent.AssistantBlockEnd -> if (event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL) {
                val (key, segment, block) = block(event.round, event.kind, event.index)
                if (!segment.interrupted && !block.ended) {
                    event.replacementContent?.let { replacement ->
                        block.text.clear()
                        val remaining = (MeetingStreamActivity.MAX_PREVIEW_CHARS - segment.blocks.values.sumOf { it.text.length }).coerceAtLeast(0)
                        block.text.append(replacement.take(remaining))
                        block.truncated = replacement.length > remaining
                    }
                    block.ended = true
                    dirty += key
                }
            }
            is AgentEvent.AssistantReceived -> if (event.reasoningContent.isNotBlank()) {
                val key = Key(event.round, attempts[event.round] ?: 0, AgentEvent.AssistantBlockKind.THINKING)
                if (segments[key] == null) {
                    val (_, _, block) = block(event.round, AgentEvent.AssistantBlockKind.THINKING, 0)
                    block.text.append(event.reasoningContent.take(MeetingStreamActivity.MAX_PREVIEW_CHARS))
                    block.truncated = event.reasoningContent.length > MeetingStreamActivity.MAX_PREVIEW_CHARS
                    block.ended = true
                }
            }
            is AgentEvent.ToolStarted -> completeRound(event.round)
            is AgentEvent.HostedToolStarted -> completeRound(event.round)
            is AgentEvent.RoundStarted -> segments.keys.filter { it.round < event.round }.forEach { complete(it, false) }
            is AgentEvent.ModelRetryScheduled -> {
                segments.keys.filter { it.round == event.round }.forEach { complete(it, true) }
                attempts[event.round] = (attempts[event.round] ?: 0) + 1
            }
            is AgentEvent.RunFinished -> finish(interrupted = false)
            is AgentEvent.RunFailed -> finish(interrupted = true)
            else -> Unit
        }
    }

    private fun block(round: Int, kind: AgentEvent.AssistantBlockKind, index: Int): Triple<Key, Segment, Block> {
        val key = Key(round, attempts[round] ?: 0, kind)
        val segment = segments.getOrPut(key) { Segment() }
        val block = segment.blocks.getOrPut(index) { Block().also { dirty += key } }
        return Triple(key, segment, block)
    }
    private fun completeRound(round: Int) = segments.keys.filter { it.round == round }.forEach { complete(it, false) }
    private fun complete(key: Key, interrupted: Boolean) {
        val segment = segments.getValue(key)
        if (interrupted && segment.blocks.values.any { !it.ended }) segment.interrupted = true
        segment.blocks.values.forEach { it.ended = true }
        dirty += key
    }
    fun finish(interrupted: Boolean = true) {
        finished = true
        segments.keys.forEach { complete(it, interrupted) }
    }
    fun drain(): List<MeetingStreamActivity> = dirty.toList().mapNotNull { key ->
        val segment = segments.getValue(key)
        val content = segment.blocks.values.joinToString("\n\n") { it.text.toString() }
        val state = MeetingStreamActivity(runId, key.round, key.attempt, key.kind, when {
            segment.interrupted -> MeetingStreamPhase.INTERRUPTED
            segment.blocks.values.all { it.ended } -> MeetingStreamPhase.COMPLETED
            else -> MeetingStreamPhase.STREAMING
        }, content.take(MeetingStreamActivity.MAX_PREVIEW_CHARS),
            content.length > MeetingStreamActivity.MAX_PREVIEW_CHARS || segment.blocks.values.any { it.truncated })
        if (published[key] == state) null else state.also { published[key] = it }
    }.also { dirty.clear() }
}
