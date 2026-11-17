package io.github.mangi.eta.agent.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionLedgerInspectorTest {
    private fun event(sequence: Long, type: String, item: String? = null, name: String = "read_file", status: String? = null): JSONObject =
        JSONObject().put("sequence", sequence).put("event_id", "event-$sequence").put("type", type)
            .put("run", JSONObject().put("run_id", "run-1"))
            .put("turn_id", "turn-1").put("item_id", item ?: JSONObject.NULL)
            .put("status", status ?: JSONObject.NULL).put("payload", JSONObject().put("name", name))

    private fun complete() = listOf(
        event(0, "run.started"), event(1, "tool.item.started", "call-1"),
        event(2, "tool.item.completed", "call-1"), event(3, "run.terminal", status = "succeeded"),
    )

    @Test fun independentlyChecksMetricsCountsAndTerminalStatus() {
        val metrics = AgentRunMetricsRecord("record", "run-1", "owner", "chat", "chat", "session", "", "", "",
            0, 1, 1, 1, "succeeded", 1, 0, AgentRunMetrics(toolCalls = 1))
        val matching = ExecutionLedgerInspector.inspect(complete().asSequence().map { it.toString() }, metrics)
        assertTrue(matching.structurallyComplete)
        assertTrue(matching.metricsCompared)
        val mismatched = ExecutionLedgerInspector.inspect(complete().asSequence().map { it.toString() },
            metrics.copy(status = "cancelled", metrics = AgentRunMetrics(toolCalls = 2)))
        assertFalse(mismatched.structurallyComplete)
        assertTrue("METRICS_TOOL_COUNT_MISMATCH" in mismatched.issues)
        assertTrue("METRICS_TERMINAL_MISMATCH" in mismatched.issues)
    }

    @Test fun duplicateAndReorderedEventsCannotPass() {
        val rows = complete().toMutableList().also { it.add(2, it[1]) }
        val inspection = ExecutionLedgerInspector.inspect(rows.asSequence().map { it.toString() })
        assertFalse(inspection.structurallyComplete)
        assertTrue("SEQUENCE_GAP_OR_REORDER" in inspection.issues)
        assertTrue("DUPLICATE_EVENT_ID" in inspection.issues)
        assertTrue("DUPLICATE_TOOL_START" in inspection.issues)
    }

    @Test fun orphanToolResultAndLateEventAreReportedInsteadOfRepairingHistory() {
        val rows = listOf(event(0, "run.started"), event(1, "tool.item.completed", "unknown"),
            event(2, "run.terminal", status = "failed"), event(3, "turn.started"))
        val inspection = ExecutionLedgerInspector.inspect(rows.asSequence().map { it.toString() })
        assertTrue("ORPHAN_TOOL_RESULT" in inspection.issues)
        assertTrue("EVENT_AFTER_TERMINAL" in inspection.issues)
        assertFalse(inspection.structurallyComplete)
    }
}
