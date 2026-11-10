package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Keeps a durable, updateable wake-up loop in WorkManager. */
internal object AgentTaskWorkScheduler {
    private const val PERIODIC_WORK_NAME = "eta_agent_task_tick"
    private const val CHECK_NOW_WORK_NAME = "eta_agent_task_check_now"
    private const val NEXT_DEADLINE_WORK_NAME = "eta_agent_task_next_deadline"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<AgentTaskWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            CHECK_NOW_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /** Refreshes the exact next wake-up after task definitions or schedules change. */
    suspend fun refreshNextDeadline(context: Context) {
        val dao = AgentTaskDatabase.get(context).agentTaskDao()
        val nextRunAt = listOfNotNull(dao.earliestNextRunAt(), dao.earliestQueuedAt()).minOrNull()
        if (nextRunAt == null) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(NEXT_DEADLINE_WORK_NAME)
        } else {
            scheduleAt(context, nextRunAt)
        }
    }

    private fun scheduleAt(context: Context, epochMillis: Long) {
        val delay = max(0L, epochMillis - System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NEXT_DEADLINE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
