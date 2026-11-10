package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.security.MessageDigest
import org.json.JSONObject

internal enum class MeetingToolPhase(val wireValue: String) {
    RUNNING("running"), SUCCESS("success"), FAILED("failed"), UNKNOWN("unknown"), INTERRUPTED("interrupted");
    val terminal: Boolean get() = this != RUNNING
}

/** A small, readable trace, never a raw tool result or an image payload. */
internal data class MeetingToolActivity(
    val runId: String,
    val round: Int,
    val callId: String,
    val hosted: Boolean,
    val name: String,
    val phase: MeetingToolPhase,
    val arguments: String = "",
    val command: String? = null,
    val result: String? = null,
    val imageCount: Int = 0,
) {
    val messageId: String = idFor(runId, round, callId, hosted)
    val eventType: String get() = "$EVENT_PREFIX$messageId"

    fun durable(): MeetingToolActivity {
        val formatter = AgentTraceFormatter()
        val sensitive = AgentSensitiveToolPolicy.isSensitive(name)
        return copy(
            name = formatter.durableDisplayPreview(name, 160),
            arguments = if (sensitive) "" else formatter.durableDisplayPreview(arguments, 2_000),
            // Subagent commands are nested progress JSON; that belongs to the live Runtime, not this archive.
            command = command?.takeUnless { sensitive || name == "subagent" }
                ?.let { formatter.durableDisplayPreview(it, 4_000) },
            result = result?.takeUnless { sensitive }?.let { formatter.durableDisplayPreview(it, 4_000) },
            imageCount = imageCount.coerceAtLeast(0),
        )
    }

    fun encode(isHost: Boolean): String = JSONObject()
        .put("version", 1).put("run_id", runId).put("round", round).put("call_id", callId)
        .put("hosted", hosted).put("name", name).put("phase", phase.wireValue)
        .put("arguments", arguments).put("command", command ?: JSONObject.NULL)
        .put("result", result ?: JSONObject.NULL).put("image_count", imageCount).put("is_host", isHost)
        .toString()

    companion object {
        const val EVENT_PREFIX = "tool_activity:"

        fun idFor(runId: String, round: Int, callId: String, hosted: Boolean): String {
            // Length-delimited input prevents collisions in names and IDs supplied by providers.
            val key = "${runId.length}:$runId:$round:$hosted:${callId.length}:$callId"
            val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            val digits = "0123456789abcdef"
            val hash = CharArray(bytes.size * 2) { index ->
                val byte = bytes[index / 2].toInt() and 255
                digits[if (index % 2 == 0) byte ushr 4 else byte and 15]
            }.concatToString()
            return "meeting-tool-$hash"
        }

        fun decode(payload: String): MeetingToolActivity? = runCatching {
            val json = JSONObject(payload)
            require(json.getInt("version") == 1)
            val phase = MeetingToolPhase.entries.first { it.wireValue == json.getString("phase") }
            MeetingToolActivity(
                runId = json.getString("run_id").also { require(it.isNotBlank()) },
                round = json.getInt("round").also { require(it >= 0) },
                callId = json.getString("call_id").also { require(it.isNotBlank()) },
                hosted = json.getBoolean("hosted"), name = json.getString("name"), phase = phase,
                arguments = json.optString("arguments"),
                command = if (json.isNull("command")) null else json.getString("command"),
                result = if (json.isNull("result")) null else json.getString("result"),
                imageCount = json.optInt("image_count").coerceAtLeast(0),
            )
        }.getOrNull()

        fun fromEvent(runId: String, event: AgentEvent, previous: MeetingToolActivity?): MeetingToolActivity? = when (event) {
            is AgentEvent.ToolStarted -> MeetingToolActivity(runId, event.round, event.toolCallId, false,
                event.name, MeetingToolPhase.RUNNING, event.argsPreview, event.command)
            is AgentEvent.ToolFinished -> MeetingToolActivity(runId, event.round, event.toolCallId, false,
                event.name, when (event.success) {
                    true -> MeetingToolPhase.SUCCESS
                    false -> MeetingToolPhase.FAILED
                    null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) MeetingToolPhase.FAILED else MeetingToolPhase.UNKNOWN
                }, arguments = previous?.arguments.orEmpty(), command = event.command ?: previous?.command,
                result = event.resultSummary, imageCount = event.imageCount)
            is AgentEvent.HostedToolStarted -> MeetingToolActivity(runId, event.round, event.toolCallId, true,
                event.name, MeetingToolPhase.RUNNING)
            is AgentEvent.HostedToolFinished -> MeetingToolActivity(runId, event.round, event.toolCallId, true,
                event.name, if (event.success) MeetingToolPhase.SUCCESS else MeetingToolPhase.FAILED)
            else -> null
        }
    }
}
