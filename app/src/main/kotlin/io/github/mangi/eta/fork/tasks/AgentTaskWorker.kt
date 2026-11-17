package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager entry point for schedule materialization and one serialized task dispatch. */
internal class AgentTaskWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val eventPayload = inputData.getString("eventPayload")
                ?.let { raw -> runCatching { org.json.JSONObject(raw) }.getOrNull() }
                ?: org.json.JSONObject()
            AutomationTriggers.poll(applicationContext, inputData.getString("eventType"), inputData.getString("eventKey").orEmpty(), eventPayload)
            val runtimeClient = AgentRuntimeClient(applicationContext, AndroidAgentLogger)
            val maxConcurrentRuns = AgentTaskSchedulerSettings.maxConcurrentRuns(applicationContext)
            val coordinator = AgentTaskCoordinator(
                dao = AgentTaskDatabase.get(applicationContext).agentTaskDao(),
                runner = DefaultAgentTaskRunner(applicationContext),
                runtimeCanceller = AgentTaskRuntimeCanceller(runtimeClient::cancelRun),
                remoteCanceller = AgentTaskRemoteCanceller { agentId, remoteTaskId ->
                    cancelRemoteA2ATask(applicationContext, agentId, remoteTaskId)
                },
                maxConcurrentRuns = maxConcurrentRuns,
                resultStore = AgentTaskResultStore(applicationContext),
            )
            val summary = coordinator.tick(maxRuns = maxConcurrentRuns)
            AutomationTriggers.poll(applicationContext)
            AgentTaskWorkScheduler.refreshNextDeadline(applicationContext)
            if (summary.executedRuns > 0) {
                // Keep draining overdue schedules one long-running task at a time instead of making
                // every backlog item wait for the next 15-minute periodic wake-up.
                AgentTaskWorkScheduler.checkNow(applicationContext)
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AndroidAgentLogger.warn(
                "Scheduled task worker failed: type=${failure.safeLogType()}",
            )
            RuntimeDebugTrace.taskFailure(failure.safeLogType())
            Result.retry()
        }
    }
}
