package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Subagent 模型与思考配置。
 *
 * 全局设置作为所有 Profile 的后备；[profiles] 允许为每个内置或自定义 Profile 绑定独立的
 * provider、Model.id 与思考强度。空的 Profile 绑定会继续使用全局设置和主会话模型。
 */
@Serializable
internal data class SubagentSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val maxParallelAgents: Int = DEFAULT_MAX_PARALLEL_AGENTS,
    val candidateRoutingEnabled: Boolean = false,
    val profiles: Map<String, SubagentProfileBinding> = emptyMap(),
    /** Applies to new rooms only; each room retains its own host and model binding. */
    val defaultMeetingHostProfile: String = "meeting-host",
) {
    val resolvedMaxParallelAgents: Int
        get() = maxParallelAgents.takeIf { it in 1..MAX_PARALLEL_AGENTS } ?: DEFAULT_MAX_PARALLEL_AGENTS

    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)

    fun bindingFor(profileName: String): SubagentProfileBinding? =
        profiles[profileName.trim().lowercase()]

    companion object {
        const val DEFAULT_MAX_PARALLEL_AGENTS = 3
        const val MAX_PARALLEL_AGENTS = 8
    }
}

@Serializable
internal data class SubagentProfileBinding(
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val candidateRoutingEnabled: Boolean? = null,
    val tools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
    val contextWindow: Int? = null,
    val maxToolCalls: Int? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
