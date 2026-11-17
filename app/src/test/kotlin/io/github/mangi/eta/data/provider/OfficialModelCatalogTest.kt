package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialModelCatalogTest {
    @Test
    fun everyOfficialProviderPresetHasAUsableContextWindow() {
        val model = OfficialModelCatalog.modelsForProvider(BuiltinProviders.providerById(BuiltinProviders.DEEPSEEK_ID)!!).first()
        assertTrue(model.contextWindow!! > 0)
        assertEquals(model.contextWindow, model.effectiveContextWindow)
        assertEquals(64_000, model.copy(contextWindowOverride = 64_000).effectiveContextWindow)
    }

    @Test
    fun openAiPresetsHaveTheDocumentedWindowConfiguredForRuntime() {
        val models = OfficialModelCatalog.modelsForProvider(BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID)!!)
        assertEquals(setOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra"), models.mapTo(mutableSetOf()) { it.modelId })
        models.forEach { model ->
            assertEquals(1_050_000, model.contextWindow)
            assertEquals(1_050_000, model.effectiveContextWindow)
            assertEquals(64_000, model.copy(contextWindowOverride = 64_000).effectiveContextWindow)
        }
    }

    @Test
    fun revisionOnlyOffersNewModelsAndEveryModelIdIsUnique() {
        BuiltinProviders.PROVIDERS.forEach { provider ->
            val models = OfficialModelCatalog.modelsForProvider(provider)
            val added = OfficialModelCatalog.modelsAddedSince(provider, revision = 0)

            assertEquals(models.size, models.map { it.modelId.lowercase() }.toSet().size)
            assertEquals(models.indices.toList(), models.map { it.sortOrder })
            assertTrue(models.all { it.source == ModelSource.CATALOG })
            assertTrue(added.all { addition -> models.any { it.modelId == addition.modelId } })
            assertTrue(OfficialModelCatalog.modelsAddedSince(provider, OfficialModelCatalog.CURRENT_REVISION).isEmpty())
        }
    }

    @Test
    fun newPresetsUseCurrentPlatformIdsAndCapabilities() {
        fun models(providerId: String) = OfficialModelCatalog.modelsForProvider(
            BuiltinProviders.providerById(providerId)!!
        ).associateBy { it.modelId }

        assertEquals(setOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra"), models(BuiltinProviders.OPENAI_ID).keys)
        assertEquals(1_050_000, models(BuiltinProviders.OPENAI_ID).getValue("gpt-6.1-sol").contextWindow)
        assertEquals(1_050_000, models(BuiltinProviders.OPENAI_ID).getValue("gpt-6-astra").contextWindow)
        assertTrue(models(BuiltinProviders.ANTHROPIC_ID).getValue("claude-opus-5-5").supportsTools)
        assertTrue(models(BuiltinProviders.BAILIAN_ID).getValue("qwen3.8-flash").supportsVision)
        assertFalse("video" in models(BuiltinProviders.BAILIAN_ID).getValue("kimi-k3").inputModalities)
        assertTrue(models(BuiltinProviders.DEEPSEEK_ID).getValue("deepseek-flash").supportsVision)
        assertFalse("deepseek-v4-flash" in models(BuiltinProviders.DEEPSEEK_ID))
        assertFalse("kimi-k2.5" in models(BuiltinProviders.KIMI_ID))
        assertTrue("audio" in models(BuiltinProviders.MIMO_ID).getValue("mimo-v2.6-pro").inputModalities)
        assertTrue("video" in models(BuiltinProviders.MINIMAX_ID).getValue("MiniMax-M3").inputModalities)
        assertEquals(1_000_000, models(BuiltinProviders.STEPFUN_ID).getValue("step-5-preview").contextWindow)
    }

    @Test
    fun everyBuiltInModelDeclaresTheCapabilitiesRequiredForSafeRouting() {
        BuiltinProviders.PROVIDERS.forEach { provider ->
            OfficialModelCatalog.modelsForProvider(provider).forEach { model ->
                assertTrue(
                    "${provider.id}/${model.modelId} must declare a positive context window",
                    model.contextWindow != null && model.contextWindow > 0,
                )
                assertEquals(
                    "${provider.id}/${model.modelId} must expose its catalog window to Runtime",
                    model.contextWindow,
                    model.effectiveContextWindow,
                )
                assertEquals(model.toolCall, true)
                assertTrue(Model.TEXT_MODALITY in model.inputModalities)
                assertTrue(Model.TEXT_MODALITY in model.outputModalities)
                if (Model.IMAGE_MODALITY in model.inputModalities) {
                    assertTrue(model.supportsVision)
                }
            }
        }
    }
}
