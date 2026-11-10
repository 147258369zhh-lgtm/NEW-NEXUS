package io.github.mangi.eta.fork.tasks

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Keeps resumed runs alive when the task-center page leaves composition. */
internal object AgentTaskBackgroundExecutor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resumingRuns = ConcurrentHashMap.newKeySet<String>()

    fun resumeWaiting(context: Context, runId: String, continuationInput: String): Boolean {
        if (continuationInput.isBlank() || !resumingRuns.add(runId)) return false
        val appContext = context.applicationContext
        scope.launch {
            try {
                val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
                val coordinator = AgentTaskCoordinator(
                    dao = AgentTaskDatabase.get(appContext).agentTaskDao(),
                    runner = DefaultAgentTaskRunner(appContext),
                    runtimeCanceller = AgentTaskRuntimeCanceller(client::cancelRun),
                )
                coordinator.resumeWaiting(runId, continuationInput.trim())
                AgentTaskWorkScheduler.refreshNextDeadline(appContext)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                RuntimeDebugTrace.taskFailure(failure.safeLogType())
                AndroidAgentLogger.warn(
                    "Scheduled task resume failed: type=${failure.safeLogType()}",
                )
            } finally {
                resumingRuns.remove(runId)
            }
        }
        return true
    }
}
