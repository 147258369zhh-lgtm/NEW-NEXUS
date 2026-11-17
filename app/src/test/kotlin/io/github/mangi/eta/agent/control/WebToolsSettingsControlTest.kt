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
class WebToolsSettingsControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    private val secrets = WebToolsCredentials()
    @Before fun setup() = runBlocking {
        SettingsDataStore.init(context); ProviderRepository.init(context)
        SettingsDataStore.setWebToolsSettings(WebToolsSettings())
    }
    @After fun close() = EtaDatabase.closeForTests()

    @Test fun originalUiSaveAndAgentBackendSwitchUseTheRealRuntimeConfigurationAndReversibleAudit() = runBlocking {
        val configured = WebToolsSettings(customSearch = CustomWebSearchConfig(url = "https://example.com/search"))
        SettingsDataStore.setWebToolsSettings(configured)
        val service = WebToolsSettingsService(context)
        service.save(configured.copy(searchBackend = WebToolBackend.CUSTOM), secrets)
        assertEquals(WebToolBackend.CUSTOM, WebToolsSettingsRepository.resolveRuntimeConfig(context).searchBackend)
        val audit = plane.audit().single { it.optString("operation") == "execute" }
        assertTrue(audit.getBoolean("rollbackSafe"))
        plane.rollbackJson(audit.getString("id"), SettingsActorKind.LOCAL_USER)
        assertEquals(configured, WebToolsSettingsRepository.settings())
        val applied = plane.applyJson("settings.web_tools", JSONObject().put("value", JSONObject().put("searchBackend", "CUSTOM")), SettingsActorKind.LOCAL_AGENT)
        assertTrue(applied.getBoolean("ok"))
        assertEquals(WebToolBackend.CUSTOM, WebToolsSettingsRepository.resolveRuntimeConfig(context).searchBackend)
        assertEquals("https://example.com/search", WebToolsSettingsRepository.settings().customSearch.url)
        assertEquals(WebToolsCredentials(), WebToolsSettingsRepository.credentials(context))
    }

    @Test fun headersBodyAndUrlSecretsNeverEnterTheAuditOrReadResultAndLaterChangesBlockOldUndo() = runBlocking {
        val config = WebToolsSettings(customSearch = CustomWebSearchConfig(url = "https://example.com/search?key=private-url-marker",
            headersText = "Authorization: Bearer private-header-marker", bodyTemplate = "{\"private\":\"private-body-marker\"}"))
        val service = WebToolsSettingsService(context)
        service.save(config, secrets)
        val audit = plane.audit().single { it.optString("operation") == "execute" }
        assertFalse(audit.getBoolean("rollbackSafe"))
        val output = plane.audit().toString() + plane.readJson("settings.web_tools", SettingsActorKind.LOCAL_USER).toString()
        listOf("private-url-marker", "private-header-marker", "private-body-marker").forEach { assertFalse(output.contains(it)) }
        service.save(config.copy(customSearch = config.customSearch.copy(resultsPath = "items")), secrets)
        assertEquals(config.customSearch.headersText, WebToolsSettingsRepository.settings().customSearch.headersText)
    }

    @Test fun invalidBackendConfigurationIsRejectedBeforeAnySettingsOrSecretsChange() = runBlocking {
        val original = WebToolsSettingsRepository.settings()
        val service = WebToolsSettingsService(context)
        assertTrue(runCatching { service.save(original.copy(searchBackend = WebToolBackend.CUSTOM), secrets) }.isFailure)
        assertTrue(runCatching { plane.applyJson("settings.web_tools", JSONObject().put("value", JSONObject().put("fetchBackend", "DEEPSEEK")), SettingsActorKind.LOCAL_AGENT) }.isFailure)
        assertTrue(runCatching { plane.applyJson("settings.web_tools", JSONObject().put("value", JSONObject().put("customSearchApiKey", "forged")), SettingsActorKind.LOCAL_AGENT) }.isFailure)
        assertTrue(runCatching { plane.applyJson("settings.web_tools", JSONObject().put("value", JSONObject().put("searchBackend", "BUILTIN")), SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertEquals(original, WebToolsSettingsRepository.settings())
        assertFalse(context.getSharedPreferences("eta_web_tools_secrets", 0).contains("credentials"))
        Unit
    }
}
