package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutomationModelSettings
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelCandidateBinding
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.SubagentConfigSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelCandidateRoutingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
                id = PRIMARY_PROVIDER,
                name = "Primary",
                baseUrl = "https://primary.example.invalid/v1",
                apiKey = "fixture",
                models = listOf(Model(
                    id = PRIMARY_MODEL,
                    modelId = "primary-api",
                    displayName = "Primary",
                )),
            ))
            ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
                id = ROUTE_PROVIDER,
                name = "Route",
                baseUrl = "https://route.example.invalid/v1",
                apiKey = "fixture",
                models = listOf(Model(
                    id = ROUTE_MODEL,
                    modelId = "route-vision-api",
                    displayName = "Route Vision",
                    contextWindowOverride = 100_000,
                    attachment = true,
                    toolCall = true,
                )),
            ))
        }
    }

    @Test
    fun selectorKeepsPrimaryFirstAndRejectsEverythingOutsideEligibleSet() {
        val request = ModelCandidateRouteRequest(
            estimatedInputTokens = 1_000,
            requiredTools = true,
        )
        val primary = option("primary", tools = true, vision = false, window = null)
        val unauthorized = option("unauthorized", tools = true, authorized = false, priority = 0)
        val noTools = option("no-tools", tools = false, priority = 1)
        val eligible = option("eligible", tools = true, priority = 2)

        val primaryDecision = ModelCandidateSelector.select(
            primary,
            listOf(eligible, noTools, unauthorized),
            request,
        )
        assertEquals(ModelCandidateSource.PRIMARY, primaryDecision.selected?.source)

        val routed = ModelCandidateSelector.select(
            primary.copy(config = primary.config?.copy(supportsTools = false)),
            listOf(eligible, noTools, unauthorized),
            request,
        )
        assertEquals("eligible", routed.selected?.modelId)
        assertEquals(
            listOf("tools_required", "unauthorized", "tools_required"),
            routed.rejections.map { it.reason },
        )

        val noCandidate = ModelCandidateSelector.select(
            primary.copy(config = primary.config?.copy(supportsTools = false)),
            listOf(noTools, unauthorized),
            request,
        )
        assertNull(noCandidate.selected)
    }

    @Test
    fun chatRouteUsesPersistedCandidateOnlyWhenPrimaryFailsCapabilityRequirements() = runBlocking {
        val primary = RuntimeConfigRepository.buildRuntimeConfig(
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!,
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!.models.single(),
        ).copy(browserTools = true)
        RuntimeConfigRepository.setModelCandidateSettings(ModelCandidateSettings(
            enabled = true,
            candidates = listOf(ModelCandidateBinding(
                providerId = " $ROUTE_PROVIDER ",
                modelId = " $ROUTE_MODEL ",
                purpose = ModelCandidatePurpose.CHAT,
                minimumContextWindow = 50_000,
            )),
        ))
        SettingsDataStore.setModelFallbackSettings(ModelFallbackSettings())

        val decision = RuntimeConfigRepository.routeChatRuntimeConfig(
            primary = primary,
            primaryModelId = PRIMARY_MODEL,
            requirements = ModelCandidateRouteRequest(
                purpose = ModelCandidatePurpose.CHAT,
                estimatedInputTokens = 1_000,
                requiredVision = true,
            ),
        )

        assertEquals(ModelCandidateSource.USER_CANDIDATE, decision.selected?.source)
        assertEquals(ROUTE_MODEL, decision.selected?.modelId)
        assertEquals("route-vision-api", decision.selected?.config?.model)
        assertEquals(true, decision.selected?.config?.browserTools)
        val persisted = RuntimeConfigRepository.modelCandidateSettings().candidates.single()
        assertEquals(ROUTE_PROVIDER, persisted.providerId)
        assertEquals(ModelCandidatePurpose.CHAT, persisted.purpose)
        assertEquals(true, persisted.enabled)
    }

    @Test
    fun chatRouteIncludesSingleFallbackAndFailsClosedWithoutAnyEligibleCandidate() = runBlocking {
        val primary = RuntimeConfigRepository.buildRuntimeConfig(
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!,
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!.models.single(),
        )
        SettingsDataStore.setModelCandidateSettings(ModelCandidateSettings(enabled = true))
        SettingsDataStore.setModelFallbackSettings(ModelFallbackSettings(
            enabled = true,
            providerId = ROUTE_PROVIDER,
            modelId = ROUTE_MODEL,
        ))
        val requirements = ModelCandidateRouteRequest(
            purpose = ModelCandidatePurpose.CHAT,
            estimatedInputTokens = 1_000,
            requiredVision = true,
        )

        val fallback = RuntimeConfigRepository.routeChatRuntimeConfig(primary, PRIMARY_MODEL, requirements)
        assertEquals(ModelCandidateSource.FALLBACK, fallback.selected?.source)

        SettingsDataStore.setModelFallbackSettings(ModelFallbackSettings())
        val denied = RuntimeConfigRepository.routeChatRuntimeConfig(primary, PRIMARY_MODEL, requirements)
        assertNull(denied.selected)
    }

    @Test
    fun automationRouteUsesOnlyAutomationPurposeAndFailsClosedOnMismatch() = runBlocking {
        val primary = RuntimeConfigRepository.buildRuntimeConfig(
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!,
            ProviderRepository.providerById(PRIMARY_PROVIDER)!!.models.single(),
        )
        SettingsDataStore.setModelFallbackSettings(ModelFallbackSettings())
        SettingsDataStore.setModelCandidateSettings(ModelCandidateSettings(
            enabled = false,
            candidates = listOf(ModelCandidateBinding(
                providerId = ROUTE_PROVIDER,
                modelId = ROUTE_MODEL,
                purpose = ModelCandidatePurpose.AUTOMATION,
            )),
        ))

        RuntimeConfigRepository.setAutomationModelSettings(AutomationModelSettings(candidateRoutingEnabled = false))
        val legacy = RuntimeConfigRepository.automationRuntimeConfig(primary, profileName = "default")
        assertEquals(SubagentConfigSource.PRIMARY, legacy.modelSource)
        assertEquals("primary-api", legacy.config.model)

        RuntimeConfigRepository.setAutomationModelSettings(AutomationModelSettings(candidateRoutingEnabled = true))
        val routed = RuntimeConfigRepository.automationRuntimeConfig(primary, profileName = "default")
        assertEquals(SubagentConfigSource.CANDIDATE, routed.modelSource)
        assertEquals("route-vision-api", routed.config.model)

        SettingsDataStore.setModelCandidateSettings(ModelCandidateSettings(
            enabled = false,
            candidates = listOf(ModelCandidateBinding(
                providerId = ROUTE_PROVIDER,
                modelId = ROUTE_MODEL,
                purpose = ModelCandidatePurpose.CHAT,
            )),
        ))
        val denied = runCatching {
            RuntimeConfigRepository.automationRuntimeConfig(primary, profileName = "default")
        }.exceptionOrNull()
        assertTrue(denied is IllegalStateException)
        assertTrue(denied?.message.orEmpty().contains("自动任务"))
    }

    private fun option(
        id: String,
        tools: Boolean,
        vision: Boolean = false,
        window: Int? = 100_000,
        authorized: Boolean = true,
        priority: Int = 0,
    ) = ModelCandidateRouteOption(
        providerId = "provider-$id",
        modelId = id,
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "",
            model = id,
            systemPrompt = "",
            contextWindow = window,
            supportsTools = tools,
            supportsImageInput = vision,
        ),
        source = if (id == "primary") ModelCandidateSource.PRIMARY else ModelCandidateSource.USER_CANDIDATE,
        authorized = authorized,
        priority = priority,
    )

    private companion object {
        const val PRIMARY_PROVIDER = "candidate-primary-provider"
        const val PRIMARY_MODEL = "candidate-primary-model"
        const val ROUTE_PROVIDER = "candidate-route-provider"
        const val ROUTE_MODEL = "candidate-route-model"
    }
}
