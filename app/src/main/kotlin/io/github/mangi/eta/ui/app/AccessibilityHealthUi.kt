package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityConnectionStatus
import io.github.mangi.eta.agent.accessibility.AccessibilityDisconnectReason
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
    summary = buildString {
        append(context.getString(when (health.status) {
            AccessibilityConnectionStatus.CONNECTED -> R.string.compat_accessibility_connected
            AccessibilityConnectionStatus.DISCONNECTED -> R.string.compat_accessibility_disconnected
            AccessibilityConnectionStatus.DISABLED -> R.string.compat_accessibility_disabled
            AccessibilityConnectionStatus.UNKNOWN -> R.string.compat_accessibility_unknown
        }))
        val history = health.history
        val disconnectedAt = history.lastDisconnectedAtMs
        if (history.disconnectCount > 0 && history.lastDisconnectReason != null) {
            val reason = context.getString(when (history.lastDisconnectReason) {
                AccessibilityDisconnectReason.UNBOUND -> R.string.compat_accessibility_disconnect_unbound
                AccessibilityDisconnectReason.DESTROYED -> R.string.compat_accessibility_disconnect_destroyed
                AccessibilityDisconnectReason.PROCESS_EXIT_UNREPORTED -> R.string.compat_accessibility_disconnect_process_exit
            })
            val ago = if (disconnectedAt == null) {
                context.getString(R.string.compat_accessibility_disconnect_time_unavailable)
            } else {
                val elapsedSeconds = ((SystemClock.elapsedRealtime() - disconnectedAt).coerceAtLeast(0L) / 1_000L)
                if (elapsedSeconds < 60L) {
                    context.getString(R.string.compat_accessibility_disconnect_seconds_ago, elapsedSeconds)
                } else {
                    context.getString(R.string.compat_accessibility_disconnect_minutes_ago, elapsedSeconds / 60L)
                }
            }
            append("\n")
            append(context.getString(R.string.compat_accessibility_disconnect_history, history.disconnectCount, reason, ago))
        }
    },
    status = when (health.status) {
        AccessibilityConnectionStatus.CONNECTED -> PermissionStatusUi.Available
        AccessibilityConnectionStatus.DISCONNECTED, AccessibilityConnectionStatus.UNKNOWN -> PermissionStatusUi.Warning
        AccessibilityConnectionStatus.DISABLED -> PermissionStatusUi.Missing
    },
    primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
)
