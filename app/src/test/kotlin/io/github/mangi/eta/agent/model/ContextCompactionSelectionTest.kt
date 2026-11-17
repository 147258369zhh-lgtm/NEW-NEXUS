package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCompactionSelectionTest {
    @Test fun firstConversationTurnCanBeSelectedWithoutAPrependedSystemMessage() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "first"))
            .put(JSONObject().put("role", "assistant").put("content", "completed"))
        assertEquals(ContextCompactionSelection.Range(0, 2), ContextCompactionSelection.resolve(messages, 0, 0..1))
    }
    @Test fun selectedRangeKeepsSystemBoundaryAndToolPair() {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system"))
            put(JSONObject().put("role", "user").put("content", "one"))
            put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1"))))
            put(JSONObject().put("role", "tool").put("tool_call_id", "call-1"))
            put(JSONObject().put("role", "assistant").put("content", "done"))
            put(JSONObject().put("role", "user").put("content", "latest"))
        }
        val range = ContextCompactionSelection.resolve(messages, systemCount = 1, requested = 1..4)
        assertEquals(1, range.start)
        assertEquals(5, range.endExclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rangeCannotSplitToolExchange() {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system"))
            put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1"))))
            put(JSONObject().put("role", "tool").put("tool_call_id", "call-1"))
        }
        ContextCompactionSelection.resolve(messages, 1, 1..1)
    }
}
