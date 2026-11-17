package io.github.mangi.eta.agent.packages

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class PackageDesktopWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val service = PackageDesktopWidgetService(applicationContext)
        val manager = AppWidgetManager.getInstance(applicationContext)
        for (id in service.ids().take(32)) {
            val controller = AgentRunController()
            try {
                val value = service.resolve(id)
                val document = if (value == null) null else withTimeout(20_000) {
                    runInterruptible { PackageUiService(applicationContext).render(service.module(value, render = true), controller = controller) }
                }
                // Do not publish a late tree from an uninstalled or updated package.
                if (value != null && service.resolve(id)?.record?.digest != value.record.digest) continue
                manager.updateAppWidget(id, PackageWidgetRemoteViews(applicationContext, id).build(value?.title, document))
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                manager.updateAppWidget(id, PackageWidgetRemoteViews(applicationContext, id).status("更新超时，点击重试"))
            } catch (_: Exception) {
                manager.updateAppWidget(id, PackageWidgetRemoteViews(applicationContext, id).status("组件暂不可用，点击查看"))
            } finally { controller.cancel() }
        }
        return Result.success()
    }
}
