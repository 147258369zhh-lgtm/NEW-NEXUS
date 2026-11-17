package io.github.mangi.eta.agent.control

import android.app.Application
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProviderSettingsControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    private val service get() = ProviderSettingsService(context)
    @Before fun setup() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db"); SettingsDataStore.init(context); ProviderRepository.init(context) }
    @After fun close() = EtaDatabase.closeForTests()

    private suspend fun approveUndo(id: String) = coroutineScope {
        val task = async(Dispatchers.IO) { plane.rollbackJson(id, SettingsActorKind.LOCAL_USER) }
        try {
            val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            EtaAppControlConfirmation.approve(approval.id)
            withTimeout(3000) { task.await() }
        } finally { task.cancelAndJoin() }
    }

    @Test fun uiNameAndPromptChangesCanUndoWithoutCopyingSecretsOrReplacingModels() = runBlocking {
        val original = service.save(CustomProviderSetting("provider", "Original", "https://example.com/v1", apiKey = "provider-private-value",
            customHeaders = listOf(CustomHeader("X-Private", "header-private-value")), models = listOf(Model("model", "fixture", "Fixture"))))
        val next = (original as CustomProviderSetting).copy(name = "Renamed", systemPrompt = "Use clear output")
        service.save(next, original)
        val audit = plane.audit().last { it.optString("operation") == "execute" }
        assertTrue(audit.getBoolean("rollbackSafe"))
        assertFalse(plane.audit().toString().contains("provider-private-value"))
        assertFalse(plane.audit().toString().contains("header-private-value"))
        approveUndo(audit.getString("id"))
        val restored = ProviderRepository.providerById(original.id)!!
        assertEquals("Original", restored.name)
        assertEquals(original.apiKey, restored.apiKey)
        assertEquals(original.models, restored.models)
        assertEquals(original.customHeaders, restored.customHeaders)
    }

    @Test fun privateChangesAndLaterUiEditsCannotBeOverwrittenByAnOldRollback() = runBlocking {
        val original = service.save(CustomProviderSetting("provider", "Original", "https://example.com/v1", apiKey = "original-private"))
        service.save((original as CustomProviderSetting).copy(apiKey = "new-private"), original)
        val privateAudit = plane.audit().last { it.optString("operation") == "execute" }
        assertFalse(privateAudit.getBoolean("rollbackSafe"))
        assertFalse(privateAudit.toString().contains("new-private"))
        assertTrue(runCatching { service.save(original.copy(name = "Stale"), original) }.isFailure)
        assertEquals("new-private", ProviderRepository.providerById(original.id)!!.apiKey)
        Unit
    }

    @Test fun agentChangesRequireLocalConfirmationAndCannotSetKeysOrConnectionAddresses() = runBlocking {
        val original = service.save(CustomProviderSetting("provider", "Original", "https://example.com/v1?key=hidden-url-value", apiKey = "private-key"))
        val args = JSONObject().put("providerId", original.id).put("value", JSONObject().put("name", "Agent name"))
        val task = async(Dispatchers.IO) { plane.applyJson("settings.provider.configure", args, SettingsActorKind.LOCAL_AGENT) }
        try {
            val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            assertEquals("Original", ProviderRepository.providerById(original.id)!!.name)
            EtaAppControlConfirmation.approve(approval.id)
            withTimeout(3000) { task.await() }
            assertEquals("Agent name", ProviderRepository.providerById(original.id)!!.name)
            assertEquals(original.baseUrl, ProviderRepository.providerById(original.id)!!.baseUrl)
            assertFalse(plane.audit().toString().contains("hidden-url-value"))
        } finally { task.cancelAndJoin() }
        assertTrue(runCatching { plane.applyJson("settings.provider.configure", JSONObject(args.toString()).put("value", JSONObject().put("apiKey", "forged")), SettingsActorKind.LOCAL_AGENT) }.isFailure)
        assertTrue(runCatching { plane.applyJson("settings.provider.configure", args, SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertNull(EtaAppControlConfirmation.pending.value)
        Unit
    }
}
