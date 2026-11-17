package io.github.mangi.eta.data.repository

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProviderConnectionHealthTest {
    @Test fun saveReturnsWhileDiscoveryIsPending() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        io.github.mangi.eta.data.db.EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        io.github.mangi.eta.data.datastore.SettingsDataStore.init(context)
        ProviderRepository.init(context)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val service = ProviderSettingsService(context) { provider ->
            started.complete(Unit)
            release.await()
            Result.success(RemoteModelCatalog(emptyList(), provider, null))
        }
        val provider = io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
            id = "async-discovery", name = "Provider", baseUrl = "https://example.invalid/v1", apiKey = "fixture")
        try {
            val saved = kotlinx.coroutines.withTimeout(5_000) { service.save(provider) }
            assertEquals(provider.id, saved.id)
            kotlinx.coroutines.withTimeout(5_000) { started.await() }
            assertEquals(provider.apiKey, ProviderRepository.providerById(provider.id)?.apiKey)
        } finally { release.complete(Unit) }
    }
    @Test fun catalogSuccessIsDistinctFromModelInferenceSuccess() {
        val context = RuntimeEnvironment.getApplication()
        ProviderConnectionHealthStore.recordCatalogSuccess(context, "catalog-provider")
        val record = ProviderConnectionHealthStore.observe(context).value.getValue("catalog-provider")
        assertEquals(ProviderHealthState.CATALOG_AVAILABLE, record.state)
        assertEquals("", record.modelId)
        ProviderConnectionHealthStore.clear(context, "catalog-provider")
    }

    @Test fun discoveryRunsOnlyForNewOrChangedUsableApiKeyConnection() {
        val service = ProviderSettingsService(RuntimeEnvironment.getApplication())
        val provider = io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
            id = "provider", name = "Provider", baseUrl = "https://example.invalid/v1", apiKey = "key")
        assertEquals(true, service.shouldDiscover(null, provider))
        assertEquals(false, service.shouldDiscover(provider, provider.copy(name = "Renamed")))
        assertEquals(true, service.shouldDiscover(provider, provider.copy(apiKey = "new-key")))
        assertEquals(false, service.shouldDiscover(null, provider.copy(apiKey = "")))
        assertEquals(false, service.shouldDiscover(null, provider.copy(isEnabled = false)))
    }
    @Test
    fun latestProbeResultPersistsWithoutProviderSecrets() {
        val context = RuntimeEnvironment.getApplication()
        ProviderConnectionHealthStore.clear(context, "health-provider")

        ProviderConnectionHealthStore.recordFailure(context, "health-provider", "model-a", "HTTP 401")
        assertEquals(ProviderHealthState.FAILED,
            ProviderConnectionHealthStore.observe(context).value.getValue("health-provider").state)

        ProviderConnectionHealthStore.recordSuccess(context, "health-provider", "model-b")
        val healthy = ProviderConnectionHealthStore.observe(context).value.getValue("health-provider")
        assertEquals(ProviderHealthState.HEALTHY, healthy.state)
        assertEquals("model-b", healthy.modelId)
        assertEquals("连接测试成功", healthy.message)

        ProviderConnectionHealthStore.clear(context, "health-provider")
        assertNull(ProviderConnectionHealthStore.observe(context).value["health-provider"])
    }
}
