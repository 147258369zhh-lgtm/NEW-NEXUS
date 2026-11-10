package io.github.mangi.eta.fork.conversations

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object ConversationArchiveScheduler {
    private const val PERIODIC_WORK_NAME = "eta_conversation_auto_archive"
    private const val CHECK_NOW_WORK_NAME = "eta_conversation_archive_check_now"

    fun sync(context: Context, settings: ConversationArchiveSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ConversationArchiveWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConversationArchiveWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            CHECK_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
