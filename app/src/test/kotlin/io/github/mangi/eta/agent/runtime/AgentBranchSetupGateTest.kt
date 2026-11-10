package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.*
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.projects.ProjectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentBranchSetupGateTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun close() {
        EtaDatabase.closeForTests(); ConversationExtensionDatabase.closeForTests(); ProjectDatabase.closeForTests()
    }
    @Before fun reset() {
        close(); listOf("eta.db", "eta_fork.db", "eta_projects.db").forEach(context::deleteDatabase)
    }
    @After fun tearDown() { close() }

    @Test(timeout = 30_000) fun missingBranchSettingsStopBeforeAnyModelToolOrGuiPreparation() {
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context).conversationDao()
            dao.insertConversations(listOf(ConversationEntity("child", "Child", false, createdAt = 1, updatedAt = 1)))
            dao.insertBranch(ConversationBranchEntity("child", "root", sourceConversationId = "root", sourceMessageId = "point", sourceSortIndex = 0, createdAt = 1))
            dao.insertBranchSetup(ConversationBranchSetupEntity("child", "setup", folderId = "missing", createdAt = 1))
        }
        val events = mutableListOf<AgentEvent>()
        var permissionsRequested = false
        val gui = GuiControlCoordinator()
        val executor = AgentRuntimeRunExecutor(context,
            currentPermissions = { permissionsRequested = true; error("Must not prepare model or tools") },
            snapshotRequest = { it }, guiControl = gui, onAcceptedEvent = { event, _ -> events += event },
            persistArtifacts = { _, _, _ -> })
        val request = AgentRuntimeWire.RunRequest(runId = "blocked-run", prompt = "Execute task", images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("blocked-run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "child"),
            config = AgentModelClient.ModelConfig(baseUrl = "https://unused.invalid/v1", apiKey = "", model = "unused", systemPrompt = ""))
        val session = AgentRuntimeSession(request.runId)
        val outcome = executor.execute(session, request)
        assertFalse(outcome.result.ok)
        assertEquals(context.getString(io.github.mangi.eta.R.string.branch_setup_required), outcome.result.error)
        assertFalse(permissionsRequested)
        assertNull(gui.state.value.ownerRunId)
        assertNull(outcome.entrySurfaceGuard)
        assertNull(outcome.response)
        assertTrue(session.isTerminal)
        assertEquals(1, events.size)
        assertTrue(events.single() is AgentEvent.RunFailed)
    }
}
