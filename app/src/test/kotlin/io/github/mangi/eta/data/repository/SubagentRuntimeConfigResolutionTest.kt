package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentProfileBinding
import io.github.mangi.eta.data.model.SubagentSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRuntimeConfigResolutionTest {
    @Test
    fun explicitModelOverridesProfileGlobalAndPrimary() {
        val providers = listOf(
            provider("profile-provider", "profile-model"),
            provider("global-provider", "global-model"),
            provider("explicit-provider", "explicit-model"),
        )
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                modelId = "global-model",
                profiles = mapOf("explore" to SubagentProfileBinding("profile-provider", "profile-model")),
            ),
            providers = providers,
            profileName = "explore",
            explicitProviderId = "explicit-provider",
            explicitModelId = "explicit-model",
        )

        assertEquals(SubagentConfigSource.EXPLICIT, resolved.modelSource)
        assertEquals("explicit-provider", resolved.config.providerId)
        assertEquals("explicit-model", resolved.config.model)
        assertNull(resolved.fallbackReason)
    }

    @Test
    fun profileBindingIsUsedAfterExplicitTierIsAbsent() {
        val providers = listOf(
            provider("profile-provider", "profile-model"),
            provider("global-provider", "global-model"),
        )
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                modelId = "global-model",
                profiles = mapOf("coder" to SubagentProfileBinding("profile-provider", "profile-model")),
            ),
            providers = providers,
            profileName = "CODER",
        )

        assertEquals(SubagentConfigSource.PROFILE, resolved.modelSource)
        assertEquals("profile-model", resolved.config.model)
    }

    @Test
    fun disabledProfileModelFallsBackToGlobalAndReportsReason() {
        val disabledProvider = provider("profile-provider", "profile-model").copy(isEnabled = false)
        val globalProvider = provider("global-provider", "global-model")
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                modelId = "global-model",
                profiles = mapOf("plan" to SubagentProfileBinding("profile-provider", "profile-model")),
            ),
            providers = listOf(disabledProvider, globalProvider),
            profileName = "plan",
        )

        assertEquals(SubagentConfigSource.GLOBAL, resolved.modelSource)
        assertEquals("global-model", resolved.config.model)
        assertNotNull(resolved.fallbackReason)
        assertTrue(resolved.fallbackReason!!.contains("Profile 绑定"))
    }

    @Test
    fun allUnavailableBindingsFallBackToPrimaryWithCompleteReason() {
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                modelId = "missing-global",
                profiles = mapOf("explore" to SubagentProfileBinding("missing-provider", "missing-profile")),
            ),
            providers = emptyList(),
            profileName = "explore",
            explicitProviderId = "missing-explicit-provider",
            explicitModelId = "missing-explicit-model",
        )

        assertEquals(SubagentConfigSource.PRIMARY, resolved.modelSource)
        assertEquals("primary-model", resolved.config.model)
        assertTrue(resolved.fallbackReason!!.contains("显式指定"))
        assertTrue(resolved.fallbackReason!!.contains("Profile 绑定"))
        assertTrue(resolved.fallbackReason!!.contains("全局默认"))
    }

    @Test
    fun profileReasoningIsNormalizedToSelectedModelCapabilities() {
        val provider = provider(
            id = "profile-provider",
            remoteModelId = "profile-model",
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW),
                canDisable = true,
            ),
        )
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                profiles = mapOf(
                    "explore" to SubagentProfileBinding(
                        providerId = "profile-provider",
                        modelId = "profile-model",
                        reasoningEffort = ReasoningEffort.HIGH.wireValue,
                    ),
                ),
            ),
            providers = listOf(provider),
            profileName = "explore",
        )

        assertEquals(SubagentConfigSource.PROFILE, resolved.modelSource)
        assertEquals(SubagentConfigSource.PROFILE, resolved.reasoningSource)
        assertEquals(ReasoningEffort.LOW, resolved.config.effectiveReasoningEffort)
        assertTrue(resolved.fallbackReason!!.contains("已调整"))
    }

    @Test
    fun profileToolAndBudgetBindingsAreReturnedAndContextIsCappedByModel() {
        val provider = provider("profile-provider", "profile-model").let { setting ->
            setting.copy(
                models = setting.models.map { it.copy(contextWindow = 128_000) },
            )
        }
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                profiles = mapOf(
                    "coder" to SubagentProfileBinding(
                        providerId = "profile-provider",
                        modelId = "profile-model",
                        tools = listOf("read_file", "grep_files", "read_file"),
                        disallowedTools = listOf("write_file"),
                        contextWindow = 64_000,
                        maxToolCalls = 12,
                    ),
                ),
            ),
            providers = listOf(provider),
            profileName = "coder",
        )

        assertEquals(64_000, resolved.config.contextWindow)
        assertEquals(listOf("read_file", "grep_files"), resolved.toolsOverride)
        assertEquals(setOf("write_file"), resolved.disallowedTools)
        assertEquals(12, resolved.maxToolCalls)
    }

    @Test
    fun profileContextCannotRaiseProviderWindow() {
        val provider = provider("profile-provider", "profile-model").let { setting ->
            setting.copy(
                models = setting.models.map { it.copy(contextWindow = 32_000) },
            )
        }
        val resolved = RuntimeConfigRepository.resolveSubagentRuntimeConfig(
            primaryConfig = primary(),
            settings = SubagentSettings(
                profiles = mapOf(
                    "explore" to SubagentProfileBinding(
                        providerId = "profile-provider",
                        modelId = "profile-model",
                        contextWindow = 128_000,
                    ),
                ),
            ),
            providers = listOf(provider),
            profileName = "explore",
        )

        assertEquals(32_000, resolved.config.contextWindow)
    }

    @Test
    fun legacySettingsJsonRemainsReadableAndNewBindingsRoundTrip() {
        val codec = Json { ignoreUnknownKeys = true }
        val legacy = codec.decodeFromString<SubagentSettings>(
            """{"modelId":"legacy-model","reasoningEffort":"high"}""",
        )
        assertEquals("legacy-model", legacy.modelId)
        assertEquals(ReasoningEffort.HIGH, legacy.resolvedReasoningEffort)
        assertTrue(legacy.profiles.isEmpty())

        val withBinding = legacy.copy(
            profiles = mapOf(
                "coder" to SubagentProfileBinding("provider", "coder-model", "medium"),
            ),
        )
        assertEquals(withBinding, codec.decodeFromString<SubagentSettings>(codec.encodeToString(withBinding)))
    }

    private fun provider(
        id: String,
        remoteModelId: String,
        reasoningCapabilities: ModelReasoningCapabilities? = null,
    ) = OpenAiCompatibleProviderSetting(
        id = id,
        name = id,
        baseUrl = "https://example.invalid/v1",
        apiKey = "fixture",
        models = listOf(
            Model(
                id = remoteModelId,
                modelId = remoteModelId,
                displayName = remoteModelId,
                reasoning = reasoningCapabilities != null,
                reasoningOverride = reasoningCapabilities != null,
                reasoningCapabilitiesOverride = reasoningCapabilities,
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
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MEDIUM),
            canDisable = true,
        ),
        reasoningEffort = ReasoningEffort.MEDIUM,
        thinkingEnabled = true,
    )
}
