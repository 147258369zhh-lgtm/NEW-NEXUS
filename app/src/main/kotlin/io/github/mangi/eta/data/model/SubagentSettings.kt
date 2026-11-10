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
    val profiles: Map<String, SubagentProfileBinding> = emptyMap(),
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)

    fun bindingFor(profileName: String): SubagentProfileBinding? =
        profiles[profileName.trim().lowercase()]
}

@Serializable
internal data class SubagentProfileBinding(
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val tools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
    val contextWindow: Int? = null,
    val maxToolCalls: Int? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
