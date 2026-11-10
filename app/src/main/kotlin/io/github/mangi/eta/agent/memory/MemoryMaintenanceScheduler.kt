package io.github.mangi.eta.agent.memory

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

internal object MemoryMaintenanceScheduler {
    private const val PERIODIC = "eta-memory-idle-maintenance"
    private const val QUEUE = "eta-memory-maintenance-queue"
    fun sync(context: Context) {
        val s = MemoryAutomationSettingsStore.read(context)
        val manager = WorkManager.getInstance(context)
        if (!s.idleEnabled) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(QUEUE)
            return
        }
        val request = PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(s.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(s)).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun enqueue(context: Context, manual: Boolean = false) {
        val s = MemoryAutomationSettingsStore.read(context)
        if (!manual && !s.idleEnabled) return
        val request = OneTimeWorkRequestBuilder<MemoryMaintenanceWorker>()
            .setInputData(workDataOf("manual" to manual))
            .setConstraints(if (manual) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else constraints(s))
            .setInitialDelay(if (manual) 0 else s.quietMinutes.toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(if (manual) "$QUEUE-manual" else QUEUE, ExistingWorkPolicy.KEEP, request)
    }
    private fun constraints(s: MemoryAutomationSettings) = Constraints.Builder()
        .setRequiresBatteryNotLow(true).setRequiresCharging(s.chargingOnly)
        .setRequiredNetworkType(if (s.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
}
