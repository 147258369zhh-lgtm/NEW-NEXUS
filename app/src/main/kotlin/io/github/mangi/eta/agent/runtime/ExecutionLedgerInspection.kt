package io.github.mangi.eta.agent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Read-only integrity report. A readable tail alone never proves a recoverable execution. */
internal data class ExecutionLedgerInspection(
    val runId: String?,
    val events: Int,
    val lastSequence: Long,
    val terminalStatus: String?,
    val toolCalls: Int,
    val unresolvedTools: Int,
    val issues: Set<String>,
    val metricsCompared: Boolean,
    val requestCount: Int = 0,
    val terminalTranscriptMessages: Int? = null,
    val toolItems: Map<String, String?> = emptyMap(),
    val toolStates: Map<String, String?> = emptyMap(),
) {
    val structurallyComplete: Boolean get() = terminalStatus != null && issues.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("runId", runId ?: JSONObject.NULL)
        .put("events", events)
        .put("lastSequence", lastSequence)
        .put("terminalStatus", terminalStatus ?: JSONObject.NULL)
        .put("toolCalls", toolCalls)
        .put("unresolvedTools", unresolvedTools)
        .put("modelRequests", requestCount)
        .put("terminalTranscriptMessages", terminalTranscriptMessages ?: JSONObject.NULL)
        .put("issues", JSONArray(issues.sorted()))
        .put("structurallyComplete", structurallyComplete)
        .put("metricsCompared", metricsCompared)
}

internal object ExecutionLedgerInspector {
    fun inspect(file: File, metrics: AgentRunMetricsRecord? = null): ExecutionLedgerInspection =
        try { file.useLines { inspect(it, metrics) } }
        catch (_: Exception) { ExecutionLedgerInspection(null, 0, -1, null, 0, 0, setOf("LEDGER_UNREADABLE"), false) }

    fun inspect(lines: Sequence<String>, metrics: AgentRunMetricsRecord? = null): ExecutionLedgerInspection {
        var runId: String? = null
        var lastSequence = -1L
        var count = 0
        var starts = 0
        var terminals = 0
        var requests = 0
        var status: String? = null
        var terminalTranscriptMessages: Int? = null
        val ids = mutableSetOf<String>()
        val tools = linkedMapOf<String, String?>()
        val settled = mutableSetOf<String>()
        val toolStates = linkedMapOf<String, String?>()
        val issues = linkedSetOf<String>()
        lines.forEach { raw ->
            val event = runCatching { JSONObject(raw) }.getOrNull()
            if (event == null) { issues += "MALFORMED_EVENT"; return@forEach }
            count++
            val currentRunId = event.optJSONObject("run")?.optString("run_id").orEmpty()
            if (currentRunId.isBlank()) issues += "MISSING_RUN_ID"
            if (runId == null) runId = currentRunId
            else if (runId != currentRunId) issues += "MIXED_RUN_ID"
            if (terminals > 0) issues += "EVENT_AFTER_TERMINAL"
            val sequence = event.optLong("sequence", -1)
            if (sequence != lastSequence + 1) issues += "SEQUENCE_GAP_OR_REORDER"
            lastSequence = sequence
            val id = event.optString("event_id")
            if (id.isBlank()) issues += "MISSING_EVENT_ID"
            else if (!ids.add(id)) issues += "DUPLICATE_EVENT_ID"
            val payload = event.optJSONObject("payload") ?: JSONObject()
            if (payload.optLong("dropped_total", 0) > 0 || payload.optLong("dropped_before", 0) > 0) issues += "EVENTS_DROPPED"
            when (event.optString("type")) {
                "run.started" -> starts++
                "model.request.started" -> requests++
                "tool.item.started", "hosted_tool.item.started" -> {
                    val key = "${event.optString("turn_id")}\u0000${event.optString("item_id") }"
                    if (tools.containsKey(key)) issues += "DUPLICATE_TOOL_START"
                    tools[key] = payload.optString("name")
                }
                "tool.item.completed", "hosted_tool.item.completed" -> {
                    val key = "${event.optString("turn_id")}\u0000${event.optString("item_id") }"
                    if (key !in tools) issues += "ORPHAN_TOOL_RESULT"
                    else if (tools[key] != payload.optString("name")) issues += "TOOL_NAME_MISMATCH"
                    if (!settled.add(key)) issues += "DUPLICATE_TOOL_RESULT"
                    toolStates[key] = when {
                        payload.optBoolean("success", false) -> "succeeded"
                        payload.has("success") && !payload.isNull("success") -> "failed"
                        else -> "unknown"
                    }
                }
                "run.terminal" -> {
                    terminals++
                    status = event.optString("status").takeIf { it in setOf("succeeded", "failed", "cancelled", "interrupted") }
                    terminalTranscriptMessages = payload.optInt("transcript_messages", -1).takeIf { it >= 0 }
                    if (status == null) issues += "INVALID_TERMINAL_STATUS"
                }
            }
        }
        if (starts != 1) issues += "INVALID_RUN_START_COUNT"
        if (terminals != 1) issues += "INCOMPLETE_OR_DUPLICATE_TERMINAL"
        val unresolved = tools.keys.count { it !in settled }
        if (unresolved > 0) issues += "UNRESOLVED_TOOL_EFFECT"
        if (metrics != null) {
            if (metrics.runId != runId) issues += "METRICS_RUN_MISMATCH"
            if (metrics.metrics.toolCalls != tools.size) issues += "METRICS_TOOL_COUNT_MISMATCH"
            if (status != null && metrics.status != status) issues += "METRICS_TERMINAL_MISMATCH"
        }
        return ExecutionLedgerInspection(
            runId = runId,
            events = count,
            lastSequence = lastSequence,
            terminalStatus = status,
            toolCalls = tools.size,
            unresolvedTools = unresolved,
            issues = issues,
            metricsCompared = metrics != null,
            requestCount = requests,
            terminalTranscriptMessages = terminalTranscriptMessages,
            toolItems = tools,
            toolStates = toolStates,
        )
    }
}
