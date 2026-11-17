package io.github.mangi.eta.fork.tasks

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentTaskResultStoreTest {
    @Test fun completeFailureReceiptRetainsLongContentAndPauseAndRejectsUnresolvedOrWrongOwner() {
        val store = AgentTaskResultStore(RuntimeEnvironment.getApplication())
        val result = AgentRuntimeWire.RunResult("runtime-receipt", false, "long content".repeat(3_000), "failure",
            reasoningContent = "reason", pause = AgentRuntimeWire.RunPause("INPUT_REQUIRED", question = "继续？"))
        store.save("task", result)
        assertEquals(result, store.read(result.runId, "task"))
        assertThrows(IllegalArgumentException::class.java) { store.read(result.runId, "other-task") }
        assertThrows(IllegalArgumentException::class.java) { store.save("task", result.copy(contextSnapshotRef = "unresolved")) }
        assertEquals(result, store.read(result.runId, "task"))
    }

    @Test fun acknowledgedReceiptRetentionIsBoundedByAge() {
        val context = RuntimeEnvironment.getApplication()
        val store = AgentTaskResultStore(context)
        val old = AgentRuntimeWire.RunResult("runtime-old-receipt", true, "old")
        val fresh = AgentRuntimeWire.RunResult("runtime-fresh-receipt", true, "fresh")
        val root = java.io.File(context.filesDir, "workflow-state/automation-results")
        root.deleteRecursively()
        root.mkdirs()
        val before = root.listFiles().orEmpty().mapTo(hashSetOf()) { it.absolutePath }
        store.save("old-task", old)
        root.listFiles().orEmpty().single { it.absolutePath !in before }.setLastModified(1L)
        val beforeFresh = root.listFiles().orEmpty().mapTo(hashSetOf()) { it.absolutePath }
        store.save("fresh-task", fresh)
        val now = System.currentTimeMillis()
        root.listFiles().orEmpty().single { it.absolutePath !in beforeFresh }.setLastModified(now)

        assertEquals(1, store.prune(now = now, maxAgeMillis = 60_000, maxFiles = 256))
        assertNull(store.read(old.runId))
        assertEquals(fresh, store.read(fresh.runId))
    }
}
