package io.github.mangi.eta.agent.control

import android.app.Application
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.runBlocking
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
class ModelSettingsControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    private val original = Model("model-one", "fixture-text", "Fixture", contextWindowOverride = 4096, toolCall = true)
    @Before fun setup() = runBlocking {
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        SettingsDataStore.init(context); ProviderRepository.init(context)
        ProviderRepository.addProvider(CustomProviderSetting(id = "fixture-provider", name = "Fixture", baseUrl = "https://fixture.invalid",
            apiKey = "provider-private-fixture", models = listOf(original)))
        Unit
    }
    @After fun close() = EtaDatabase.closeForTests()
    private suspend fun current() = ProviderRepository.providerById("fixture-provider")!!.models.single { it.id == original.id }

    @Test fun uiContextAndReasoningEditShareTheAgentMutationAndCanUndoWithoutAffectingProviderSecrets() = runBlocking {
        val saved = plane.saveModel("fixture-provider", original.copy(contextWindowOverride = 8192, reasoningOverride = true))
        assertEquals(8192, saved.contextWindowOverride)
        val audit = plane.audit().single { it.optString("operation") == "execute" }
        assertTrue(audit.getBoolean("rollbackSafe"))
        assertFalse(audit.toString().contains("provider-private-fixture"))
        plane.rollbackJson(audit.getString("id"), SettingsActorKind.LOCAL_USER)
        assertEquals(4096, current().contextWindowOverride)
        assertNull(current().reasoningOverride)
        assertEquals("provider-private-fixture", ProviderRepository.providerById("fixture-provider")!!.apiKey)
    }

    @Test fun agentUpdatesOnlyDeclaredFieldsAndPreservesPrivateConfigurationOnUndo() = runBlocking {
        val privateModel = original.copy(customHeaders = listOf(CustomHeader("X-Private", "header-secret-fixture")))
        ModelRepository.saveModel("fixture-provider", privateModel)
        val value = JSONObject().put("contextWindowOverride", 12288).put("toolCall", false)
        val changed = plane.applyJson("settings.model.configure", JSONObject().put("providerId", "fixture-provider")
            .put("modelId", original.id).put("value", value), SettingsActorKind.LOCAL_AGENT, sourceAgent = "model-operator")
        assertEquals(12288, current().contextWindowOverride)
        assertFalse(current().supportsTools)
        assertFalse(changed.toString().contains("header-secret-fixture"))
        assertFalse(plane.audit().toString().contains("header-secret-fixture"))
        plane.rollbackJson(changed.getString("auditId"), SettingsActorKind.LOCAL_USER)
        assertEquals(4096, current().contextWindowOverride)
        assertEquals(privateModel.customHeaders, current().customHeaders)
    }

    @Test fun uiPrivateFieldChangesAreAppliedButNeverStoredAsARollbackSnapshot() = runBlocking {
        plane.saveModel("fixture-provider", original.copy(customHeaders = listOf(CustomHeader("X-Private", "new-secret-fixture"))))
        val audit = plane.audit().single { it.optString("operation") == "execute" }
        assertEquals("new-secret-fixture", current().customHeaders.single().value)
        assertFalse(audit.getBoolean("rollbackSafe"))
        assertFalse(audit.toString().contains("new-secret-fixture"))
        assertEquals("ROLLBACK_SNAPSHOT_UNVERIFIED", runCatching { plane.rollbackJson(audit.getString("id"), SettingsActorKind.LOCAL_USER) }.exceptionOrNull()?.message)
    }

    @Test fun newModelCanBeRemovedByUndoAndLaterEditPreventsAnOldUndo() = runBlocking {
        val added = plane.saveModel("fixture-provider", Model("", "new-model", "New model"))
        val created = plane.audit().single { it.optString("operation") == "execute" }
        plane.rollbackJson(created.getString("id"), SettingsActorKind.LOCAL_USER)
        assertFalse(ProviderRepository.providerById("fixture-provider")!!.models.any { it.id == added.id })
        plane.saveModel("fixture-provider", original.copy(contextWindowOverride = 8192))
        val first = plane.audit().last { it.optString("operation") == "execute" }.getString("id")
        plane.saveModel("fixture-provider", current().copy(displayName = "Renamed later"))
        assertEquals("SETTINGS_CHANGED_SINCE_AUDIT", runCatching { plane.rollbackJson(first, SettingsActorKind.LOCAL_USER) }.exceptionOrNull()?.message)
        assertEquals("Renamed later", current().displayName)
    }

    @Test fun unknownFieldsInvalidContextAndRemoteRequestsCannotChangeTheModel() = runBlocking {
        suspend fun apply(value: JSONObject, actor: SettingsActorKind = SettingsActorKind.LOCAL_AGENT) = plane.applyJson("settings.model.configure",
            JSONObject().put("providerId", "fixture-provider").put("modelId", original.id).put("value", value), actor)
        assertTrue(runCatching { apply(JSONObject().put("contextWindowOverride", 0)) }.isFailure)
        assertTrue(runCatching { apply(JSONObject().put("customHeaders", org.json.JSONArray())) }.isFailure)
        assertTrue(runCatching { apply(JSONObject().put("contextWindowOverride", 8192), SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertEquals(4096, current().contextWindowOverride)
        Unit
    }
}
