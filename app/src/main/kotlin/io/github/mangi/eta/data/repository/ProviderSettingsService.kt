package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.mangi.eta.agent.control.ProviderSettingsControlProvider
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.withId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class ProviderSettingsService(
    context: Context,
    private val fetchCatalog: suspend (ProviderSetting) -> Result<RemoteModelCatalog> = RemoteModelFetcher::fetchCatalog,
) {
    private val context = context.applicationContext
    suspend fun save(value: ProviderSetting, expected: ProviderSetting? = null): ProviderSetting {
        val draft = if (value.id.isBlank()) value.withId(ProviderRepository.newId()) else value
        val before = ProviderRepository.providerById(draft.id)
        val privateChange = before != null && (ProviderSettingsControlProvider.privateHash(before) != ProviderSettingsControlProvider.privateHash(draft))
        val parameters = JSONObject().put("providerId", draft.id).put("value", ProviderSettingsControlProvider.publicModel(draft))
            .put("privateChange", privateChange)
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi("settings.provider.configure", parameters, "provider_settings") {
            val current = ProviderRepository.providerById(draft.id)
            if (expected != null) check(current != null && ProviderSettingsControlProvider.fingerprint(current) == ProviderSettingsControlProvider.fingerprint(expected)) {
                "提供商配置已变化，请重新打开"
            }
            if (before == null) {
                check(current == null) { "提供商编号已存在" }
                ProviderRepository.addProvider(draft)
            } else ProviderRepository.updateProvider(draft)
            ProviderSettingsControlProvider.snapshot(draft.id, privateChange)
        }
        if (before != null && (before.baseUrl != draft.baseUrl || before.apiKey != draft.apiKey ||
                before.authMode != draft.authMode || before.isEnabled != draft.isEnabled ||
                before.customHeaders != draft.customHeaders || before.customBody != draft.customBody)) {
            ProviderConnectionHealthStore.clear(context, draft.id)
        }
        val saved = requireNotNull(ProviderRepository.providerById(draft.id))
        if (before != null && !RemoteModelCatalog(emptyList(), before, null).matches(saved)) {
            synchronized(discoveryJobs) { discoveryJobs.remove(saved.id)?.cancel() }
        }
        if (shouldDiscover(before, saved)) scheduleDiscovery(saved)
        return saved
    }

    private fun scheduleDiscovery(saved: ProviderSetting) {
        synchronized(discoveryJobs) {
            discoveryJobs.remove(saved.id)?.cancel()
            val job = discoveryScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val catalog = withTimeout(20_000) { fetchCatalog(saved).getOrThrow() }
                    ModelRepository.addDiscoveredCatalog(catalog)
                    recordDiscoveryResult(saved, success = true)
                } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                    recordDiscoveryResult(saved, success = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recordDiscoveryResult(saved, success = false)
                }
            }
            discoveryJobs[saved.id] = job
            job.invokeOnCompletion { synchronized(discoveryJobs) { if (discoveryJobs[saved.id] === job) discoveryJobs.remove(saved.id) } }
            job.start()
        }
    }

    private suspend fun recordDiscoveryResult(saved: ProviderSetting, success: Boolean) {
        ProviderRepository.mutationMutex.withLock {
            val current = ProviderRepository.providerById(saved.id)
            if (current != null && RemoteModelCatalog(emptyList(), saved, null).matches(current)) {
                ProviderConnectionHealthStore.observe(context)
                if (success) ProviderConnectionHealthStore.recordCatalogSuccess(context, saved.id)
                else ProviderConnectionHealthStore.recordCatalogFailure(context, saved.id)
            }
        }
    }

    internal fun shouldDiscover(before: ProviderSetting?, after: ProviderSetting): Boolean =
        after.isEnabled && after.authMode == ProviderAuthModes.API_KEY && after.apiKey.isNotBlank() &&
            !io.github.mangi.eta.agent.packages.PackageAiRegistry.isPackageProvider(after.id) &&
            (before == null || !RemoteModelCatalog(emptyList(), before, null).matches(after))

    companion object {
        private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val discoveryJobs = mutableMapOf<String, Job>()
        internal fun cancelDiscovery(providerId: String) {
            synchronized(discoveryJobs) { discoveryJobs.remove(providerId)?.cancel() }
        }
    }
}
