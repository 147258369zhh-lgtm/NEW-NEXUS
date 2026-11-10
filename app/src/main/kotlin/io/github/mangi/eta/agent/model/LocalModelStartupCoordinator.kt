package io.github.mangi.eta.agent.model

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Restores only the built-in model that is still the globally selected chat model. */
internal object LocalModelStartupCoordinator {
    private val mutex = Mutex()
    private var resumed = false

    suspend fun resumeSelectedModel(context: Context) = mutex.withLock {
        if (resumed) return@withLock
        resumed = true
        withContext(Dispatchers.IO) {
            LocalModelRepository.init(context.applicationContext)
            ProviderRepository.ensureBuiltInsMerged()
            val selection = SettingsDataStore.settings()
            val record = chooseRecord(
                providerId = selection.selectedProviderId,
                modelId = selection.selectedModelId,
                models = LocalModelRepository.models.value,
            ) ?: return@withContext
            LocalModelRepository.activateBuiltInRuntime(record.id)
            when (record.format) {
                LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.warmUp(
                    record.id,
                    NativeLaunchKind.AUTOMATIC,
                )
                LocalModelFormat.GGUF -> LlamaCppRuntimeManager.warmUp(
                    record.id,
                    NativeLaunchKind.AUTOMATIC,
                )
                LocalModelFormat.MNN -> MnnRuntimeManager.warmUp(
                    record.id,
                    NativeLaunchKind.AUTOMATIC,
                )
                else -> return@withContext
            }
            LocalModelRepository.selectBuiltInRuntime(record.id)
        }
    }

    @VisibleForTesting
    internal fun chooseRecord(
        providerId: String?,
        modelId: String?,
        models: List<LocalModelRecord>,
    ): LocalModelRecord? = models.firstOrNull { record ->
        providerId != null &&
            modelId != null &&
            record.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN) &&
            record.runtimeSettings.autoConnect &&
            record.runtimeSettings.autoStart &&
            record.boundProviderId == providerId &&
            record.boundModelSelectionId == modelId
    }
}
