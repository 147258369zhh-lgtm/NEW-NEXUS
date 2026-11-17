package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主聊天、子 Agent、会议、自动任务四个入口必须读同一份 providerId+modelId 能力快照。 */
class ModelEntryCapabilityConsistencyTest {
    @Test
    fun sameProviderAndModelExposesOneCapabilitySnapshotAcrossEntries() {
        val provider = provider("shared-provider", "shared-model", vision = true, tools = true)
        val chat = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models.single())

        val entries = mapOf(
            ModelCandidatePurpose.SUBAGENT to resolve(provider, ModelCandidatePurpose.SUBAGENT),
            ModelCandidatePurpose.MEETING to resolve(provider, ModelCandidatePurpose.MEETING),
            ModelCandidatePurpose.AUTOMATION to resolve(provider, ModelCandidatePurpose.AUTOMATION),
        )
        for ((purpose, config) in entries) {
            assertEquals(purpose.name, chat.providerId, config.providerId)
            assertEquals(purpose.name, chat.model, config.model)
            assertTrue(purpose.name, config.supportsTools)
            assertTrue(purpose.name, config.supportsImageInput)
            assertEquals(purpose.name, chat.supportsTools, config.supportsTools)
            assertEquals(purpose.name, chat.supportsImageInput, config.supportsImageInput)
            assertTrue(purpose.name, config.reasoningCapabilities != null)
            assertEquals(purpose.name, chat.reasoningCapabilities, config.reasoningCapabilities)
            assertEquals(purpose.name, chat.knownContextWindow, config.knownContextWindow)
        }
    }

    @Test
    fun meetingKeepsImageOnlyVisionRequirementWhileSubagentDoesNotNeedIt() {
        val provider = provider("text-provider", "text-model", vision = false, tools = true)
        val subagent = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(),
            providers = listOf(provider),
            profileName = "general",
            explicitProviderId = provider.id,
            explicitModelId = "text-model",
            candidatePurpose = ModelCandidatePurpose.SUBAGENT,
            requiresTools = true,
            requiresVision = false,
        )
        assertEquals(SubagentConfigSource.EXPLICIT, subagent.modelSource)
        assertEquals("text-model", subagent.config.model)

        val denied = runCatching {
            RuntimeConfigRepository.resolveSubagentRuntimeConfig(
                primaryConfig = primary(),
                settings = SubagentSettings(),
                providers = listOf(provider),
                profileName = "general",
                explicitProviderId = provider.id,
                explicitModelId = "text-model",
                candidatePurpose = ModelCandidatePurpose.MEETING,
                requiresTools = true,
                requiresVision = true,
            )
        }.exceptionOrNull()
        assertTrue(denied is IllegalStateException)
        assertTrue(denied?.message.orEmpty().contains("会议参与者"))
        assertTrue(denied?.message.orEmpty().contains("视觉"))
    }

    @Test
    fun manualBindingCannotBypassTheCredentialRuleUsedByCandidatesAndFallbacks() {
        val unconfigured = provider("draft-provider", "draft-model", apiKey = "")
        val global = provider("global-provider", "global-model")

        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(modelId = "global-model"),
            providers = listOf(unconfigured, global),
            profileName = "general",
            explicitProviderId = "draft-provider",
            explicitModelId = "draft-model",
        )

        assertEquals(SubagentConfigSource.GLOBAL, resolved.modelSource)
        assertEquals("global-model", resolved.config.model)
        assertTrue(resolved.fallbackReason.orEmpty().contains("显式指定"))
    }

    private fun resolve(
        provider: OpenAiCompatibleProviderSetting,
        purpose: ModelCandidatePurpose,
    ): AgentModelClient.ModelConfig = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
        primaryConfig = primary(),
        settings = SubagentSettings(),
        providers = listOf(provider),
        profileName = "general",
        explicitProviderId = provider.id,
        explicitModelId = "shared-model",
        candidatePurpose = purpose,
        requiresTools = true,
        requiresVision = true,
        candidateSettings = ModelCandidateSettings(enabled = false),
        fallbackSettings = ModelFallbackSettings(),
    ).config

    private fun provider(
        id: String,
        remoteModelId: String,
        vision: Boolean = false,
        tools: Boolean = false,
        apiKey: String = "fixture",
    ) = OpenAiCompatibleProviderSetting(
        id = id,
        name = id,
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        models = listOf(
            Model(
                id = remoteModelId,
                modelId = remoteModelId,
                displayName = remoteModelId,
                contextWindowOverride = 128_000,
                attachment = vision,
                toolCall = tools,
                reasoning = true,
                reasoningOverride = true,
                reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM),
                    canDisable = true,
                ),
            ),
        ),
    )

    private fun primary() = AgentModelClient.ModelConfig(
        providerId = "primary-provider",
        providerName = "Primary",
        baseUrl = "https://primary.invalid/v1",
        apiKey = "fixture",
        model = "primary-model",
        modelDisplayName = "Primary Model",
        systemPrompt = "primary",
    )
}
