package io.github.mangi.eta.fork.tasks

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.NexusUiContract

internal object AutomationNotifications {
    fun show(context: Context, id: String, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled()) { "通知权限未开启" }
        manager.createNotificationChannel(NotificationChannel("automation-results", "自动任务结果", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id.hashCode(), NexusUiContract.launchIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id.hashCode(), Notification.Builder(context, "automation-results").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(100)).setContentText(text.take(1000)).setStyle(Notification.BigTextStyle().bigText(text.take(4000)))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
