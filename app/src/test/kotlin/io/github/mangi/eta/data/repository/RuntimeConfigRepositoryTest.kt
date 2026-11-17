package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelRoleSettings
import io.github.mangi.eta.data.model.ModelRoleBinding
import io.github.mangi.eta.data.model.ModelRolePurpose
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeConfigRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun setUp() {
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking { ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "roundtrip", name = "测试", baseUrl = "https://example.invalid/v1", apiKey = "fixture",
            models = listOf("aux", "compact", "memory", "memory-updated", "learning", "transcript").map {
                Model(id = "m-$it", modelId = it, displayName = it)
            },
        )) }
    }

    @Test
    fun modelRoleSettingsRoundTripPreservesEveryPurposeAndPartialEdits() = runBlocking {
        val allRoles = ModelRoleSettings(
            auxiliaryProviderId = "roundtrip", auxiliaryModelId = "m-aux",
            compactionProviderId = "roundtrip", compactionModelId = "m-compact",
            memoryProviderId = "roundtrip", memoryModelId = "m-memory",
            learningProviderId = "roundtrip", learningModelId = "m-learning",
            transcriptProviderId = "roundtrip", transcriptModelId = "m-transcript",
        )

        RuntimeConfigRepository.setModelRoleSettings(allRoles)
        assertEquals(allRoles, RuntimeConfigRepository.modelRoleSettings())

        val partialEdit = RuntimeConfigRepository.modelRoleSettings().copy(memoryModelId = "m-memory-updated")
        RuntimeConfigRepository.setModelRoleSettings(partialEdit)
        assertEquals(partialEdit, RuntimeConfigRepository.modelRoleSettings())
        assertEquals("roundtrip", RuntimeConfigRepository.modelRoleSettings().memoryProviderId)
        assertEquals("m-learning", RuntimeConfigRepository.modelRoleSettings().learningModelId)
        assertEquals("m-transcript", RuntimeConfigRepository.modelRoleSettings().transcriptModelId)
    }

    @Test
    fun selectingOneProcessingModelKeepsUnassignedJsonNullRolesEmpty() = runBlocking {
        val onlyAuxiliary = ModelRoleSettings(auxiliaryProviderId = "roundtrip", auxiliaryModelId = "m-aux")
        RuntimeConfigRepository.setModelRoleSettings(onlyAuxiliary)
        assertEquals(onlyAuxiliary, RuntimeConfigRepository.modelRoleSettings())
        RuntimeConfigRepository.setModelRoleSettings(ModelRoleSettings())
        assertEquals(ModelRoleSettings(), RuntimeConfigRepository.modelRoleSettings())
    }

    @Test
    fun compactionModelFallsBackFromExplicitToAuxiliary() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "roles", name = "角色测试", baseUrl = "https://example.invalid/v1", apiKey = "fixture-key",
            models = listOf(
                Model(id = "aux", modelId = "aux-api", displayName = "辅助"),
                Model(id = "compaction", modelId = "compact-api", displayName = "压缩"),
            ),
        )
        val auxiliary = RuntimeConfigRepository.resolveCompactionRole(
            ModelRoleSettings(auxiliaryProviderId = provider.id, auxiliaryModelId = "aux"),
            listOf(provider),
        )
        assertEquals("aux", auxiliary?.model?.id)
        assertEquals(ModelRoleSource.AUXILIARY, auxiliary?.source)

        val explicit = RuntimeConfigRepository.resolveCompactionRole(
            ModelRoleSettings(
                auxiliaryProviderId = provider.id,
                auxiliaryModelId = "aux",
                compactionProviderId = provider.id,
                compactionModelId = "compaction",
            ),
            listOf(provider),
        )
        assertEquals("compaction", explicit?.model?.id)
        assertEquals(ModelRoleSource.COMPACTION, explicit?.source)

        val scoped = RuntimeConfigRepository.resolveCompactionRole(
            ModelRoleSettings(
                compactionProviderId = provider.id,
                compactionModelId = "compaction",
            ),
            listOf(provider),
            ModelRoleBinding("aux", provider.id),
        )
        assertEquals("aux", scoped?.model?.id)
    }

    @Test
    fun maintenancePurposesUseDedicatedModelThenAuxiliaryThenPrimary() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "role-chain", name = "测试", baseUrl = "https://example.invalid/v1",
            apiKey = "fixture-key",
            models = listOf(
                Model(id = "primary", modelId = "primary-api", displayName = "主模型", toolCall = true),
                Model(id = "aux", modelId = "aux-api", displayName = "辅助"),
                Model(id = "memory", modelId = "memory-api", displayName = "记忆"),
                Model(id = "learning", modelId = "learning-api", displayName = "自学习"),
                Model(id = "transcript", modelId = "transcript-api", displayName = "语音整理"),
            ),
        )
        val primary = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models[0])
        val configured = ModelRoleSettings(
            auxiliaryProviderId = provider.id,
            auxiliaryModelId = "aux",
            memoryProviderId = provider.id,
            memoryModelId = "memory",
            learningProviderId = provider.id,
            learningModelId = "learning",
            transcriptProviderId = provider.id,
            transcriptModelId = "transcript",
        )

        ModelRolePurpose.entries.forEach { purpose ->
            val expected = when (purpose) {
                ModelRolePurpose.MEMORY -> "memory-api"
                ModelRolePurpose.LEARNING -> "learning-api"
                ModelRolePurpose.TRANSCRIPT -> "transcript-api"
            }
            assertEquals(
                expected,
                RuntimeConfigRepository.resolveModelRoleRuntimeConfig(
                    purpose, configured, listOf(provider), primary,
                ).model,
            )
        }
        assertEquals(
            "aux-api",
            RuntimeConfigRepository.resolveModelRoleRuntimeConfig(
                ModelRolePurpose.MEMORY,
                configured.copy(memoryModelId = null, memoryProviderId = null),
                listOf(provider),
                primary,
            ).model,
        )
        assertEquals(
            "primary-api",
            RuntimeConfigRepository.resolveModelRoleRuntimeConfig(
                ModelRolePurpose.MEMORY,
                ModelRoleSettings(),
                listOf(provider),
                primary,
            ).model,
        )
    }

    @Test
    fun mapsExplicitModelToolCapabilityIntoRuntimeConfig() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "tool-provider", name = "测试", baseUrl = "https://example.invalid/v1",
        )
        fun config(toolCall: Boolean?) = RuntimeConfigRepository.buildRuntimeConfig(
            provider,
            Model(id = "model-$toolCall", modelId = "fixture", displayName = "测试", toolCall = toolCall),
        )

        assertFalse(config(false).supportsTools)
        assertTrue(config(true).supportsTools)
        assertFalse(config(null).supportsTools)
    }

    @Test
    fun mapsExplicitModelVisionCapabilityIntoRuntimeConfig() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "vision-provider", name = "测试", baseUrl = "https://example.invalid/v1",
        )
        fun config(vision: Boolean?) = RuntimeConfigRepository.buildRuntimeConfig(
            provider,
            Model(
                id = "model-$vision", modelId = "fixture", displayName = "测试",
                attachment = vision,
            ),
        )

        assertFalse(config(false).supportsImageInput)
        assertTrue(config(true).supportsImageInput)
        assertFalse(config(null).supportsImageInput)
    }

    @Test
    fun emptyProviderPromptStaysEmptyAcrossRuntimeSerialization() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "prompt-provider", name = "测试", baseUrl = "https://example.invalid/v1",
        )
        val model = Model(id = "model", modelId = "fixture", displayName = "测试")
        for (prompt in listOf(null, "", "  ", "你是小满")) {
            val config = RuntimeConfigRepository.buildRuntimeConfig(provider.copy(systemPrompt = prompt), model)
            val restored = Json.decodeFromString<AgentModelClient.ModelConfig>(
                RuntimeConfigRepository.runtimeConfigJson(config),
            )
            assertEquals(prompt.orEmpty().trim(), restored.systemPrompt)
        }
    }

    @Test
    fun oauthConfigUsesBoundEndpointAndNeverSerializesApiKey() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-provider",
            name = "订阅",
            baseUrl = "https://untrusted.invalid/v1",
            apiKey = "fixture-must-not-be-projected",
            authMode = ProviderAuthModes.XAI_GROK,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        )
        val config = RuntimeConfigRepository.buildRuntimeConfig(
            provider,
            Model(id = "local-model", modelId = "fixture-model", displayName = "测试"),
        )

        assertEquals("https://cli-chat-proxy.grok.com/v1", config.baseUrl)
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, config.openAiEndpointMode)
        assertEquals(ProviderSourceTypes.XAI, config.providerSourceType)
        assertEquals(ProviderAuthModes.XAI_GROK, config.authMode)
        assertEquals("", config.apiKey)
        val exported = RuntimeConfigRepository.runtimeConfigJson(config.copy(apiKey = "fixture-must-not-be-projected"))
        assertFalse(exported.contains("fixture-must-not-be-projected"))
        assertEquals("", Json.decodeFromString<AgentModelClient.ModelConfig>(exported).apiKey)
    }

    @Test
    fun previousRuntimeJsonDefaultsToApiKeyAuthentication() {
        val restored = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """{"baseUrl":"https://example.invalid/v1","apiKey":"fixture-key","model":"fixture","systemPrompt":""}""",
        )
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
        assertEquals("fixture-key", restored.apiKey)
    }

    @Test
    fun buildsStructuredRuntimeConfigFromProviderAndModel() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Provider",
            baseUrl = "https://api.example.com/v1",
            apiKey = "key",
            sourceType = ProviderSourceTypes.OPENAI,
            customHeaders = listOf(CustomHeader("x-provider", "1"))
        )
        val model = Model(
            id = "m1",
            modelId = "gpt-5.5",
            displayName = "GPT-5.5",
            contextWindow = 1_000_000,
            contextWindowOverride = 256_000,
            reasoning = true,
            reasoningOverride = true,
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                canDisable = true,
            ),
            customHeaders = listOf(CustomHeader("x-model", "2"))
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config.copy(autoCompactionEnabled = false))
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderTypes.OPENAI_COMPATIBLE, root.getValue("providerType").jsonPrimitive.content)
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(256_000, config.contextWindow)
        assertEquals(listOf("x-provider", "x-model"), config.customHeaders.map { it.name })
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(true, config.thinkingEnabled)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
            ),
            config.reasoningCapabilities?.selectableEfforts,
        )
        assertEquals("false", root.getValue("autoCompactionEnabled").jsonPrimitive.content)
        assertEquals(config.copy(autoCompactionEnabled = false), Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
        assertEquals(config.autoCompactionEnabled,
            Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(config)).autoCompactionEnabled)
    }
}
