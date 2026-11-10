package io.github.mangi.eta.agent.runtime

internal data class AgentRequestTiming(val sequence: Long, val round: Int?, val httpCode: Int?, val responseMs: Long?, val firstTextMs: Long?, val totalMs: Long?)

internal object AgentRequestTimings {
    fun from(trace: List<AgentMetricTraceEntity>): List<AgentRequestTiming> {
        val ordered = trace.sortedBy { it.sequence }
        return ordered.mapIndexedNotNull { index, step ->
            if (step.kind != "request") return@mapIndexedNotNull null
            val following = ordered.drop(index + 1).takeWhile { it.kind != "request" && it.kind != "model" }
            val response = following.firstOrNull { it.kind == "response" && it.round == step.round }
            val first = following.firstOrNull { it.kind == "first_text" && it.round == step.round }
            val end = following.firstOrNull { it.kind == "assistant" && it.round == step.round }
            fun delta(other: AgentMetricTraceEntity?) = other?.let { (it.elapsedMs - step.elapsedMs).takeIf { value -> value >= 0 } }
            AgentRequestTiming(step.sequence, step.round, response?.label?.toIntOrNull(), delta(response), delta(first), delta(end))
        }
    }
}
