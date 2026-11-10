package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

/** Execution-owned callbacks. They carry structured events only; a child never re-enters root usage. */
internal class AgentMetricScope(
    private val onEvent: (AgentEvent) -> Unit,
    private val onFinish: (Boolean, Boolean) -> Unit,
    private val onChild: (String, AgentModelClient.ModelConfig, Int?, String) -> AgentMetricScope?,
) {
    fun accept(event: AgentEvent) { onEvent(event) }
    fun finish(ok: Boolean, cancelled: Boolean = false) { onFinish(ok, cancelled) }
    fun child(kind: String, config: AgentModelClient.ModelConfig, parentRound: Int? = null, toolCallId: String = "") =
        onChild(kind, config, parentRound, toolCallId)
}

internal data class AgentMetricChild(
    val scopeId: String, val parentScopeId: String, val index: Int, val kind: String,
    val parentRound: Int?, val toolCallId: String, val startedAt: Long, val completedAt: Long?,
    val durationMs: Long, val status: String, val revision: Long,
    val providerId: String, val modelId: String, val name: String, val metrics: AgentRunMetrics,
    val price: AgentTokenPrice?, val priceCaptured: Boolean,
)
