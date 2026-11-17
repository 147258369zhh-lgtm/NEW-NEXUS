package io.github.mangi.eta.agent.packages

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class PackageDesktopWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PackageDesktopWidgetService.ACTION_PINNED) { super.onReceive(context, intent); return }
        val pending = goAsync()
        java.util.concurrent.Executors.newSingleThreadExecutor().also { executor -> executor.execute {
            try {
                val service = PackageDesktopWidgetService(context)
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                service.available(intent.getStringExtra("packageId")).singleOrNull {
                    it.id == intent.getStringExtra("widgetId") && it.record.digest == intent.getStringExtra("digest")
                }?.let { service.bind(id, it) }
            } catch (_: Exception) { PackageDesktopWidgetService.refresh(context) }
            finally { pending.finish(); executor.shutdown() }
        } }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PackageDesktopWidgetService.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = PackageDesktopWidgetService.refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) { PackageDesktopWidgetService(context).unbind(ids) }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) { PackageDesktopWidgetService(context).remap(oldIds, newIds) }
}
