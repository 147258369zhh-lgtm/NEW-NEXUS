package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * Validates a user-selected compaction range before the model is called. The range is half-open
 * and indexes the provider history (system messages are excluded by the caller).
 */
internal object ContextCompactionSelection {
    data class Range(val start: Int, val endExclusive: Int)

    fun resolve(messages: JSONArray, systemCount: Int, requested: IntRange?): Range {
        require(systemCount in 0..messages.length()) { "COMPACTION_SYSTEM_RANGE_INVALID" }
        val first = systemCount
        val last = messages.length()
        if (requested == null) return Range(first, last)
        require(requested.first >= first && requested.last < last) { "COMPACTION_RANGE_INVALID" }
        val end = requested.last + 1
        require(end - requested.first >= 2) { "COMPACTION_RANGE_TOO_SMALL" }
        val history = (0 until messages.length()).map { messages.getJSONObject(it) }
        require(AgentContextCompactor.canSplit(history, requested.first)) { "COMPACTION_RANGE_START_UNSAFE" }
        require(AgentContextCompactor.canSplit(history, end)) { "COMPACTION_RANGE_END_UNSAFE" }
        val selected = history.subList(requested.first, end)
        require(selected.none { it.optString("role") == "user" && it.optBoolean("_eta_observation") }) {
            "COMPACTION_RANGE_OBSERVATION"
        }
        return Range(requested.first, end)
    }
}
