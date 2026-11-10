package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

internal const val LOCAL_NETWORK_PERMISSION_ITEM_ID = "local_network"

@Immutable
data class PermissionHealthUiState(
    val items: List<PermissionHealthItemUi>,
    val runtime: DeviceRuntimeUi? = null,
    val rootAccessibilityRecovery: RootAccessibilityRecoveryUi? = null,
) {
    val issueCount: Int get() = items.count { it.requiredForCore && it.status != PermissionStatusUi.Available }
}

@Immutable
data class DeviceRuntimeUi(
    val androidVersion: String,
    val device: String,
    val systemBuild: String,
    val mode: String,
)

@Immutable
data class RootAccessibilityRecoveryUi(
    val enabled: Boolean,
    val summary: String,
    val canToggle: Boolean,
    val canRetry: Boolean,
)

@Immutable
data class PermissionHealthItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val status: PermissionStatusUi,
    val primaryActionLabel: String?,
    val requiredForCore: Boolean = true,
)

@Immutable
enum class PermissionStatusUi {
    Available,
    Missing,
    Warning,
    Disabled,
}
