package io.github.mangi.eta.ui

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal sealed interface ProviderSettingsLoadState {
    data object Loading : ProviderSettingsLoadState
    data class Loaded(val providers: List<ProviderSetting>) : ProviderSettingsLoadState
    data object Failed : ProviderSettingsLoadState
}

internal fun providerSettingsLoadState(source: Flow<List<ProviderSetting>>): Flow<ProviderSettingsLoadState> =
    source.map<List<ProviderSetting>, ProviderSettingsLoadState> { ProviderSettingsLoadState.Loaded(it) }
        .catch { emit(ProviderSettingsLoadState.Failed) }

internal fun cachedProviderSettingsLoadState(): ProviderSettingsLoadState =
    ProviderRepository.cachedProviders()?.let(ProviderSettingsLoadState::Loaded) ?: ProviderSettingsLoadState.Loading
