package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.mangi.eta.fork.projects.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusRunContextResolverTest {
    @Test fun corruptGoalCannotSilentlyExpandTheBudgetOrStartANewRun() {
        val workflow = GoalPlanNotepadStore.get(context)
        assertTrue(workflow.goalManage("context-chat", JSONObject().put("action", "create").put("objective", "fixture")).getBoolean("ok"))
        val directory = java.io.File(context.filesDir, "workflow-state")
        directory.listFiles()!!.single().writeText("{broken", Charsets.UTF_8)
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            assertThrows(Exception::class.java) {
                api.admit(legacy(), resolveContext = NexusRunContextResolver(context)::resolve) { error("must not execute") }
            }
            assertTrue(store.recent().isEmpty())
        }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private fun legacy(id: String = "context-run", conversation: String = "context-chat", mode: String = "GOAL") =
        AgentRuntimeWire.RunRequest(id, "fixture", AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid", apiKey = "private", model = "fixture", systemPrompt = ""),
            images = emptyList(), handoff = AgentRuntimeWire.EntryHandoff(id, "agent_ui",
                AgentUiHandoffPayload(conversation, workflowMode = mode).toJson()))

    @After fun close() {
        ProjectDatabase.closeForTests()
        EtaDatabase.closeForTests()
    }

    private fun project(name: String) = runBlocking(Dispatchers.IO) {
        ProjectRepository.create(context, ProjectDraft(name, "fixture", "root:$name", name, "fixture"))
    }

    @Test fun actualProjectAndGoalReachTheStoreSessionMetricsAndChildWithoutBeingReResolved() {
        val oldProject = project("first")
        val newProject = project("second")
        val legacy = legacy()
        val workflow = GoalPlanNotepadStore.get(context)
        val goal = workflow.goalManage("context-chat", JSONObject().put("action", "create")
            .put("objective", "fixture objective").put("soft_rounds", 1).put("hard_rounds", 2)
            .put("soft_tool_calls", 1).put("hard_tool_calls", 2))
        assertTrue(goal.toString(), goal.getBoolean("ok"))
        val goalId = GoalId(goal.getJSONObject("goal").getString("goal_id"))
        runBlocking(Dispatchers.IO) { ProjectRepository.bindSubject(context, oldProject, ProjectSubjectType.CONVERSATION, "context-chat") }
        NexusExecutionStore(context).use { store ->
            val registry = RuntimeRunRegistry()
            val api = NexusRunApplicationApi(store, registry) {}
            val resolver = NexusRunContextResolver(context)
            var resolutions = 0
            val admitted = api.admit(legacy, resolveContext = { resolutions++; resolver.resolve(it) }) { recorder ->
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, executionRequest = recorder.request,
                    executionStartRecorder = recorder::start))
            } as RuntimeRunRegistry.Admission.Accepted
            val canonical = admitted.run.session.executionRequest!!
            assertEquals(oldProject, canonical.projectId)
            assertEquals(goalId, canonical.goalId)
            assertEquals(2, canonical.budget.hardRounds)
            assertEquals(2, canonical.budget.hardToolCalls)
            assertEquals(canonical, store.find(canonical.runId)?.request)
            runBlocking(Dispatchers.IO) { ProjectRepository.bindSubject(context, newProject, ProjectSubjectType.CONVERSATION, "context-chat") }
            assertTrue(api.admit(legacy, resolveContext = { resolutions++; resolver.resolve(it) }) { error("duplicate") }
                is RuntimeRunRegistry.Admission.Duplicate)
            assertEquals(1, resolutions)
            assertEquals(oldProject, store.find(canonical.runId)?.request?.projectId)
            admitted.run.session.beginExecution()
            val child = api.openChild(canonical.runId, "child", "child-session", RunBudget(hardRounds = 100), AgentRunController())
            assertEquals(oldProject, child.request.projectId)
            assertEquals(goalId, child.request.goalId)
            assertEquals(2, child.request.budget.hardRounds)
            var snapshot: AgentRunTelemetrySnapshot? = null
            AgentRunTelemetry(legacy, projectId = canonical.projectId, onSnapshot = { snapshot = it })
            assertEquals(oldProject, snapshot?.projectId)
            assertEquals(newProject, resolver.resolve(legacy.copy(runId = "next-run")).projectId)
        }
    }

    @Test fun busyAdmissionDoesNotInitializeOrResolveTheRejectedContext() {
        NexusExecutionStore(context).use { store ->
            val registry = RuntimeRunRegistry()
            val api = NexusRunApplicationApi(store, registry) {}
            val first = legacy()
            api.admit(first) { recorder -> RuntimeRunRegistry.Run(first, AgentRuntimeSession(first.runId, executionRequest = recorder.request)) }
            val rejected = api.admit(legacy("other-run"), resolveContext = { error("must not initialize busy run") }) {
                error("must not execute busy run")
            } as RuntimeRunRegistry.Admission.Rejected
            assertEquals("CONVERSATION_BUSY", rejected.code)
            assertEquals(1, store.recent().size)
        }
    }

    @Test fun ordinaryChatDoesNotInheritAnUnrelatedGoalBudgetAndMeetingResolvesItsOwnProject() {
        val project = project("meeting")
        runBlocking(Dispatchers.IO) { ProjectRepository.bindSubject(context, project, ProjectSubjectType.MEETING, "room") }
        val store = GoalPlanNotepadStore.get(context)
        assertTrue(store.goalManage("context-chat", JSONObject().put("action", "create").put("objective", "fixture")).getBoolean("ok"))
        val resolver = NexusRunContextResolver(context)
        val chat = resolver.resolve(legacy(mode = "CHAT"))
        assertNull(chat.goalId)
        assertNull(chat.goalBudget)
        val meeting = legacy().copy(handoff = AgentRuntimeWire.EntryHandoff("meeting", "meeting", "room"))
        assertEquals(project, resolver.resolve(meeting).projectId)
        assertNull(resolver.resolve(meeting).goalId)
    }
}
