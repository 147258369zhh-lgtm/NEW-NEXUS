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

    @Test fun invalidPathsAndUnknownRecordsAreRejected() {
        val store = WorkspaceArtifactStore(context)
        assertFalse(store.remove("missing"))
        runCatching { store.record("run", "../outside") }.onSuccess { error("expected invalid path") }
        val file = File(context.filesDir, "workspace-artifacts.json")
        file.writeText("[{\"path\":\"ok.txt\"},{\"runId\":\"run\",\"path\":\"ok.txt\"}]")
        assertNotNull(store.list().singleOrNull())
    }
}
