package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionPort
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ResolvedSubagentConfig
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/** Meeting orchestration shapes tasks; this port resolves models and uses the shared Runtime. */
internal interface MeetingParticipantRuntime {
    suspend fun configuration(
        participant: MeetingParticipantEntity,
        candidateRoutingEnabled: Boolean = false,
        requiresTools: Boolean = true,
        requiresVision: Boolean = false,
        /** 调用方已知的本轮请求输入估算；拿不到时保持 0。 */
        estimatedInputTokens: Int = 0,
    ): ResolvedSubagentConfig
    fun execute(context: Context, request: AgentRuntimeWire.RunRequest, onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult
    fun acknowledge(context: Context, runId: String): Boolean = false
}

internal object DefaultMeetingParticipantRuntime : MeetingParticipantRuntime {
    override suspend fun configuration(
        participant: MeetingParticipantEntity,
        candidateRoutingEnabled: Boolean,
        requiresTools: Boolean,
        requiresVision: Boolean,
        estimatedInputTokens: Int,
    ): ResolvedSubagentConfig {
        if (!participant.modelId.isNullOrBlank()) {
            val provider = ProviderRepository.allProviders().firstOrNull { it.id == participant.providerId }
            require(provider?.hasUsableConnection() == true && provider.models.any { it.id == participant.modelId && it.isEnabled }) {
                "会议成员绑定的模型已停用、移除或缺少连接配置；没有自动换成另一模型，请修改成员绑定。"
            }
        }
        val primary = RuntimeConfigRepository.currentRuntimeConfig() ?: error("尚未配置可用的模型提供商")
        return RuntimeConfigRepository.subagentRuntimeConfig(
            primaryConfig = primary,
            profileName = participant.profileName,
            explicitProviderId = participant.providerId,
            explicitModelId = participant.modelId,
            allowCandidateRouting = candidateRoutingEnabled,
            candidatePurpose = ModelCandidatePurpose.MEETING,
            requiresTools = requiresTools,
            requiresVision = requiresVision,
            estimatedInputTokens = estimatedInputTokens,
        )
    }

    override fun execute(context: Context, request: AgentRuntimeWire.RunRequest, onEvent: (AgentEvent) -> Unit) =
        AgentExecutionPort.run(context.applicationContext, request, onEvent)

    override fun acknowledge(context: Context, runId: String): Boolean =
        AgentRuntimeClient(context.applicationContext, AndroidAgentLogger).ackResult(runId)
}

internal enum class MeetingExecutionPhase { PLANNING, WORK, SUMMARY, FACILITATION, INTERVIEW }
