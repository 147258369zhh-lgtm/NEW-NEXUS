package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityConnectionStatus
import io.github.mangi.eta.agent.accessibility.AccessibilityHealthSnapshot
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityHealth
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionStatusUi

internal fun accessibilityPermissionHealthItem(
    context: Context,
    health: AccessibilityHealthSnapshot = AgentAccessibilityHealth.snapshot(context),
): PermissionHealthItemUi = PermissionHealthItemUi(
    id = "accessibility",
    title = context.getString(R.string.state_accessibility_permissions_f80103),
    summary = context.getString(when (health.status) {
        AccessibilityConnectionStatus.CONNECTED -> R.string.compat_accessibility_connected
        AccessibilityConnectionStatus.DISCONNECTED -> R.string.compat_accessibility_disconnected
        AccessibilityConnectionStatus.DISABLED -> R.string.compat_accessibility_disabled
        AccessibilityConnectionStatus.UNKNOWN -> R.string.compat_accessibility_unknown
    }),
    status = when (health.status) {
        AccessibilityConnectionStatus.CONNECTED -> PermissionStatusUi.Available
        AccessibilityConnectionStatus.DISCONNECTED, AccessibilityConnectionStatus.UNKNOWN -> PermissionStatusUi.Warning
        AccessibilityConnectionStatus.DISABLED -> PermissionStatusUi.Missing
    },
    primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
)
