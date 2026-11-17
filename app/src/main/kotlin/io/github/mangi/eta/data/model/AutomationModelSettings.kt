package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 自动任务是否复用持久候选模型路由。
 *
 * 默认关闭：自动任务继续使用原有的子 Agent Profile/全局/主会话模型优先级。
 * 开启后，模型候选仍由 [ModelCandidateSettings] 提供，且只接受 [ModelCandidatePurpose.AUTOMATION]
 * （或 ALL）用途的候选。
 */
@Serializable
internal data class AutomationModelSettings(
    val candidateRoutingEnabled: Boolean = false,
)
