package io.github.mangi.eta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.mangi.eta.data.repository.NotificationHistoryRepository

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        activeNotifications?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        // Notification access sees Eta's own foreground-service and run status updates. Recording
        // them pollutes real notification history and can recursively expose Agent run text.
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        repository.record(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = notificationText(extras),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
        )
    }

    private fun notificationText(extras: android.os.Bundle): String? {
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.joinToString("\n")
            ?.takeIf(String::isNotBlank)
    }

    companion object {
        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}
