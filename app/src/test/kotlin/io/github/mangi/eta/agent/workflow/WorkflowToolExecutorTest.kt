package io.github.mangi.eta.agent.workflow

import android.app.Application
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.capability.NexusExecutionGate
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WorkflowToolExecutorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = "workflow-session"
    private fun request() = RunRequest(RunId("workflow-run"), RunSource.GOAL, RunMode.GOAL,
        HostId("native-android"), sessionId = SessionId(scope))
    private fun gateRequest() = AgentRuntimeWire.RunRequest(
        runId = "workflow-run", prompt = "fixture", images = emptyList(),
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "",
        ),
    )
    private fun invoke(controller: AgentRunController, tool: String, args: JSONObject) =
        RuntimeInvocationScope.withController(controller) { WorkflowToolExecutor.execute(context, scope, tool, args) }
    private fun transition(id: String, value: String) = JSONObject().put("action", "transition")
        .put("goal_id", id).put("transition", value).put("confirmed", true).apply {
            if (value == "complete") put("completion", completedNote())
        }

    private fun completedNote() = JSONObject()
        .put("core_criteria_verified", true)
        .put("missing_noncritical_items", JSONArray().put("optional appendix"))
        .put("impact", "Core result is usable")
        .put("evidence", JSONArray().put("verification fixture passed"))
    private fun plan(revision: Long, title: String = "verify fixture") = JSONObject().put("action", "replace")
        .put("expected_revision", revision).put("steps", JSONArray().put(JSONObject().put("id", "inspect")
            .put("title", title).put("verification", "inspect real result").put("reversible", true)))

    private fun preparedGoal(controller: AgentRunController): String {
        val goal = invoke(controller, "goal_manage", JSONObject().put("action", "create").put("objective", "PRIVATE_OBJECTIVE"))
        assertTrue(goal.toString(), goal.getBoolean("ok"))
        val id = goal.getJSONObject("goal").getString("goal_id")
        assertTrue(invoke(controller, "goal_manage", transition(id, "start_planning")).getBoolean("ok"))
        assertTrue(invoke(controller, "plan_manage", plan(0)).getBoolean("ok"))
        return id
    }

    @Test fun completionAppliesFromVerificationWhileStartRequiresVisibleApprovalAndPreservesVersionReferences() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController()
            val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            try {
                val id = preparedGoal(controller)
                suspend fun approveTransition(value: String) {
                    val execution = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, value)) }
                    try {
                        val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                        assertEquals("workflow.goal.$value", approval.capabilityId)
                        assertEquals(RunState.WAITING_AUTH, store.find(request().runId)?.state)
                        assertTrue(EtaAppControlConfirmation.approve(approval.id))
                        val result = withTimeout(3_000) { execution.await() }
                        assertTrue(result.toString(), result.getBoolean("ok"))
                        assertTrue(result.getString("execution_reference").startsWith("nexus-workflow://"))
                    } finally { execution.cancelAndJoin() }
                }
                approveTransition("start_running")
                assertTrue(invoke(controller, "goal_manage", transition(id, "start_verification")).getBoolean("ok"))
                val first = invoke(controller, "notepad_manage", JSONObject().put("action", "append").put("expected_revision", 0)
                    .put("content", "PRIVATE_NOTE"))
                assertTrue(first.toString(), first.getBoolean("ok"))
                assertTrue(invoke(controller, "notepad_manage", JSONObject().put("action", "replace").put("expected_revision", 1)
                    .put("content", "later version")).getBoolean("ok"))
                val saved = WorkflowToolExecutor.execute(context, scope, "notepad_manage", JSONObject()
                    .put("action", "revision").put("revision_ref", first.getString("execution_reference")))
                assertEquals("PRIVATE_NOTE", saved.getString("content"))
                assertEquals(1, saved.getLong("revision"))
                val completed = withTimeout(3_000) { invoke(controller, "goal_manage", transition(id, "complete")) }
                assertTrue(completed.toString(), completed.getBoolean("ok"))
                assertTrue(completed.getString("execution_reference").startsWith("nexus-workflow://"))
                assertNull(EtaAppControlConfirmation.pending.value)
                assertEquals(GoalState.COMPLETED, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)

                val lateWrite = invoke(controller, "notepad_manage", JSONObject().put("action", "append")
                    .put("expected_revision", 2).put("content", "LATE_WRITE"))
                assertEquals("WORKFLOW_TERMINAL_SIDE_EFFECT_DENIED", lateWrite.getString("code"))
                assertEquals(2L, GoalPlanNotepadStore.get(context).executionSnapshot(scope).notepadRevision?.revision)
                assertTrue(WorkflowToolExecutor.execute(context, scope, "notepad_manage", JSONObject().put("action", "read"))
                    .getBoolean("ok"))

                // A fresh controller has no in-process latch; the persisted COMPLETED goal still stops writes.
                val freshController = AgentRunController().apply {
                    bindExecutionIdentity(RunRequest(RunId("fresh-run"), RunSource.GOAL, RunMode.GOAL,
                        HostId("native-android"), goalId = GoalId(id)))
                }
                val lateTool = NexusExecutionGate.authorize(context, gateRequest(), "write_file",
                    JSONObject().put("path", "late.txt").put("content", "late"),
                    freshController, System.currentTimeMillis() + 60_000, workflowScopeId = scope)
                assertEquals("WORKFLOW_TERMINAL_SIDE_EFFECT_DENIED", (lateTool as CapabilityAuthorization.Denied).code)
                assertEquals(CapabilityAuthorization.Allowed, NexusExecutionGate.authorize(context, gateRequest(), "read_file",
                    JSONObject().put("path", "late.txt"), freshController,
                    System.currentTimeMillis() + 60_000, workflowScopeId = scope))
                val events = store.timeline(request().runId)
                assertEquals(5, events.count { it.event.type == RunEventType.GOAL_UPDATED })
                assertEquals(1, events.count { it.event.type == RunEventType.PLAN_UPDATED })
                assertEquals(2, events.count { it.event.type == RunEventType.NOTEPAD_UPDATED })
                assertFalse(events.toString().contains("PRIVATE_OBJECTIVE"))
                assertFalse(events.toString().contains("PRIVATE_NOTE"))
                assertTrue(store.items(request().runId).filter { it.kind == ItemKind.NOTEPAD_REVISION }.all { it.payloadRef != null })
                recorder.finish(AgentRuntimeWire.RunResult("workflow-run", true, "done"), false)
            } finally { controller.cancel() }
        }
    }

    @Test fun forgedConfirmedCannotCompleteStoreGoalAndLocalRejectionPreservesState() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            val id = preparedGoal(controller)
            val direct = GoalPlanNotepadStore.get(context).goalManage(scope, transition(id, "start_running"))
            assertEquals("LOCAL_APPROVAL_REQUIRED", direct.getString("code"))
            val execution = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, "start_running")) }
            try {
                val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                EtaAppControlConfirmation.cancel(approval.id)
                assertEquals("LOCAL_APPROVAL_REJECTED", withTimeout(3_000) { execution.await() }.getString("code"))
                assertEquals(GoalState.WAITING_PLAN_APPROVAL, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)
            } finally { controller.cancel(); execution.cancelAndJoin() }
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "stopped"), true)
        }
    }

    @Test fun directCompletionOutsideVerificationIsRejectedAndDoesNotWriteTheNote() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            val id = preparedGoal(controller)
            val direct = GoalPlanNotepadStore.get(context).goalManage(scope, transition(id, "complete"))
            assertFalse(direct.getBoolean("ok"))
            assertTrue(direct.getString("message"), direct.getString("message").contains("WAITING_PLAN_APPROVAL"))
            val unchanged = GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal
            assertEquals(GoalState.WAITING_PLAN_APPROVAL, unchanged?.state)
            assertNull(unchanged?.completionNote)
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "stopped"), true)
        }
    }

    @Test fun approvalForAnOlderPlanCannotStartAnEditedPlan() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            val id = preparedGoal(controller)
            val execution = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, "start_running")) }
            try {
                val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertTrue(GoalPlanNotepadStore.get(context).planManage(scope, plan(1, "changed plan")).getBoolean("ok"))
                EtaAppControlConfirmation.approve(approval.id)
                assertEquals("WORKFLOW_CHANGED_SINCE_APPROVAL", withTimeout(3_000) { execution.await() }.getString("code"))
                assertEquals(GoalState.WAITING_PLAN_APPROVAL, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)
            } finally { controller.cancel(); execution.cancelAndJoin() }
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "stopped"), true)
        }
    }

    @Test fun pausedGoalBlocksEffectsAndRevisionCannotReuseAnOldResumeApproval() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            try {
                val id = preparedGoal(controller)
                val start = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, "start_running")) }
                try {
                    val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertTrue(EtaAppControlConfirmation.approve(approval.id))
                    val started = start.await()
                    assertTrue(started.toString(), started.getBoolean("ok"))
                } finally { start.cancelAndJoin() }
                assertTrue(invoke(controller, "goal_manage", transition(id, "pause")).getBoolean("ok"))
                assertEquals(GoalState.PAUSED, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)

                val blocked = invoke(controller, "notepad_manage", JSONObject().put("action", "append")
                    .put("expected_revision", 0).put("content", "must not be written"))
                assertEquals("WORKFLOW_PAUSED_SIDE_EFFECT_DENIED", blocked.getString("code"))
                assertNull(GoalPlanNotepadStore.get(context).executionSnapshot(scope).notepadRevision)

                val freshController = AgentRunController().apply {
                    bindExecutionIdentity(RunRequest(RunId("paused-run"), RunSource.GOAL, RunMode.GOAL,
                        HostId("native-android"), goalId = GoalId(id)))
                }
                val denied = NexusExecutionGate.authorize(context, gateRequest(), "write_file",
                    JSONObject().put("path", "paused.txt").put("content", "blocked"),
                    freshController, System.currentTimeMillis() + 60_000, workflowScopeId = scope)
                assertEquals("WORKFLOW_PAUSED_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)

                assertTrue(invoke(controller, "plan_manage", plan(1, "revised while paused")).getBoolean("ok"))
                val staleResume = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, "resume")) }
                try {
                    val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertTrue(invoke(controller, "plan_manage", plan(2, "changed after approval")).getBoolean("ok"))
                    EtaAppControlConfirmation.approve(approval.id)
                    assertEquals("WORKFLOW_CHANGED_SINCE_APPROVAL", withTimeout(3_000) { staleResume.await() }.getString("code"))
                } finally { staleResume.cancelAndJoin() }
                assertEquals(GoalState.PAUSED, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)

                val resume = async(Dispatchers.Default) { invoke(controller, "goal_manage", transition(id, "resume")) }
                try {
                    val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertEquals("3", approval.parameters.toMap()["plan_revision"])
                    EtaAppControlConfirmation.approve(approval.id)
                    val resumed = resume.await()
                    assertTrue(resumed.toString(), resumed.getBoolean("ok"))
                } finally { resume.cancelAndJoin() }
                assertEquals(GoalState.RUNNING, GoalPlanNotepadStore.get(context).executionSnapshot(scope).goal?.state)
                assertTrue(invoke(controller, "notepad_manage", JSONObject().put("action", "append")
                    .put("expected_revision", 0).put("content", "after confirmed resume")).getBoolean("ok"))
                assertTrue(invoke(controller, "goal_manage", transition(id, "pause")).getBoolean("ok"))
                val revised = invoke(controller, "goal_manage", JSONObject().put("action", "revise")
                    .put("goal_id", id).put("expected_revision", 0).put("objective", "revised objective"))
                assertTrue(revised.toString(), revised.getBoolean("ok"))
                assertEquals(1L, revised.getJSONObject("goal").getLong("revision"))
                assertEquals("WAITING_PLAN_APPROVAL", revised.getJSONObject("goal").getString("state"))
                assertEquals(3L, revised.getJSONObject("plan").getLong("revision"))
                assertTrue(revised.getString("execution_reference").startsWith("nexus-workflow://"))
            } finally {
                controller.cancel()
                recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "stopped"), true)
            }
        }
    }

    @Test fun mutationWithoutARealRunRecorderIsRejectedBeforeWritingAndRevisionScopeIsEnforced() {
        val args = JSONObject().put("action", "append").put("expected_revision", 0).put("content", "fixture")
        assertEquals("RUN_CONTEXT_REQUIRED", WorkflowToolExecutor.execute(context, scope, "notepad_manage", args).getString("code"))
        assertEquals("WORKFLOW_LEDGER_UNAVAILABLE", invoke(AgentRunController(), "notepad_manage", args).getString("code"))
        assertNull(GoalPlanNotepadStore.get(context).executionSnapshot(scope).notepadRevision)
        val revisions = WorkflowRevisionStore(context)
        val reference = revisions.save(scope, "notepad", JSONObject().put("content", "fixture"))
        assertThrows(IllegalArgumentException::class.java) { revisions.read("other-scope", "notepad", reference) }
        assertThrows(IllegalArgumentException::class.java) { revisions.read(scope, "plan", reference) }
    }

    @Test fun ledgerWriteFailureReportsAppliedMutationAndAbortsFurtherExecution() {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            store.writableDatabase.execSQL("""CREATE TRIGGER reject_workflow BEFORE INSERT ON execution_events
                WHEN NEW.type='NOTEPAD_UPDATED' BEGIN SELECT RAISE(ABORT,'fixture'); END""")
            val result = invoke(controller, "notepad_manage", JSONObject().put("action", "append").put("expected_revision", 0)
                .put("content", "already saved"))
            assertEquals("WORKFLOW_RESULT_UNRECORDED", result.getString("code"))
            assertTrue(result.getBoolean("mutation_applied"))
            assertEquals("already saved", GoalPlanNotepadStore.get(context).executionSnapshot(scope).notepadRevision?.content)
            assertTrue(controller.abortFailure is WorkflowMutationCommitException)
            assertFalse(store.timeline(request().runId).any { it.event.type == RunEventType.NOTEPAD_UPDATED })
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "unverified"), false)
        }
    }

    @Test fun failedIntentRecordPreventsTheMutationEntirely() {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            store.writableDatabase.execSQL("""CREATE TRIGGER reject_intent BEFORE INSERT ON execution_events
                WHEN NEW.type='WORKFLOW_UPDATE_STARTED' BEGIN SELECT RAISE(ABORT,'fixture'); END""")
            val result = invoke(controller, "notepad_manage", JSONObject().put("action", "append").put("expected_revision", 0)
                .put("content", "must not be saved"))
            assertFalse(result.getBoolean("ok"))
            assertNull(GoalPlanNotepadStore.get(context).executionSnapshot(scope).notepadRevision)
            assertFalse(store.timeline(request().runId).any { it.event.type == RunEventType.WORKFLOW_UPDATE_STARTED })
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", false, "", "fixture"), false)
        }
    }

    @Test fun optimisticConflictSettlesAsFailedWithoutLeavingAnUncertainMutation() {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request())
            recorder.bindController(controller); recorder.start()
            val result = invoke(controller, "plan_manage", plan(9))
            assertFalse(result.getBoolean("ok"))
            assertEquals("PLAN_REVISION_CONFLICT", result.getString("code"))
            val item = store.items(request().runId).single()
            assertEquals(RunState.FAILED, item.state)
            assertNotNull(item.completedAt)
            recorder.finish(AgentRuntimeWire.RunResult("workflow-run", true, "conflict reported"), false)
            assertEquals(RunState.SUCCEEDED, store.find(request().runId)?.state)
        }
    }
}
