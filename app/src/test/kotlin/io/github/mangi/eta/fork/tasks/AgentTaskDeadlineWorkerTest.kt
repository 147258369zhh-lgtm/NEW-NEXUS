package io.github.mangi.eta.fork.tasks

import android.app.Application
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentTaskDeadlineWorkerTest {
    @Test fun replacingDeadlineNeverCancelsAlreadyQueuedExecution() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManager.initialize(context, Configuration.Builder().build())
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        AgentTaskDatabase.closeForTests()
        context.deleteDatabase("eta_task.db")
        val dao = AgentTaskDatabase.get(context).agentTaskDao()
        val definition = "deadline-definition"
        val now = System.currentTimeMillis()
        try {
            dao.insertDefinition(AgentTaskDefinitionEntity(definition, "Deadline", AgentTaskRunKind.CHAT, "default", "任务", createdAt = now, updatedAt = now))
            dao.insertRunIfAbsent(AgentTaskRunEntity("deadline-run", definition, null, now + 86_400_000L, 1,
                "deadline-manual", AgentTaskState.QUEUED, createdAt = now, updatedAt = now))
            AgentTaskWorkScheduler.refreshNextDeadline(context)
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = manager.getWorkInfosForUniqueWork("eta_agent_task_next_deadline").get(5, TimeUnit.SECONDS).last()
            assertEquals(WorkInfo.State.ENQUEUED, deadline.state)
            assertTrue(deadline.tags.contains(AgentTaskDeadlineWorker::class.java.name))
            AgentTaskWorkScheduler.checkNow(context).result.get(5, TimeUnit.SECONDS)
            val execution = manager.getWorkInfosForUniqueWork("eta_agent_task_check_now").get(5, TimeUnit.SECONDS).last()
            assertTrue(execution.tags.contains(AgentTaskWorker::class.java.name))
            AgentTaskWorkScheduler.refreshNextDeadline(context)
            shadowOf(Looper.getMainLooper()).idle()
            val replaced = manager.getWorkInfosForUniqueWork("eta_agent_task_next_deadline").get(5, TimeUnit.SECONDS)
            assertTrue(replaced.any { it.id != deadline.id && it.state == WorkInfo.State.ENQUEUED })
            val old = manager.getWorkInfoById(deadline.id).get(5, TimeUnit.SECONDS)
            assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
            assertFalse(manager.getWorkInfoById(execution.id).get(5, TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED)
        } finally {
            manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
            AgentTaskDatabase.closeForTests()
        }
    }
}
