package io.github.mangi.eta.ui.screens.capability

internal sealed interface CapabilityLocalDataState {
    data object Loading : CapabilityLocalDataState
    data object Loaded : CapabilityLocalDataState
    data object Failed : CapabilityLocalDataState
}

internal fun hasCapabilitySnapshot(state: CapabilityLocalDataState): Boolean =
    state is CapabilityLocalDataState.Loaded
