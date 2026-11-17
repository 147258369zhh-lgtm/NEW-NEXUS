package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelReconciliationTest {
    @Test fun refreshKeepsManuallyConfiguredImageOutputWhenRemoteCatalogHasNoModalities() {
        val manual = model("configured", "custom-image", ModelSource.MANUAL)
            .copy(inputModalities = listOf("text", "image"), outputModalities = listOf("image"))
        val remote = model("remote", "custom-image", ModelSource.REMOTE)
            .copy(outputModalities = emptyList())
        val result = reconcileRemoteModels(listOf(manual), listOf(remote), emptySet(), ProviderAuthModes.API_KEY)
        assertEquals(listOf("image"), result.models.single().outputModalities)
        assertEquals(listOf("text", "image"), result.models.single().inputModalities)
        assertEquals(ModelSource.MANUAL, result.models.single().source)
    }

    @Test fun newChatDefaultsPreserveExplicitCapabilitiesAndImageOnlyModels() {
        val unknown = Model(id = "a", modelId = "chat", displayName = "Chat")
        val defaults = unknown.withNewChatDefaults()
        assertEquals(true, defaults.supportsTools)
        assertEquals(true, defaults.supportsVision)
        assertEquals(null, defaults.toolCall)
        assertEquals(null, defaults.attachment)
        val disabled = unknown.copy(toolCall = false, attachment = false, inputModalities = listOf("image"))
            .withNewChatDefaults()
        assertEquals(false, disabled.supportsTools)
        assertEquals(false, disabled.supportsVision)
        val imageOnly = unknown.copy(outputModalities = listOf("image"))
        assertEquals(imageOnly, imageOnly.withNewChatDefaults())
        val retained = reconcileRemoteModels(listOf(unknown), listOf(unknown.copy(id = "new")),
            setOf("chat"), ProviderAuthModes.API_KEY).models.single()
        assertEquals(null, retained.toolCall)
        assertEquals(null, retained.attachment)
        val updated = reconcileRemoteModels(listOf(defaults), listOf(unknown.copy(id = "new", toolCall = false, attachment = false)),
            setOf("chat"), ProviderAuthModes.API_KEY).models.single()
        assertEquals(false, updated.supportsTools)
        assertEquals(false, updated.supportsVision)
        val selected = reconcileRemoteModels(listOf(defaults.copy(toolCall = true, attachment = true)),
            listOf(unknown.copy(id = "new", toolCall = false, attachment = false)), setOf("chat"), ProviderAuthModes.API_KEY).models.single()
        assertEquals(true, selected.supportsTools)
        assertEquals(true, selected.supportsVision)
    }
    @Test
    fun accountCatalogRemovesUnavailableAutomaticModelsAndPreservesMatchingLocalIdentity() {
        val kept = model("local-kept", "available", ModelSource.CATALOG).copy(
            isEnabled = false, contextWindowOverride = 64_000,
            customHeaders = listOf(CustomHeader("x-fixture", "fixture")),
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW)),
        )
        val existing = listOf(
            kept,
            model("local-catalog", "old-api-model", ModelSource.CATALOG),
            model("local-remote", "old-account-model", ModelSource.REMOTE),
            model("local-manual", "manual", ModelSource.MANUAL),
        )
        val fetched = listOf(
            model("remote-kept", "available", ModelSource.REMOTE).copy(contextWindow = 128_000),
            model("remote-new", "new", ModelSource.REMOTE),
            model("remote-unpicked", "unpicked", ModelSource.REMOTE),
        )
        val merged = reconcileRemoteModels(existing, fetched, setOf("NEW"), ProviderAuthModes.OPENAI_CHATGPT)

        assertTrue(merged.result.applied)
        assertEquals(1, merged.result.addedCount)
        assertEquals(2, merged.result.removedCount)
        assertEquals(listOf("available", "manual", "new"), merged.models.map { it.modelId })
        val preserved = merged.models.first()
        assertEquals(kept.id, preserved.id)
        assertEquals(ModelSource.REMOTE, preserved.source)
        assertFalse(preserved.isEnabled)
        assertEquals(64_000, preserved.contextWindowOverride)
        assertEquals(128_000, preserved.contextWindow)
        assertEquals(kept.customHeaders, preserved.customHeaders)
        assertEquals(kept.reasoningCapabilitiesOverride, preserved.reasoningCapabilitiesOverride)
    }

    @Test
    fun latestCatalogReplacesPresetsAndRepeatedRefreshKeepsTheSelectedRemoteIdentity() {
        val selected = model("selected", "gpt-6.1-sol", ModelSource.REMOTE)
        val original = listOf(selected, model("old-preset", "gpt-5.5", ModelSource.CATALOG))
        val fetched = listOf(
            model("response-id", "gpt-6.1-sol", ModelSource.REMOTE),
            model("new-id", "future-model", ModelSource.REMOTE),
        )
        val first = reconcileRemoteModels(original, fetched, fetched.mapTo(mutableSetOf()) { it.modelId }, ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(listOf("gpt-6.1-sol", "future-model"), first.models.map { it.modelId })
        assertEquals(selected.id, first.models.first().id)
        assertTrue(first.models.all { it.source == ModelSource.REMOTE })
        val repeated = reconcileRemoteModels(first.models, fetched, fetched.mapTo(mutableSetOf()) { it.modelId }, ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(first.models, repeated.models)
    }

    @Test
    fun codexRemoteWindowReplacesThePublicApiPresetButPreservesCustomConfiguration() {
        val preset = model("preset", "gpt-6.1-sol", ModelSource.CATALOG)
            .copy(contextWindow = 1_050_000, contextWindowOverride = 1_050_000)
        val fetched = model("remote", preset.modelId, ModelSource.REMOTE).copy(contextWindow = 272_000)
        val adopted = setOf(fetched.modelId)
        val synchronized = reconcileRemoteModels(listOf(preset), listOf(fetched), adopted, ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(272_000, synchronized.models.single().effectiveContextWindow)
        assertEquals(1_050_000, synchronized.models.single().contextWindowOverride)
        assertEquals(preset.id, synchronized.models.single().id)
        val custom = preset.copy(contextWindowOverride = 64_000)
        val configured = reconcileRemoteModels(listOf(custom), listOf(fetched), adopted, ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(64_000, configured.models.single().effectiveContextWindow)
    }

    @Test
    fun synchronizationPreservesAnOverrideEvenWhenItEqualsTheOldCatalogWindow() {
        val stored = model("stored", "model", ModelSource.CATALOG)
            .copy(contextWindow = 64_000, contextWindowOverride = 64_000)
        val remote = model("remote", "model", ModelSource.REMOTE).copy(contextWindow = 128_000)
        val result = reconcileRemoteModels(listOf(stored), listOf(remote), emptySet(), ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(64_000, result.models.single().contextWindowOverride)
        assertEquals(64_000, result.models.single().effectiveContextWindow)
    }

    @Test
    fun validEmptyAccountCatalogRemovesAutomaticModelsButNotManualConfiguration() {
        val existing = listOf(
            model("catalog", "catalog", ModelSource.CATALOG),
            model("remote", "remote", ModelSource.REMOTE),
            model("manual", "manual", ModelSource.MANUAL),
        )
        val merged = reconcileRemoteModels(existing, emptyList(), emptySet(), ProviderAuthModes.XAI_GROK)

        assertTrue(merged.result.applied)
        assertEquals(0, merged.result.fetchedCount)
        assertEquals(2, merged.result.removedCount)
        assertEquals(listOf("manual"), merged.models.map { it.id })
    }

    @Test
    fun apiKeyEmptyCatalogRetainsItsExistingNonDestructiveContract() {
        val existing = listOf(model("catalog", "catalog", ModelSource.CATALOG))
        val merged = reconcileRemoteModels(existing, emptyList(), emptySet(), ProviderAuthModes.API_KEY)

        assertFalse(merged.result.applied)
        assertSame(existing, merged.models)
    }

    private fun model(id: String, modelId: String, source: ModelSource) =
        Model(id = id, modelId = modelId, displayName = modelId, source = source)
}
