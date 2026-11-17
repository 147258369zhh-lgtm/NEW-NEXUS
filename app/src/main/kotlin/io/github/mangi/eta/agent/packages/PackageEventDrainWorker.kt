package io.github.mangi.eta.agent.packages

import android.content.Context
import androidx.work.*

class PackageEventDrainWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            PackageApplicationEvents.initialize(applicationContext)
            PackageApplicationEvents.refresh()
            PackageApplicationEvents.awaitIdle()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
    companion object {
        internal fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("package-event-drain", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PackageEventDrainWorker>().build())
        }
    }
}
