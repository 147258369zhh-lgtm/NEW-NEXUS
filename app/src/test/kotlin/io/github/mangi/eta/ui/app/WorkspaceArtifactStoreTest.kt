package io.github.mangi.eta.ui.app

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkspaceArtifactStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "workspace-artifacts.json").delete()
    }

    @After fun tearDown() { File(context.filesDir, "workspace-artifacts.json").delete() }

    @Test fun recordsSurviveReloadAndStatusUpdateIsAtomic() {
        val first = WorkspaceArtifactStore(context).record("run-1", "reports/result.md", now = 10)
        val before = WorkspaceArtifactStore(context).list().single()
        assertEquals(WorkspaceArtifact.STATUS_PENDING, before.status)
        assertTrue(WorkspaceArtifactStore(context).updateStatus(first.id, WorkspaceArtifact.STATUS_VERIFIED, now = 20))
        val restored = WorkspaceArtifactStore(context).list().single()
        assertEquals(WorkspaceArtifact.STATUS_VERIFIED, restored.status)
        assertEquals(20L, restored.verifiedAt)
    }

    @Test fun successfulWritesNotifyOtherStoreInstances() {
        val observer = WorkspaceArtifactStore(context).changes()
        val initial = observer.value
        val writer = WorkspaceArtifactStore(context)
        val item = writer.record("run-1", "reports/result.md")
        assertTrue(observer.value > initial)
        val afterRecord = observer.value
        writer.updateStatus(item.id, WorkspaceArtifact.STATUS_VERIFIED)
        assertTrue(observer.value > afterRecord)
        val afterUpdate = observer.value
        assertFalse(writer.remove("missing"))
        assertEquals(afterUpdate, observer.value)
    }

    @Test fun invalidPathsAndUnknownRecordsAreRejected() {
        val store = WorkspaceArtifactStore(context)
        assertFalse(store.remove("missing"))
        runCatching { store.record("run", "../outside") }.onSuccess { error("expected invalid path") }
        val file = File(context.filesDir, "workspace-artifacts.json")
        file.writeText("[{\"path\":\"ok.txt\"},{\"runId\":\"run\",\"path\":\"ok.txt\"}]")
        assertNotNull(store.list().singleOrNull())
    }

    @Test fun verifiedArtifactsEnforceManagedSizeAndFreeSpace() {
        val existing = io.github.mangi.eta.data.repository.WorkspaceArtifact(
            id = "existing",
            runId = "run",
            path = "existing.bin",
            label = "existing.bin",
            status = io.github.mangi.eta.data.repository.WorkspaceArtifact.STATUS_VERIFIED,
            createdAt = 1L,
            sizeBytes = io.github.mangi.eta.data.repository.WorkspaceArtifactCapacityPolicy.MAX_MANAGED_BYTES - 4L,
        )
        val incoming = existing.copy(id = "incoming", path = "incoming.bin", label = "incoming.bin", sizeBytes = 8L)

        val quota = runCatching {
            io.github.mangi.eta.data.repository.WorkspaceArtifactCapacityPolicy.requireAdmission(
                listOf(existing), incoming, usableBytes = Long.MAX_VALUE,
            )
        }.exceptionOrNull()
        assertEquals("ARTIFACT_STORAGE_QUOTA_EXCEEDED", quota?.message)

        val lowSpace = runCatching {
            io.github.mangi.eta.data.repository.WorkspaceArtifactCapacityPolicy.requireAdmission(
                listOf(existing.copy(sizeBytes = 0L)),
                incoming.copy(sizeBytes = 1L),
                usableBytes = io.github.mangi.eta.data.repository.WorkspaceArtifactCapacityPolicy.MIN_FREE_BYTES - 1L,
            )
        }.exceptionOrNull()
        assertEquals("ARTIFACT_STORAGE_LOW_SPACE", lowSpace?.message)
    }
}
