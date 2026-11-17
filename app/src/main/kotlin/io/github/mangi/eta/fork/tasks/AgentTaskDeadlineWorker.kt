package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Deadline replacement can cancel this wake-up, but never the task executing on check-now. */
internal class AgentTaskDeadlineWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        AgentTaskWorkScheduler.checkNow(applicationContext).result.get()
        Result.success()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}
